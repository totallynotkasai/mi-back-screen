package com.backscreen.wallpaper

import android.content.Context
import android.content.Intent
import com.backscreen.wallpaper.core.KeeperService

/**
 * What a tile or the keeper's notification asks of the app when it opens it: the tiles when
 * they aren't allowed to start the service themselves, the notification when the wallpaper's
 * folder needs choosing again.
 *
 * They open it through [ALIAS], a second name for MainActivity that isn't exported, so only this
 * app can use it. MainActivity itself is exported, as the launcher's way in, so any app could
 * open it with one of these actions; that way they're ignored.
 */
enum class LaunchCommand {
    TOGGLE,
    QUICK_SWITCH,
    SHOW_WALLPAPER;

    companion object {
        /** MainActivity's private name (an activity-alias in the manifest). */
        const val ALIAS = "com.backscreen.wallpaper.CommandLaunch"

        /** Opens the app with [action], through [ALIAS]. */
        fun intent(context: Context, action: String): Intent = Intent().setClassName(context, ALIAS).setAction(action)

        /**
         * The command in an intent with [action] and [flags], for the activity named [className].
         * None unless it came through [ALIAS], and none when Recents reopens the app: a tile that
         * opened it with no task of its own made its intent the task's first, and Recents
         * replays that.
         */
        fun of(action: String?, flags: Int, className: String?): LaunchCommand? {
            if (className != ALIAS) return null
            if (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            return when (action) {
                KeeperService.ACTION_TOGGLE -> TOGGLE
                KeeperService.ACTION_QUICK_SWITCH -> QUICK_SWITCH
                KeeperService.ACTION_SHOW_WALLPAPER -> SHOW_WALLPAPER
                else -> null
            }
        }
    }
}
