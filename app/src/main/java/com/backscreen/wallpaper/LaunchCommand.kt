package com.backscreen.wallpaper

import android.content.Intent
import com.backscreen.wallpaper.core.KeeperService

/**
 * What a tile or the keeper's notification asks of the app when it opens it: the tiles when
 * they aren't allowed to start the service themselves, the notification when the wallpaper's
 * folder needs choosing again.
 */
enum class LaunchCommand {
    TOGGLE,
    QUICK_SWITCH,
    SHOW_WALLPAPER;

    companion object {
        /**
         * The command in an intent with [action] and [flags]. None when Recents reopens the app:
         * a tile that opened it with no task of its own made its intent the task's first, and
         * Recents replays that.
         */
        fun of(action: String?, flags: Int): LaunchCommand? {
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
