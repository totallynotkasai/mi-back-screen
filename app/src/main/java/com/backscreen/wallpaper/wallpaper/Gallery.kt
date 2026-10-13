package com.backscreen.wallpaper.wallpaper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.widget.ImageView
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 * (kept in app storage) or a folder you chose, read afresh (at most every few seconds) so new
 * images in it are picked up. With more than one, the wallpaper moves on to the next every
 * [interval]; it only does that while showing, so the schedules pause it too. Which one is next
 * is [GalleryRotation]'s.
 *
 * The back screen, the Wallpaper tab's preview and the other tabs' backdrop each ask which image
 * is showing, from threads of their own, so the place is read, decided and saved under one lock:
 * they always agree. Changes to the images go through [io], one at a time.
 */
object Gallery {
    private const val PREFS = "gallery"
    private const val KEY_FOLDER = "folder"
    private const val KEY_FOLDER_NAME = "folder_name"
    private const val KEY_CURRENT = "current"
    private const val KEY_CHANGED_AT = "changed_at"
    private const val KEY_SEED = "shuffle_seed"
    private const val KEY_INTERVAL = "interval_min"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_SCALING = "scaling"

    /** How often it can change, in minutes. */
    val INTERVALS = intArrayOf(1, 5, 15, 30, 60, 180, 360, 720, 1440)
    private const val DEFAULT_INTERVAL = 30

    // A listing is used again for this long: one change of image asks for it several times.
    private const val LISTING_KEPT_MS = 3000L

    // Images tried for one change before giving up until the next.
    private const val MAX_TRIES = 3

    // The copies' "folder", for the listing kept.
    private const val COPIES = "copies"

    /**
     * What's listed from a folder: the types Android can decode. Anything else that calls itself
     * an image (a camera's RAW files, say) would only fail, or take long to, on the back screen.
     */
    private val TYPES = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/x-ms-bmp",
        "image/x-icon", "image/vnd.microsoft.icon", "image/vnd.wap.wbmp",
        "image/heif", "image/heic", "image/heif-sequence", "image/heic-sequence", "image/avif",
    )

    /** Every change to the images (choosing them or a folder, removing them, Next) runs here, in turn, whichever screen asked. */
    val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "BackScreen-gallery") }

    // Reading, deciding and saving the place; and the copies' folder while it's swapped.
    private val lock = Any()

    // Images that couldn't be decoded, and when (elapsedRealtime): passed over for a while, and
    // logged once each time. Not for good, since the keeper can run for weeks: a file still being
    // copied into the folder fails too.
    private val bad = ConcurrentHashMap<String, Long>()
    private const val BAD_FOR_MS = 60 * 60_000L

    // The last listing, of the folder or the copies ([source]), when it was made and what it
    // found (null: the folder couldn't be read). A change to the images starts a new generation.
    private class Kept(val source: String, val generation: Int, val at: Long, val images: List<Uri>?)

    @Volatile private var kept: Kept? = null
    @Volatile private var generation = 0

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

    /**
     * Every image, in order. Empty if none are chosen or the folder can't be read ([access]).
     * [fresh]: read again now, rather than use a listing from the last few seconds.
     */
    fun images(context: Context, fresh: Boolean = false): List<Uri> = list(context, fresh).orEmpty()

    /** The images, or null if the folder can't be read; a listing from the last few seconds will do unless [fresh]. */
    private fun list(context: Context, fresh: Boolean = false): List<Uri>? {
        val folder = folder(context)
        val source = folder?.toString() ?: COPIES
        val gen = generation
        val now = SystemClock.elapsedRealtime()
        kept?.takeIf { !fresh && it.source == source && it.generation == gen && now - it.at < LISTING_KEPT_MS }
            ?.let { return it.images }
        // The copies are listed under the lock, so never halfway through being replaced.
        val images = if (folder == null) synchronized(lock) { listCopies(context) } else listFolder(context, folder)
        if (generation == gen) kept = Kept(source, gen, now, images)
        return images
    }

    private fun listCopies(context: Context) = imagesDir(context).listFiles().orEmpty().sortedBy { it.name }.map(Uri::fromFile)

    /** [folder]'s images, by name; null if it can't be read. */
    private fun listFolder(context: Context, folder: Uri): List<Uri>? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
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
                if (c.getString(1)?.lowercase() !in TYPES) continue
                found += c.getString(2).orEmpty() to DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0))
            }
        }
        listed(context, folder, null)
        found.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first }).map { it.second }
    } catch (e: Exception) {
        listed(context, folder, e)
        null
    }

    /** No listing kept from before a change to the images, and every image gets another try. */
    private fun forgetKept() {
        generation++
        kept = null
        bad.clear()
    }

    /** The images passed over just now ([markBad]); ones marked long enough ago get another try. */
    private fun badNow(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        bad.entries.removeIf { now - it.value >= BAD_FOR_MS }
        return bad.keys.toSet()
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

    /**
     * Replaces the images with copies of [picked]. Slow for big files: on [io]. Throws if a copy
     * fails, and then nothing has changed.
     */
    fun setImages(context: Context, picked: List<Uri>) {
        val dir = imagesDir(context)
        val staging = File(context.filesDir, "images_new").apply { deleteRecursively(); mkdirs() }
        try {
            // New names, so the back screen doesn't take a new image for one it already shows.
            val names = CopyNames.next(dir.list().orEmpty().toList(), picked.size, System.currentTimeMillis())
            picked.forEachIndexed { i, uri ->
                // Copy: the picker's access to the originals is temporary.
                val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("couldn't open $uri")
                input.use { File(staging, names[i]).outputStream().use { out -> it.copyTo(out) } }
            }
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
        val old = File(context.filesDir, "images_old")
        synchronized(lock) {
            // The old copies go aside, the new ones take their place, and the old ones come back
            // if that fails; nothing lists the folder in between.
            old.deleteRecursively()
            if (dir.exists() && !dir.renameTo(old)) {
                staging.deleteRecursively()
                throw IOException("couldn't move the old images aside")
            }
            if (!staging.renameTo(dir)) {
                old.renameTo(dir)
                staging.deleteRecursively()
                throw IOException("couldn't put the new images in place")
            }
            releaseFolder(context)
            restart(context)
            forgetKept()
        }
        old.deleteRecursively()
    }

    /** Takes [folder]'s images instead. On [io]. */
    fun setFolder(context: Context, folder: Uri) {
        // Keep access after the app restarts.
        context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        synchronized(lock) {
            // Let go of the old folder, unless it's this one again: that would undo the line
            // above, and the picker's own access lasts only until the app closes.
            if (folder(context) != folder) releaseFolder(context)
            prefs(context).edit().putString(KEY_FOLDER, folder.toString()).apply()
            restart(context)
            forgetKept()
        }
        forgetListing()
        deleteCopies(context)
    }

    /** No images: deletes the copies, or lets go of the folder. The originals are untouched. On [io]. */
    fun clear(context: Context) {
        synchronized(lock) {
            releaseFolder(context)
            restart(context)
            forgetKept()
        }
        deleteCopies(context)
    }

    /** The copies, if any: moved aside under the lock, so nothing lists them half deleted, then deleted. */
    private fun deleteCopies(context: Context) {
        val old = File(context.filesDir, "images_old")
        synchronized(lock) {
            old.deleteRecursively()
            if (!imagesDir(context).renameTo(old)) return
            forgetKept()
        }
        old.deleteRecursively()
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

    /** Switching shuffle on starts a new order. */
    fun setShuffle(context: Context, shuffle: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHUFFLE, shuffle).apply { if (shuffle) putLong(KEY_SEED, newSeed()) }.apply()
    }

    private fun newSeed() = Random.nextLong().takeIf { it != 0L } ?: 1L

    fun scaling(context: Context): Scaling {
        val name = prefs(context).getString(KEY_SCALING, null)
        return Scaling.entries.firstOrNull { it.name == name } ?: Scaling.FILL
    }

    fun setScaling(context: Context, scaling: Scaling) {
        prefs(context).edit().putString(KEY_SCALING, scaling.name).apply()
    }

    /** What shows now: the image (null for none), how many images there are, and when it next changes. */
    data class Shown(val image: Uri?, val count: Int, val nextChangeAt: Long?)

    /**
     * The image to show now. With [advance], moves on to the next one first if this one has had
     * its time; without, just reports what's showing (or moves past it if it's gone). Not on the
     * main thread: it may read the folder.
     */
    fun current(context: Context, advance: Boolean = false): Shown =
        step(context) { images, skipped, place, now, interval ->
            GalleryRotation.current(images, skipped, place, shuffle(context), now, interval, advance, ::newSeed)
        }

    /** Moves on to the next image straight away. Not on the main thread. */
    fun skip(context: Context): Uri? = step(context) { images, skipped, place, now, _ ->
        GalleryRotation.skip(images, skipped, place, shuffle(context), now, ::newSeed)
    }.image

    /** When the image showing now is due to change, or null if there's nothing to change to. Not on the main thread. */
    fun nextChangeAt(context: Context): Long? {
        val images = list(context) ?: return null
        val skipped = badNow()
        val usable = images.count { it.toString() !in skipped }
        return synchronized(lock) { GalleryRotation.nextChangeAt(usable, place(context), interval(context) * 60_000L) }
    }

    /** The image last shown, without checking it's still there. */
    fun shown(context: Context): Uri? = prefs(context).getString(KEY_CURRENT, null)?.let(Uri::parse)

    /**
     * [uri] couldn't be decoded ([error]): it's passed over for an hour, or until the images
     * change, and said once. Not if it has gone, or can't be read: that's the file's place, not
     * the file, and the listing and [access] deal with those.
     */
    fun markBad(uri: Uri, error: Exception) {
        if (error is FileNotFoundException || error is SecurityException) return
        if (bad.put(uri.toString(), SystemClock.elapsedRealtime()) != null) return
        BackScreen.log("Skipping ${uri.lastPathSegment?.substringAfterLast('/')}: it can't be shown (${error.javaClass.simpleName}: ${error.message})")
    }

    /** What shows now, and what [decode] made of its image: null with no image, or when it's [alreadyShown]. */
    class Decoded<T>(val shown: Shown, val result: T?)

    /**
     * [current], decoded by [decode]. An image [decode] throws on is passed over ([markBad]) and
     * the next one tried, up to 3 in all; then it waits for the next change. An Error, such as
     * running out of memory, isn't the file's fault: it's thrown on. Not on the main thread.
     */
    fun <T : Any> decodeCurrent(context: Context, advance: Boolean, alreadyShown: (Uri) -> Boolean = { false }, decode: (Uri) -> T): Decoded<T> {
        var shown = current(context, advance)
        var tries = 0
        while (true) {
            val uri = shown.image ?: return Decoded(shown, null)
            if (alreadyShown(uri)) return Decoded(shown, null)
            try {
                return Decoded(shown, decode(uri))
            } catch (e: Exception) {
                markBad(uri, e)
            }
            if (++tries == MAX_TRIES) return Decoded(shown, null)
            // It's passed over now, so this is the one after it.
            shown = current(context)
        }
    }

    /**
     * Reads the place, lets [decide] work out the step, and saves it, all under the lock, so two
     * threads asking at once agree on the image. The listing is read first, outside it.
     */
    private fun step(context: Context, decide: (List<String>, Set<String>, GalleryPlace, Long, Long) -> GalleryStep): Shown {
        // Can't be read just now: nothing shows, and it carries on from the same place once it can.
        val images = list(context) ?: return Shown(null, 0, null)
        val names = images.map(Uri::toString)
        val skipped = badNow()
        synchronized(lock) {
            val place = place(context)
            val interval = interval(context) * 60_000L
            val step = decide(names, skipped, place, System.currentTimeMillis(), interval)
            if (step.place != place) save(context, step.place)
            val image = step.image?.let { images[names.indexOf(it)] }
            val usable = names.count { it !in skipped }
            return Shown(image, images.size, GalleryRotation.nextChangeAt(usable, step.place, interval))
        }
    }

    private fun place(context: Context): GalleryPlace {
        val prefs = prefs(context)
        return GalleryPlace(prefs.getString(KEY_CURRENT, null), prefs.getLong(KEY_CHANGED_AT, 0L), prefs.getLong(KEY_SEED, 0L))
    }

    private fun save(context: Context, place: GalleryPlace) {
        prefs(context).edit().apply {
            if (place.current == null) remove(KEY_CURRENT) else putString(KEY_CURRENT, place.current)
            putLong(KEY_CHANGED_AT, place.changedAt)
            putLong(KEY_SEED, place.seed)
        }.apply()
    }
}
