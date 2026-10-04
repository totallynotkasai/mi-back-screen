package com.backscreen.wallpaper.mirror

import android.content.Context
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.FeatureSettings

/** How long the back screen stays lit after the last touch while an app is there. */
enum class KeepLit(val ms: Int?, val label: Int) {
    /** Xiaomi's own timeout, left alone (10 s on the 17 Pro Max). */
    AS_SET(null, R.string.keep_lit_as_set),
    SECONDS_30(30_000, R.string.keep_lit_30s),
    MINUTES_2(120_000, R.string.keep_lit_2min),
    MINUTES_10(600_000, R.string.keep_lit_10min),
}

/** How big apps draw on the back screen, against Xiaomi's own density (260 dpi). */
enum class AppSize(val scale: Float, val label: Int) {
    DEFAULT(1f, R.string.size_default),
    SMALLER(0.85f, R.string.size_smaller),
    SMALLEST(0.7f, R.string.size_smallest),
}

/** Which way up the back screen is while an app is there. It never turns by itself. */
enum class RearOrientation(val label: Int) {
    LANDSCAPE(R.string.orientation_landscape),

    /** Turned so the camera is at the top. */
    PORTRAIT(R.string.orientation_portrait),
}

/** The Mirror section's settings: Quick Switch, moving the app in use to the back screen. */
object MirrorSettings : FeatureSettings("mirror") {
    private const val KEY_KEEP_LIT = "keep_lit"
    private const val KEY_COVER_RETURN = "cover_return"
    private const val KEY_SIZE = "size"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_TILE_ADDED = "tile_added"
    private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"

    /** 2 minutes by default (decided before Phase 6). */
    fun keepLit(context: Context) =
        KeepLit.entries.firstOrNull { it.name == prefs(context).getString(KEY_KEEP_LIT, null) } ?: KeepLit.MINUTES_2

    fun setKeepLit(context: Context, value: KeepLit) {
        prefs(context).edit().putString(KEY_KEEP_LIT, value.name).apply()
    }

    /**
     * Covering the back for a moment brings the app back. Off by default: holding the phone to
     * watch the back screen covered the sensor now and then (Phase 6).
     */
    fun coverReturn(context: Context) = prefs(context).getBoolean(KEY_COVER_RETURN, false)

    fun setCoverReturn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_COVER_RETURN, on).apply()
    }

    fun size(context: Context) =
        AppSize.entries.firstOrNull { it.name == prefs(context).getString(KEY_SIZE, null) } ?: AppSize.DEFAULT

    fun setSize(context: Context, value: AppSize) {
        prefs(context).edit().putString(KEY_SIZE, value.name).apply()
    }

    fun orientation(context: Context) =
        RearOrientation.entries.firstOrNull { it.name == prefs(context).getString(KEY_ORIENTATION, null) }
            ?: RearOrientation.LANDSCAPE

    fun setOrientation(context: Context, value: RearOrientation) {
        prefs(context).edit().putString(KEY_ORIENTATION, value.name).apply()
    }

    /** Whether the Quick Switch tile is in Quick Settings, as the tile last told us. */
    fun tileAdded(context: Context) = prefs(context).getBoolean(KEY_TILE_ADDED, false)

    fun setTileAdded(context: Context, added: Boolean) {
        prefs(context).edit().putBoolean(KEY_TILE_ADDED, added).apply()
    }

    /** Notification permission is asked for once, when Mirror or Camera is first switched on. */
    fun askedNotifications(context: Context) = prefs(context).getBoolean(KEY_ASKED_NOTIFICATIONS, false)

    fun setAskedNotifications(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
    }
}
