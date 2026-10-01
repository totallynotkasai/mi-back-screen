package com.backscreen.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the schedule alarm, and re-sets it whenever the system may have dropped or moved
 * it: after a restart, an app update, a clock or time zone change, or exact alarms being allowed.
 * The clock's alarm too: after the time is set back, the minute it was set for is far off.
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Schedules.ACTION_ALARM) {
            Schedules.onAlarm(context)
            return
        }
        Schedules.update(context)
        ClockAlarm.update(context)
        // Updating the app stops it; put the wallpaper back if it was on.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && BackScreen.isEnabled(context)) {
            try {
                KeeperService.start(context, KeeperService.ACTION_RESUME)
            } catch (e: IllegalStateException) {
                BackScreen.log("Couldn't resume after the update: ${e.message}")
            }
        }
    }
}
