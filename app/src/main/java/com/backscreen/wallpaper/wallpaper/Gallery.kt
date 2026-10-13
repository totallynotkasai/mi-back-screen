package com.backscreen.wallpaper.wallpaper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.ImageView
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import java.io.File
import java.io.FileNotFoundException
import kotlin.random.Random

/** How an image is fitted to the back screen. */
enum class Scaling(val label: Int, val scaleType: ImageView.ScaleType) {
    /** Covers the screen, cropping what doesn't fit. */
    FILL(R.string.scale_fill, ImageView.ScaleType.CENTER_CROP),
    /** All of the image, with black bars where it doesn't reach. */
    FIT(R.string.scale_fit, ImageView.ScaleType.FIT_CENTER),
    /** Squashed or stretched to exactly the screen's shape. */
    STRETCH(R.string.scale_stretch, ImageView.ScaleType.FIT_XY),
    /** Its own size, centred. See [WallpaperView]. */
    NONE(R.string.scale_none, ImageView.ScaleType.MATRIX),
}

/** Whether the app can still read the chosen folder. */
enum class FolderAccess {
    /** It can, or there's no folder. */
    OK,

    /** Android no longer lets it: access was taken back, or lost to 2.0's bug. */
    LOST,

    /** It's still allowed, but the folder couldn't be read: moved, renamed or deleted, say. */
    UNREADABLE,
}

/**
 * The images to show and which one is showing. They're either copies of images you picked
 * (kept in app storage) or a folder you chose, read afresh each time so new images in it are
 * picked up. With more than one, the wallpaper moves on to the next every [interval]; it only
 * does that while showing, so the schedules pause it too.
 */
object Gallery {
    private const val PREFS = "gallery"
    private const val KEY_FOLDER = "folder"
    private const val KEY_FOLDER_NAME = "folder_name"
    private const val KEY_CURRENT = "current"
    private const val KEY_CHANGED_AT = "changed_at"
    private const val KEY_INTERVAL = "interval_min"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_SCALING = "scaling"

    /** How often it can change, in minutes. */
    val INTERVALS = intArrayOf(1, 5, 15, 30, 60, 180, 360, 720, 1440)
    private const val DEFAULT_INTERVAL = 30

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun imagesDir(context: Context) = File(context.filesDir, "images")

    /** Before galleries there was one image; it becomes the first picked image. */
    fun migrate(context: Context) {
        val old = File(context.filesDir, "wallpaper")
        if (!old.exists()) return
        imagesDir(context).mkdirs()
        old.renameTo(File(imagesDir(context), "img_000"))
    }

    /** The chosen folder, if images come from one. */
    fun folder(context: Context): Uri? = prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)

    /** Every image, in order. Empty if none are chosen or the folder can't be read ([access]). */
    fun images(context: Context): List<Uri> {
        val folder = folder(context)
            ?: return imagesDir(context).listFiles().orEmpty().sortedBy { it.name }.map(Uri::fromFile)
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                folder, DocumentsContract.getTreeDocumentId(folder)
            )
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            val found = mutableListOf<Pair<String, Uri>>()
            val cursor = context.contentResolver.query(children, columns, null, null, null)
                ?: throw FileNotFoundException("nothing answered")
            cursor.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1)?.startsWith("image/") != true) continue
                    found += c.getString(2).orEmpty() to DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0))
                }
            }
            listed(context, folder, null)
            found.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first }).map { it.second }
        } catch (e: Exception) {
            listed(context, folder, e)
            emptyList()
        }
    }

    // Whether the chosen folder could be read last time, so a change is logged once and the
    // keeper's notification updated. The folder it's for, as a folder chosen again starts afresh.
    private class Listing(val folder: Uri, val access: FolderAccess)

    @Volatile private var lastListing: Listing? = null
    private val listingLock = Any()

    /**
     * Whether the chosen folder could be read when it was last listed: [FolderAccess.OK] until
     * then. Any thread; it doesn't read the folder.
     */
    fun access(context: Context): FolderAccess {
        val folder = folder(context) ?: return FolderAccess.OK
        return lastListing?.takeIf { it.folder == folder }?.access ?: FolderAccess.OK
    }

    /** [folder] was just listed, and failed with [error] if not null. Not on the main thread. */
    private fun listed(context: Context, folder: Uri, error: Exception?) {
        // A listing that began before the folder was replaced says nothing about the new choice.
        if (folder(context) != folder) return
        val access = when {
            !hasGrant(context, folder) -> FolderAccess.LOST
            error != null -> FolderAccess.UNREADABLE
            else -> FolderAccess.OK
        }
        val before = synchronized(listingLock) {
            lastListing.also { lastListing = Listing(folder, access) }
        }?.takeIf { it.folder == folder }?.access
        if (access == before || (before == null && access == FolderAccess.OK)) return
        val name = folderName(context)
        BackScreen.log(
            when (access) {
                FolderAccess.OK -> "Can open folder $name again"
                FolderAccess.LOST -> "Can't open folder $name: access was taken back. Choose it again"
                FolderAccess.UNREADABLE -> "Can't open folder $name: ${error?.message}"
            }
        )
        BackScreen.mainHandler.post { KeeperService.instance?.folderAccessChanged() }
    }

    /** Whether Android still lets the app read [folder] after a restart. */
    private fun hasGrant(context: Context, folder: Uri) =
        context.contentResolver.persistedUriPermissions.any { it.uri == folder && it.isReadPermission }

    /** Forgets how the last folder listing went, for a new choice. */
    private fun forgetListing() {
        synchronized(listingLock) { lastListing = null }
        BackScreen.mainHandler.post { KeeperService.instance?.folderAccessChanged() }
    }

    /**
     * The folder's name, e.g. "Wallpapers". Kept when it can be read, so it can still be named
     * once it can't. Not on the main thread.
     */
    fun folderName(context: Context): String? {
        val folder = folder(context) ?: return null
        val prefs = prefs(context)
        val name = try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        if (name != null) {
            if (name != prefs.getString(KEY_FOLDER_NAME, null)) prefs.edit().putString(KEY_FOLDER_NAME, name).apply()
            return name
        }
        return prefs.getString(KEY_FOLDER_NAME, null) ?: nameInPath(folder)
    }

    /**
     * For a folder chosen before its name was kept: the end of its path, which is what a folder
     * on the phone's own storage is called ("primary:Pictures/Wallpapers").
     */
    private fun nameInPath(folder: Uri): String? = try {
        DocumentsContract.getTreeDocumentId(folder).substringAfterLast(':').substringAfterLast('/').ifEmpty { null }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Replaces the images with copies of [picked]. Slow for big files; call off the main thread. */
    fun setImages(context: Context, picked: List<Uri>) {
        val dir = imagesDir(context)
        val staging = File(context.filesDir, "images_new").apply { deleteRecursively(); mkdirs() }
        // New names, so the back screen doesn't take a new image for one it already shows.
        val names = CopyNames.next(dir.list().orEmpty().toList(), picked.size, System.currentTimeMillis())
        picked.forEachIndexed { i, uri ->
            // Copy: the picker's access to the originals is temporary.
            context.contentResolver.openInputStream(uri)!!.use { input ->
                File(staging, names[i]).outputStream().use { input.copyTo(it) }
            }
        }
        dir.deleteRecursively()
        staging.renameTo(dir)
        releaseFolder(context)
        restart(context)
    }

    fun setFolder(context: Context, folder: Uri) {
        // Keep access after the app restarts.
        context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Let go of the old folder, unless it's this one again: that would undo the line above, and
        // the picker's own access lasts only until the app closes.
        if (folder(context) != folder) releaseFolder(context)
        imagesDir(context).deleteRecursively()
        prefs(context).edit().putString(KEY_FOLDER, folder.toString()).apply()
        restart(context)
        forgetListing()
    }

    /** No images: deletes the copies, or lets go of the folder. The originals are untouched. */
    fun clear(context: Context) {
        imagesDir(context).deleteRecursively()
        releaseFolder(context)
        restart(context)
    }

    private fun releaseFolder(context: Context) {
        val old = folder(context) ?: return
        // Forgotten first, so a listing still running doesn't take the release for lost access.
        prefs(context).edit().remove(KEY_FOLDER).remove(KEY_FOLDER_NAME).apply()
        try {
            context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already gone.
        }
        forgetListing()
    }

    private fun restart(context: Context) {
        prefs(context).edit().remove(KEY_CURRENT).remove(KEY_CHANGED_AT).apply()
    }

    fun interval(context: Context) = prefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL)

    fun setInterval(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL, minutes).apply()
    }

    fun shuffle(context: Context) = prefs(context).getBoolean(KEY_SHUFFLE, false)

    fun setShuffle(context: Context, shuffle: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHUFFLE, shuffle).apply()
    }

    fun scaling(context: Context): Scaling {
        val name = prefs(context).getString(KEY_SCALING, null)
        return Scaling.entries.firstOrNull { it.name == name } ?: Scaling.FILL
    }

    fun setScaling(context: Context, scaling: Scaling) {
        prefs(context).edit().putString(KEY_SCALING, scaling.name).apply()
    }

    /**
     * The image to show now. With [advance], moves on to the next one first if this one has had
     * its time; without, just reports what's showing.
     */
    fun current(context: Context, advance: Boolean = false): Uri? {
        val images = images(context)
        if (images.isEmpty()) return null
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val index = images.indexOf(prefs.getString(KEY_CURRENT, null)?.let(Uri::parse))
        val changedAt = prefs.getLong(KEY_CHANGED_AT, 0)
        return when {
            index < 0 -> show(context, images, if (shuffle(context)) Random.nextInt(images.size) else 0, now)
            // After the clock was set back, count from now.
            now < changedAt -> show(context, images, index, now)
            advance && images.size > 1 && now - changedAt >= interval(context) * 60_000L ->
                show(context, images, nextIndex(context, images, index), now)
            else -> images[index]
        }
    }

    /** The image last shown, without checking it's still there. */
    fun shown(context: Context): Uri? = prefs(context).getString(KEY_CURRENT, null)?.let(Uri::parse)

    /** Moves on to the next image straight away. */
    fun skip(context: Context): Uri? {
        val images = images(context)
        if (images.isEmpty()) return null
        val index = images.indexOf(prefs(context).getString(KEY_CURRENT, null)?.let(Uri::parse))
        return show(context, images, nextIndex(context, images, index), System.currentTimeMillis())
    }

    /** When the image showing now is due to change, or null if there's nothing to change to. */
    fun nextChangeAt(context: Context): Long? {
        if (images(context).size < 2) return null
        return prefs(context).getLong(KEY_CHANGED_AT, 0) + interval(context) * 60_000L
    }

    private fun nextIndex(context: Context, images: List<Uri>, index: Int): Int = when {
        images.size < 2 -> 0
        // Any image but this one.
        shuffle(context) -> (index + 1 + Random.nextInt(images.size - 1)).mod(images.size)
        else -> (index + 1) % images.size
    }

    private fun show(context: Context, images: List<Uri>, index: Int, now: Long): Uri {
        prefs(context).edit()
            .putString(KEY_CURRENT, images[index].toString())
            .putLong(KEY_CHANGED_AT, now)
            .apply()
        return images[index]
    }
}
