package com.backscreen.wallpaper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.ImageView
import java.io.File
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

/**
 * The images to show and which one is showing. They're either copies of images you picked
 * (kept in app storage) or a folder you chose, read afresh each time so new images in it are
 * picked up. With more than one, the wallpaper moves on to the next every [interval]; it only
 * does that while showing, so the schedules pause it too.
 */
object Gallery {
    private const val PREFS = "gallery"
    private const val KEY_FOLDER = "folder"
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

    /** Every image, in order. Empty if none are chosen or the folder can't be read. */
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
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1)?.startsWith("image/") != true) continue
                    found += c.getString(2).orEmpty() to DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0))
                }
            }
            found.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first }).map { it.second }
        } catch (e: Exception) {
            BackScreen.log("Couldn't read folder: ${e.message}")
            emptyList()
        }
    }

    fun hasImages(context: Context) = images(context).isNotEmpty()

    /** The folder's name, e.g. "Wallpapers". */
    fun folderName(context: Context): String? {
        val folder = folder(context) ?: return null
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    /** Replaces the images with copies of [picked]. Slow for big files; call off the main thread. */
    fun setImages(context: Context, picked: List<Uri>) {
        val dir = imagesDir(context)
        val staging = File(context.filesDir, "images_new").apply { deleteRecursively(); mkdirs() }
        picked.forEachIndexed { i, uri ->
            // Copy: the picker's access to the originals is temporary.
            context.contentResolver.openInputStream(uri)!!.use { input ->
                File(staging, "img_%03d".format(i)).outputStream().use { input.copyTo(it) }
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
        releaseFolder(context)
        imagesDir(context).deleteRecursively()
        prefs(context).edit().putString(KEY_FOLDER, folder.toString()).apply()
        restart(context)
    }

    /** No images: deletes the copies, or lets go of the folder. The originals are untouched. */
    fun clear(context: Context) {
        imagesDir(context).deleteRecursively()
        releaseFolder(context)
        restart(context)
    }

    private fun releaseFolder(context: Context) {
        val old = folder(context) ?: return
        try {
            context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already gone.
        }
        prefs(context).edit().remove(KEY_FOLDER).apply()
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
