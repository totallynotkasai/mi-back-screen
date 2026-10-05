package com.backscreen.wallpaper.ui

import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.battery.BatterySettings
import com.backscreen.wallpaper.camera.CameraSettings
import com.backscreen.wallpaper.core.FeatureSettings
import com.backscreen.wallpaper.mirror.MirrorSettings
import com.backscreen.wallpaper.notifications.NotificationSettings
import com.backscreen.wallpaper.wallpaper.WallpaperSettings

/** One part of a section's help: a heading and what it says. */
data class HelpSection(val title: Int, val text: Int)

/**
 * The app's five sections, one tab each: its place in the bottom bar, its name, its main switch,
 * its settings, and its help, behind the ? in the top bar.
 */
enum class Feature(
    val navId: Int,
    val label: Int,
    val switchTitle: Int,
    val icon: Int,
    val settings: FeatureSettings,
    val help: List<HelpSection>,
) {
    WALLPAPER(
        R.id.nav_wallpaper, R.string.tab_wallpaper, R.string.show_on_back_screen, R.drawable.ic_image, WallpaperSettings,
        listOf(
            HelpSection(R.string.help_panning_title, R.string.help_panning),
            HelpSection(R.string.help_display_title, R.string.help_display),
            HelpSection(R.string.help_dimmed_title, R.string.help_dimmed),
        ),
    ),
    NOTIFICATIONS(
        R.id.nav_notifications, R.string.tab_notifications, R.string.notifications_switch, R.drawable.ic_notifications,
        NotificationSettings,
        listOf(
            HelpSection(R.string.help_notifications_off_title, R.string.help_notifications_off),
            HelpSection(R.string.help_notifications_locked_title, R.string.help_notifications_locked),
        ),
    ),
    BATTERY(
        R.id.nav_battery, R.string.tab_battery, R.string.battery_switch, R.drawable.ic_battery_charging, BatterySettings,
        listOf(
            HelpSection(R.string.help_battery_when_title, R.string.help_battery_when),
            HelpSection(R.string.help_battery_glow_title, R.string.help_battery_glow),
            HelpSection(R.string.help_battery_xiaomi_title, R.string.help_battery_xiaomi),
        ),
    ),
    MIRROR(
        R.id.nav_mirror, R.string.tab_mirror, R.string.mirror_switch, R.drawable.ic_swap, MirrorSettings,
        listOf(HelpSection(R.string.good_to_know, R.string.mirror_limits)),
    ),
    CAMERA(
        R.id.nav_camera, R.string.tab_camera, R.string.camera_switch, R.drawable.ic_camera, CameraSettings,
        listOf(
            HelpSection(R.string.camera_close_title, R.string.camera_close),
            HelpSection(R.string.good_to_know, R.string.camera_limits),
        ),
    );

    companion object {
        fun forNavId(id: Int) = entries.firstOrNull { it.navId == id }
    }
}

/** A tab that keeps itself up to date while it's showing; the app calls this every second. */
interface Refreshable {
    fun refresh()
}
