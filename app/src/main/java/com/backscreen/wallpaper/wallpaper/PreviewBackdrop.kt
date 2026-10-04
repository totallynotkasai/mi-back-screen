package com.backscreen.wallpaper.wallpaper

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import com.backscreen.wallpaper.core.BackScreen
import java.util.concurrent.Executors

/**
 * What a tab's preview of something on the back screen (the charging animation, a notification)
 * is drawn over: the wallpaper's image now, decoded as the back screen does and held where a pan
 * starts, with its clock; or black while the wallpaper is off (as a pop-over is) or has no
 * images. Decoding takes a moment, so it's done off the main thread, which the back screen
 * shares.
 */
class PreviewBackdrop(
    private val frame: RearPreviewLayout,
    private val image: WallpaperView,
    private val clock: ClockLayer,
    /** The clock's colours may have changed; what's drawn over it takes them. */
    private val onColorsChanged: () -> Unit,
) {
    private val context get() = frame.context
    private val loader = Executors.newSingleThreadExecutor()
    private var loads = 0
    private var released = false

    // What it was loaded for: whether the wallpaper was on, and its image.
    private var loadedFor: Boolean? = null
    private var uri: Uri? = null

    init {
        clock.backdrop = image
        clock.onColorsChanged = onColorsChanged
    }

    /** Whether the clock shows, as it does on the back screen. */
    val clockShown get() = clock.visibility == View.VISIBLE

    /** Shown again: the Wallpaper tab may have changed what's under it. */
    fun reload() {
        BackScreen.findRearDisplay(context)?.let { frame.setRearDisplay(it, WallpaperSettings.avoidCamera(context)) }
        loadedFor = null
        refresh()
    }

    /** Catches up with the wallpaper's switch, image and clock. The tab calls this every second. */
    fun refresh() {
        val wallpaperOn = WallpaperSettings.isEnabled(context)
        if (wallpaperOn != loadedFor || (wallpaperOn && Gallery.shown(context) != uri)) load(wallpaperOn)
        showClock(wallpaperOn)
    }

    fun release() {
        released = true
        loader.shutdownNow()
    }

    private fun load(wallpaperOn: Boolean) {
        loadedFor = wallpaperOn
        val load = ++loads
        if (!wallpaperOn) {
            uri = null
            show(null, Scaling.FILL)
            return
        }
        val display = BackScreen.findRearDisplay(context) ?: frame.display ?: return
        val app = context.applicationContext
        uri = Gallery.shown(context)
        loader.execute {
            val uri = Gallery.current(app)
            val scaling = WallpaperSettings.scalingInUse(app)
            val drawable = try {
                uri?.let { BackScreen.loadImage(app, it, display, scaling) }
            } catch (e: Exception) {
                BackScreen.log("Preview failed: ${e.message}")
                null
            }
            BackScreen.mainHandler.post {
                if (released || load != loads) return@post
                this.uri = uri
                show(drawable, scaling)
            }
        }
    }

    private fun show(drawable: Drawable?, scaling: Scaling) {
        image.setBackgroundColor(Color.BLACK)
        image.scaling = scaling
        // It doesn't move here; with panning on, it holds where a pan starts.
        image.pan = WallpaperSettings.pan(context)
        image.setImageDrawable(drawable)
        clock.backdropChanged()
        onColorsChanged()
    }

    /** The clock, as the back screen shows it: only with the wallpaper, which a pop-over doesn't have. */
    private fun showClock(wallpaperOn: Boolean) {
        val visibility = if (wallpaperOn && WallpaperSettings.showClock(context)) View.VISIBLE else View.GONE
        if (clock.visibility != visibility) {
            clock.visibility = visibility
            onColorsChanged()
        }
        val settings = WallpaperSettings.clockSettings(context)
        if (clock.settings != settings) clock.settings = settings
    }
}
