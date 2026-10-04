package com.backscreen.wallpaper.ui

import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.battery.BatterySettings
import com.backscreen.wallpaper.camera.CameraSettings
import com.backscreen.wallpaper.core.FeatureSettings
import com.backscreen.wallpaper.mirror.MirrorSettings
import com.backscreen.wallpaper.notifications.NotificationSettings
import com.backscreen.wallpaper.wallpaper.WallpaperSettings

/**
 * The app's five sections, one tab each: its place in the bottom bar, its main switch and its
 * settings. [planned] describes a section that isn't built yet.
 */
enum class Feature(
    val navId: Int,
    val switchTitle: Int,
    val icon: Int,
    val settings: FeatureSettings,
    val planned: Int? = null,
) {
    WALLPAPER(R.id.nav_wallpaper, R.string.show_on_back_screen, R.drawable.ic_image, WallpaperSettings),
    NOTIFICATIONS(R.id.nav_notifications, R.string.notifications_switch, R.drawable.ic_notifications, NotificationSettings),
    BATTERY(R.id.nav_battery, R.string.battery_switch, R.drawable.ic_battery_charging, BatterySettings),
    MIRROR(R.id.nav_mirror, R.string.mirror_switch, R.drawable.ic_swap, MirrorSettings, R.string.mirror_planned),
    CAMERA(R.id.nav_camera, R.string.camera_switch, R.drawable.ic_camera, CameraSettings, R.string.camera_planned);

    companion object {
        fun forNavId(id: Int) = entries.firstOrNull { it.navId == id }
    }
}

/** A tab that keeps itself up to date while it's showing; the app calls this every second. */
interface Refreshable {
    fun refresh()
}
