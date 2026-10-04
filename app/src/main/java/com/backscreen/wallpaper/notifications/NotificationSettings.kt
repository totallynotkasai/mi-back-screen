package com.backscreen.wallpaper.notifications

import android.content.Context
import com.backscreen.wallpaper.core.FeatureSettings

/** The Notifications section's settings: new notifications shown on the back screen. */
object NotificationSettings : FeatureSettings("notifications") {
    private const val KEY_STYLE = "style"
    private const val KEY_SWIPE_DOWN = "swipe_down"
    private const val KEY_LIGHT_UP = "light_up"

    /** How much shows. Normal by default: the app and who it's from, not the message. */
    fun style(context: Context) =
        NotificationStyle.entries.firstOrNull { it.name == prefs(context).getString(KEY_STYLE, null) }
            ?: NotificationStyle.NORMAL

    fun setStyle(context: Context, style: NotificationStyle) {
        prefs(context).edit().putString(KEY_STYLE, style.name).apply()
    }

    /** Swipe down on the back screen for the ones you haven't cleared. On by default. */
    fun swipeDown(context: Context) = prefs(context).getBoolean(KEY_SWIPE_DOWN, true)

    fun setSwipeDown(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SWIPE_DOWN, on).apply()
    }

    /**
     * Light up the back screen for a new one, if it's dark. On by default, as for charging; each
     * app lights it at most once a minute ([AppWakeLimit]).
     */
    fun lightUp(context: Context) = prefs(context).getBoolean(KEY_LIGHT_UP, true)

    fun setLightUp(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIGHT_UP, on).apply()
    }
}
