package com.backscreen.wallpaper.battery

import android.content.Context
import com.backscreen.wallpaper.core.FeatureSettings

/** The Battery section's settings: a charging animation on the back screen. */
object BatterySettings : FeatureSettings("battery") {
    private const val KEY_LIGHT_UP = "light_up"
    private const val KEY_OVER_XIAOMI = "over_xiaomi"

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
}
