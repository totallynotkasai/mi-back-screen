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
 * up the screen; the first image too, so the window opens black for a moment. The gallery moves
 * on only while the window can be seen ([pause], [resume]).
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

    /** The image showing has a different colour for the charging animation's Auto ([WallpaperColour]), or none. */
    var onColourChanged: ((Int?) -> Unit)? = null

    /** The image showing's colour for the charging animation's Auto, or null for none. */
    var colour: Int? = null
        private set

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
    private val reload = ReloadRule()
    private var released = false

    // While the window can't be seen (under the camera or an app, or the back screen is off), the
    // gallery doesn't move on; it catches up when it's back ([resume]).
    private var paused = false

    private val loader = Executors.newSingleThreadExecutor()
    private val nextImage = Runnable { showImage(advance = true) }

    /** The window can't be seen: no decoding, colours or new frames until it can. */
    fun pause() {
        paused = true
        BackScreen.mainHandler.removeCallbacks(nextImage)
    }

    /**
     * The window can be seen: shows the gallery's image, moving on if it's time. Also how the
     * first image goes up, off the main thread, so the screen is black for a moment first.
     */
    fun resume() {
        paused = false
        showImage(advance = true)
    }

    /**
     * Shows the gallery's current image, moving on to the next first if [advance] and it's time.
     * With [force], reloads it even if it's the one already showing. With no images, fades to
     * black. An image that can't be decoded is passed over ([Gallery.decodeCurrent]).
     */
    fun showImage(advance: Boolean, force: Boolean = false) {
        if (released) return
        if (paused) {
            // Done on resume.
            if (force) reload.ask()
            return
        }
        val load = reload.start(force)
        loader.execute {
            val decoded = try {
                Gallery.decodeCurrent(context, advance, alreadyShown = { it == shown && !load.reload }) { uri ->
                    val drawable = BackScreen.loadImage(context, uri, display, WallpaperSettings.scalingInUse(context))
                    drawable to WallpaperColour.of(drawable)
                }
            } catch (e: Throwable) {
                // Out of memory too: uncaught on this thread, it would take the app down.
                BackScreen.log("Couldn't load image: $e")
                null
            }
            BackScreen.mainHandler.post {
                if (released || !reload.ended(load)) return@post
                if (decoded == null) return@post scheduleNextImage()
                val uri = decoded.shown.image
                val result = decoded.result
                if (uri != null && result != null) setImage(uri, result.first, result.second, fade = true)
                if (uri == null) clearImage()
                setNextImage(decoded.shown.nextChangeAt)
            }
        }
    }

    /**
     * Sets the gallery's next change, for when the interval or images change too. Working it out
     * reads the folder, which can take half a second for a big one, so it's done on the loader's
     * thread: on the main thread it held up the pan and the clock.
     */
    fun scheduleNextImage() {
        if (released || paused) return
        loader.execute {
            val next = Gallery.nextChangeAt(context)
            BackScreen.mainHandler.post { setNextImage(next) }
        }
    }

    private fun setNextImage(at: Long?) {
        BackScreen.mainHandler.removeCallbacks(nextImage)
        if (released || paused || at == null) return
        // While the phone sleeps this runs late; the host catches up when the rear wakes.
        BackScreen.mainHandler.postDelayed(nextImage, max(MIN_DELAY_MS, at - System.currentTimeMillis()))
    }

    /**
     * The pan speed or Skip short pans changed: the image showing carries on from where it is, at
     * the new speed, or stays still if it's now too short a pan (or pans again, from the start).
     */
    fun panChanged() {
        val pan = WallpaperSettings.pan(context)
        val minTravel = WallpaperSettings.panMinTravel(context)
        for (image in images()) {
            image.pan = pan
            image.minTravel = minTravel
        }
    }

    /** Stops loading and animating, for good. */
    fun release() {
        released = true
        BackScreen.mainHandler.removeCallbacks(nextImage)
        loader.shutdownNow()
        moving = false
    }

    /**
     * Puts [drawable] on top, fading it in over the old image (if [fade] and lit), which is then
     * removed. [colour] is its colour for the charging animation's Auto.
     */
    private fun setImage(uri: Uri, drawable: Drawable, colour: Int?, fade: Boolean) {
        shown = uri
        setColour(colour)
        val view = WallpaperView(context).apply {
            scaling = WallpaperSettings.scalingInUse(context)
            pan = WallpaperSettings.pan(context)
            minTravel = WallpaperSettings.panMinTravel(context)
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
        setColour(null)
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

    private fun setColour(value: Int?) {
        if (value == colour) return
        colour = value
        onColourChanged?.invoke(value)
    }

    private fun images() = (0 until childCount).map { getChildAt(it) as WallpaperView }

    private companion object {
        const val FADE_MS = 800L
        const val MIN_DELAY_MS = 1000L
    }
}
