package com.backscreen.wallpaper.wallpaper

import android.content.Context
import com.backscreen.wallpaper.ToggleTile
import com.backscreen.wallpaper.ToggleWidget
import com.backscreen.wallpaper.core.FeatureSettings

/**
 * The Wallpaper section's settings. The file and keys are the ones every earlier version used,
 * so updating keeps the switch, the clock and the rest as they were. The gallery keeps its own
 * (see [Gallery]).
 */
object WallpaperSettings : FeatureSettings("backscreen") {
    private const val KEY_AVOID_CAMERA = "avoid_camera"
    private const val KEY_CLOCK = "clock"
    private const val KEY_CLOCK_STYLE = "clock_style"
    private const val KEY_CLOCK_X = "clock_x"
    private const val KEY_CLOCK_Y = "clock_y"
    private const val KEY_CLOCK_COLOR = "clock_color"
    private const val KEY_CLOCK_BG_COLOR = "clock_bg_color"
    private const val KEY_CLOCK_BG_OPACITY = "clock_bg_opacity"
    private const val KEY_PAN = "pan"
    private const val KEY_PAN_SPEED = "pan_speed"

    /** The tile and widget show this switch, so they follow it. */
    override fun setEnabled(context: Context, enabled: Boolean) {
        super.setEnabled(context, enabled)
        ToggleWidget.updateAll(context)
        ToggleTile.requestUpdate(context)
    }

    /** Fit the image beside the rear camera instead of letting the camera cover part of it. */
    fun avoidCamera(context: Context) = prefs(context).getBoolean(KEY_AVOID_CAMERA, false)

    fun setAvoidCamera(context: Context, avoid: Boolean) {
        prefs(context).edit().putBoolean(KEY_AVOID_CAMERA, avoid).apply()
    }

    /** The speed images pan at, or null while panning is off. */
    fun pan(context: Context): PanSpeed? = if (prefs(context).getBoolean(KEY_PAN, false)) panSpeed(context) else null

    fun setPan(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAN, on).apply()
    }

    /** The chosen speed, kept while panning is off. */
    fun panSpeed(context: Context): PanSpeed {
        val name = prefs(context).getString(KEY_PAN_SPEED, null)
        return PanSpeed.entries.firstOrNull { it.name == name } ?: PanSpeed.MEDIUM
    }

    fun setPanSpeed(context: Context, speed: PanSpeed) {
        prefs(context).edit().putString(KEY_PAN_SPEED, speed.name).apply()
    }

    /** How images are fitted: the gallery's scaling, except that panning always fills the screen. */
    fun scalingInUse(context: Context): Scaling = if (pan(context) != null) Scaling.FILL else Gallery.scaling(context)

    /** Show the time and date over the wallpaper. */
    fun showClock(context: Context) = prefs(context).getBoolean(KEY_CLOCK, false)

    fun setShowClock(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLOCK, show).apply()
    }

    fun clockStyle(context: Context): ClockStyle {
        val name = prefs(context).getString(KEY_CLOCK_STYLE, null)
        return ClockStyle.entries.firstOrNull { it.name == name } ?: ClockStyle.CLASSIC
    }

    fun setClockStyle(context: Context, style: ClockStyle) {
        prefs(context).edit().putString(KEY_CLOCK_STYLE, style.name).apply()
    }

    /** Everything about how the clock looks and where it sits. */
    fun clockSettings(context: Context): ClockSettings {
        val p = prefs(context)
        val style = clockStyle(context)
        return ClockSettings(
            style = style,
            // Until it's been moved, each style has its own place.
            x = p.getFloat(KEY_CLOCK_X, style.x),
            y = p.getFloat(KEY_CLOCK_Y, style.y),
            color = if (p.contains(KEY_CLOCK_COLOR)) p.getInt(KEY_CLOCK_COLOR, 0) else null,
            bgColor = if (p.contains(KEY_CLOCK_BG_COLOR)) p.getInt(KEY_CLOCK_BG_COLOR, 0) else null,
            bgOpacity = p.getInt(KEY_CLOCK_BG_OPACITY, 0),
        )
    }

    fun setClockPosition(context: Context, x: Float, y: Float) {
        prefs(context).edit().putFloat(KEY_CLOCK_X, x).putFloat(KEY_CLOCK_Y, y).apply()
    }

    /** The clock's text colour; null picks one that stands out from the image. */
    fun setClockColor(context: Context, color: Int?) = putColor(context, KEY_CLOCK_COLOR, color)

    /** The colour behind the clock; null picks one that sets off the text. */
    fun setClockBgColor(context: Context, color: Int?) = putColor(context, KEY_CLOCK_BG_COLOR, color)

    /** How solid the colour behind the clock is, 0 (none) to 100. */
    fun setClockBgOpacity(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_CLOCK_BG_OPACITY, percent).apply()
    }

    private fun putColor(context: Context, key: String, color: Int?) {
        prefs(context).edit().apply { if (color == null) remove(key) else putInt(key, color) }.apply()
    }
}
