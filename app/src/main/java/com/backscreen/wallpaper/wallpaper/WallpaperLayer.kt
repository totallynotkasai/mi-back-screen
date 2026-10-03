package com.backscreen.wallpaper.wallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.Display
import android.widget.FrameLayout
import com.backscreen.wallpaper.core.BackScreen
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The back screen's bottom layer: the gallery's images, each fading in over the last, or black
 * with none. Decoding happens on its own thread, so a big image or a slow folder doesn't hold
 * up the screen.
 */
@SuppressLint("ViewConstructor")
class WallpaperLayer(context: Context, private val display: Display) : FrameLayout(context) {

    /** Whether the back screen is lit. A change only fades in then; a dimmed one would show a moment of it. */
    var isLit: () -> Boolean = { true }

    /** What it shows changed: a new image, or none. */
    var onShownChanged: (() -> Unit)? = null

    /** A different image is under the clock, so its automatic colours may need to change. */
    var onBackdropChanged: (() -> Unit)? = null

    /** The image under the clock moved, as it pans. */
    var onBackdropMoved: (() -> Unit)? = null

    /**
     * Whether images pan and GIFs play. Only while the back screen is lit: a dimmed one keeps
     * showing the last frame, and moving would just use battery.
     */
    var moving = false
        set(value) {
            field = value
            for (image in images()) image.moving = value
        }

    @Volatile private var shown: Uri? = null
    private var loads = 0

    // A reload overtaken by a later request (the rear waking, say) must still happen.
    private var reloadPending = false
    private var released = false

    private val loader = Executors.newSingleThreadExecutor()
    private val nextImage = Runnable { showImage(advance = true) }

    /** The first image straight away, so there's no blank moment. Main thread. */
    fun showFirst() {
        try {
            val uri = Gallery.current(context, advance = true)
            if (uri == null) {
                BackScreen.log("No images; the back screen shows black")
            } else {
                setImage(uri, BackScreen.loadImage(context, uri, display, WallpaperSettings.scalingInUse(context)), fade = false)
                BackScreen.log("Wallpaper showing on back screen")
            }
        } catch (e: Exception) {
            BackScreen.log("Couldn't load image: ${e.message}")
        }
        scheduleNextImage()
    }

    /**
     * Shows the gallery's current image, moving on to the next first if [advance] and it's time.
     * With [force], reloads it even if it's the one already showing. With no images, fades to
     * black.
     */
    fun showImage(advance: Boolean, force: Boolean = false) {
        if (released) return
        val load = ++loads
        val reload = force || reloadPending
        reloadPending = reload
        loader.execute {
            try {
                val uri = Gallery.current(context, advance)
                val drawable = if (uri == null || (uri == shown && !reload)) null
                else BackScreen.loadImage(context, uri, display, WallpaperSettings.scalingInUse(context))
                val next = Gallery.nextChangeAt(context)
                BackScreen.mainHandler.post {
                    if (released || load != loads) return@post
                    reloadPending = false
                    if (uri != null && drawable != null) setImage(uri, drawable, fade = true)
                    if (uri == null) clearImage()
                    setNextImage(next)
                }
            } catch (e: Exception) {
                BackScreen.log("Couldn't load image: ${e.message}")
                BackScreen.mainHandler.post { if (!released) scheduleNextImage() }
            }
        }
    }

    /**
     * Sets the gallery's next change, for when the interval or images change too. Working it out
     * reads the folder, which can take half a second for a big one, so it's done on the loader's
     * thread: on the main thread it held up the pan and the clock.
     */
    fun scheduleNextImage() {
        if (released) return
        loader.execute {
            val next = Gallery.nextChangeAt(context)
            BackScreen.mainHandler.post { setNextImage(next) }
        }
    }

    private fun setNextImage(at: Long?) {
        BackScreen.mainHandler.removeCallbacks(nextImage)
        if (released || at == null) return
        // While the phone sleeps this runs late; the host catches up when the rear wakes.
        BackScreen.mainHandler.postDelayed(nextImage, max(MIN_DELAY_MS, at - System.currentTimeMillis()))
    }

    /** The pan speed changed: the image showing carries on from where it is, at the new speed. */
    fun panChanged() {
        val pan = WallpaperSettings.pan(context)
        for (image in images()) image.pan = pan
    }

    /** Stops loading and animating, for good. */
    fun release() {
        released = true
        BackScreen.mainHandler.removeCallbacks(nextImage)
        loader.shutdownNow()
        moving = false
    }

    /** Puts [drawable] on top, fading it in over the old image (if [fade] and lit), which is then removed. */
    private fun setImage(uri: Uri, drawable: Drawable, fade: Boolean) {
        shown = uri
        val view = WallpaperView(context).apply {
            scaling = WallpaperSettings.scalingInUse(context)
            pan = WallpaperSettings.pan(context)
            setImageDrawable(drawable)
            onPanned = { onBackdropMoved?.invoke() }
            moving = this@WallpaperLayer.moving
        }
        val old = images()
        addView(view)
        val removeOld = Runnable {
            for (o in old) {
                o.moving = false
                removeView(o)
            }
            onBackdropChanged?.invoke()
        }
        if (fade && old.isNotEmpty() && isLit()) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(FADE_MS).withEndAction(removeOld)
        } else {
            removeOld.run()
        }
        BackScreen.log("Showing image ${uri.lastPathSegment?.substringAfterLast('/')}")
        onShownChanged?.invoke()
    }

    /** The images were removed: fade out whatever is showing (at once if dimmed), leaving black. */
    private fun clearImage() {
        if (shown == null && childCount == 0) return
        shown = null
        for (o in images()) {
            val remove = Runnable {
                o.moving = false
                removeView(o)
                onBackdropChanged?.invoke()
            }
            if (isLit()) o.animate().alpha(0f).setDuration(FADE_MS).withEndAction(remove) else remove.run()
        }
        BackScreen.log("No images; the back screen shows black")
        onShownChanged?.invoke()
    }

    private fun images() = (0 until childCount).map { getChildAt(it) as WallpaperView }

    private companion object {
        const val FADE_MS = 800L
        const val MIN_DELAY_MS = 1000L
    }
}
