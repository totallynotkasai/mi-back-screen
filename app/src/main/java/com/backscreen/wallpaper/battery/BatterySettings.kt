package com.backscreen.wallpaper.battery

import android.content.Context
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.FeatureSettings

/** How the charging animation looks. */
enum class ChargingStyle(val label: Int, val summary: Int) {
    /** A ring filling to the level over the dimmed wallpaper (1.x and 2.0's first builds). */
    RING(R.string.style_ring, R.string.style_ring_summary),

    /** A glow round the back screen's edge, as far as the level, then faint while it charges. */
    EDGE_GLOW(R.string.style_glow, R.string.style_glow_summary),

    /** A small bolt and the level, just below the clock. */
    MINIMAL(R.string.style_minimal, R.string.style_minimal_summary),
}

/** The Battery section's settings: a charging animation on the back screen. */
object BatterySettings : FeatureSettings("battery") {
    private const val KEY_LIGHT_UP = "light_up"
    private const val KEY_OVER_XIAOMI = "over_xiaomi"
    private const val KEY_STYLE = "style"
    private const val KEY_COLOR = "color"

    /**
     * Light up the back screen to play it, if it's dark when you plug in. On by default: a
     * dimmed back screen shows none of the animation, so without it you'd rarely see one.
     */
    fun lightUp(context: Context) = prefs(context).getBoolean(KEY_LIGHT_UP, true)

    fun setLightUp(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIGHT_UP, on).apply()
    }

    /** Play it over Xiaomi's back screen too, while the wallpaper is off. Off by default (a pop-over). */
    fun overXiaomi(context: Context) = prefs(context).getBoolean(KEY_OVER_XIAOMI, false)

    fun setOverXiaomi(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVER_XIAOMI, on).apply()
    }

    /** Ring by default, as before Phase 9. */
    fun style(context: Context): ChargingStyle {
        val name = prefs(context).getString(KEY_STYLE, null)
        return ChargingStyle.entries.firstOrNull { it.name == name } ?: ChargingStyle.RING
    }

    fun setStyle(context: Context, style: ChargingStyle) {
        prefs(context).edit().putString(KEY_STYLE, style.name).apply()
    }

    /** The ring's, glow's or bolt's colour; null for Auto, a colour from the wallpaper. */
    fun color(context: Context): Int? = prefs(context).let { if (it.contains(KEY_COLOR)) it.getInt(KEY_COLOR, 0) else null }

    fun setColor(context: Context, color: Int?) {
        prefs(context).edit().apply { if (color == null) remove(KEY_COLOR) else putInt(KEY_COLOR, color) }.apply()
    }
}
