package com.backscreen.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the schedule alarm, and re-sets it whenever the system may have dropped or moved
 * it: after a restart, an app update, a clock or time zone change, or exact alarms being allowed.
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Schedules.ACTION_ALARM) Schedules.onAlarm(context) else Schedules.update(context)
    }
}
