package com.backscreen.wallpaper.wallpaper

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import com.backscreen.wallpaper.battery.BatterySettings
import com.backscreen.wallpaper.battery.ChargingLayer
import com.backscreen.wallpaper.battery.ChargingStatus
import com.backscreen.wallpaper.battery.ChargingStyle
import com.backscreen.wallpaper.core.BackScreen
import java.util.concurrent.Executors

/**
 * What a tab's preview of something on the back screen (the charging animation, a notification)
 * is drawn over: the wallpaper's image now, decoded as the back screen does and held where a pan
 * starts, with its clock; or black while the wallpaper is off (as a pop-over is) or has no
 * images. Decoding takes a moment, so it's done off the main thread, which the back screen
 * shares. With a [glow], it also shows the Edge glow's faint glow while the phone charges, as
 * the back screen does.
 */
class PreviewBackdrop(
    private val frame: RearPreviewLayout,
    private val image: WallpaperView,
    private val clock: ClockLayer,
    /** The clock's colours, or the image's [colour], may have changed; what's drawn over it takes them. */
    private val onColorsChanged: () -> Unit,
    private val glow: ChargingLayer? = null,
) {
    private val context get() = frame.context
    private val loader = Executors.newSingleThreadExecutor()
    private var loads = 0
    private var released = false

    // What it was loaded for: whether the wallpaper was on, and its image.
    private var loadedFor: Boolean? = null
    private var uri: Uri? = null

    /** The image's colour for the charging animation's Auto ([WallpaperColour]), or null for none. */
    var colour: Int? = null
        private set

    init {
        clock.backdrop = image
        clock.onColorsChanged = {
            matchGlow()
            onColorsChanged()
        }
    }

    /** Whether the clock shows, as it does on the back screen. */
    val clockShown get() = clock.visibility == View.VISIBLE

    /** Shown again: the Wallpaper tab may have changed what's under it. */
    fun reload() {
        BackScreen.findRearDisplay(context)?.let { frame.setRearDisplay(it, WallpaperSettings.avoidCamera(context)) }
        loadedFor = null
        refresh()
    }

    /** Catches up with the wallpaper's switch, image and clock, and charging. The tab calls this every second. */
    fun refresh() {
        val wallpaperOn = WallpaperSettings.isEnabled(context)
        if (wallpaperOn != loadedFor || (wallpaperOn && Gallery.shown(context) != uri)) load(wallpaperOn)
        showClock(wallpaperOn)
        showGlow(wallpaperOn)
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
            show(null, null, Scaling.FILL)
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
            val colour = drawable?.let(WallpaperColour::of)
            BackScreen.mainHandler.post {
                if (released || load != loads) return@post
                this.uri = uri
                show(drawable, colour, scaling)
            }
        }
    }

    private fun show(drawable: Drawable?, colour: Int?, scaling: Scaling) {
        this.colour = colour
        image.setBackgroundColor(Color.BLACK)
        image.scaling = scaling
        // It doesn't move here; with panning on, it holds where a pan starts.
        image.pan = WallpaperSettings.pan(context)
        image.minTravel = WallpaperSettings.panMinTravel(context)
        image.setImageDrawable(drawable)
        clock.backdropChanged()
        matchGlow()
        onColorsChanged()
    }

    /** The clock, as the back screen shows it: only with the wallpaper, which a pop-over doesn't have. */
    private fun showClock(wallpaperOn: Boolean) {
        val visibility = if (wallpaperOn && WallpaperSettings.showClock(context)) View.VISIBLE else View.GONE
        if (clock.visibility != visibility) {
            clock.visibility = visibility
            matchGlow()
            onColorsChanged()
        }
        val settings = WallpaperSettings.clockSettings(context)
        if (clock.settings != settings) clock.settings = settings
    }

    /** The faint Edge glow, while charging with that style in the wallpaper, as on the back screen. */
    private fun showGlow(wallpaperOn: Boolean) {
        val glow = glow ?: return
        val wanted = wallpaperOn && BatterySettings.isEnabled(context) &&
            BatterySettings.style(context) == ChargingStyle.EDGE_GLOW && ChargingStatus.isPlugged(context)
        if (wanted == glow.isFaint) return
        if (wanted) {
            matchGlow()
            glow.showFaint()
        } else {
            glow.clear()
        }
    }

    /** The glow's colour: the chosen one, or the image's, or the clock's. */
    private fun matchGlow() {
        val glow = glow ?: return
        glow.accent = BatterySettings.color(context)
        glow.setAutoAccent(colour)
        glow.setColors(if (clockShown) clock.textColor else Color.WHITE, Color.BLACK)
    }
}
