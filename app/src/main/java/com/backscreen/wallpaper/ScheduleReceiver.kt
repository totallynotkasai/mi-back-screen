package com.backscreen.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.core.Schedules
import com.backscreen.wallpaper.wallpaper.ClockAlarm

/**
 * Receives the schedule alarm, and re-sets it whenever the system may have dropped or moved
 * it: after a restart, an app update, a clock or time zone change, or exact alarms being allowed.
 * The clock's alarm too: after the time is set back, the minute it was set for is far off.
 *
 * After a restart or an update, it also starts KeeperService again if a section needs it.
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Schedules.ACTION_ALARM) {
            Schedules.onAlarm(context)
            return
        }
        Schedules.update(context)
        ClockAlarm.update(context)
        when (intent.action) {
            // A lend from before a restart is forgotten by RearState itself. This also arrives
            // when the app comes back from a force stop (Android 15 and later), with the lent
            // app still on the back screen, so it mustn't clear the lend.
            Intent.ACTION_BOOT_COMPLETED -> {}
            // The widget's button names the service; make sure it names this version's.
            Intent.ACTION_MY_PACKAGE_REPLACED -> ToggleWidget.updateAll(context)
            else -> return
        }
        // Restarting the phone or updating the app stops it; carry on where it was. After a
        // restart Shizuku isn't running yet, and the wallpaper waits for it.
        if (RearState.keeperNeeded(context)) {
            try {
                KeeperService.start(context, KeeperService.ACTION_RESUME)
            } catch (e: IllegalStateException) {
                BackScreen.log("Couldn't carry on after ${if (intent.action == Intent.ACTION_BOOT_COMPLETED) "the restart" else "the update"}: ${e.message}")
            }
        }
    }
}
