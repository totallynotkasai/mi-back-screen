package com.backscreen.wallpaper.wallpaper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.rear.RearHostActivity

/**
 * Wakes the phone at each minute boundary while the clock is on the back screen, the way
 * always-on displays do, so the time there doesn't lag while the phone sleeps. Only set while
 * the wallpaper and its clock are both on, the wallpaper is on the back screen with no other
 * app over it, and that screen isn't off (Xiaomi turns it off when the back is covered, and
 * after a while in some conditions); when it wakes, the wallpaper catches the clock up at once
 * and sets the alarm again.
 *
 * Each wake updates the clock and gets the new frame onto the dimmed panel
 * (see [RearHostActivity.onMinuteAlarm]); a short wake lock lets that finish before the
 * CPU sleeps again.
 */
object ClockAlarm {
    // Until the frame push takes over: its draw wake lock keeps the CPU up while the frame is sent.
    private const val WAKE_LOCK_MS = 500L
    private const val LATE_MS = 2000L

    @Volatile private var due = 0L

    // Since the process started: how many fired, how many were late and the worst. Shown in
    // the app's details, to spot HyperOS holding them back.
    @Volatile private var fired = 0
    @Volatile private var late = 0
    @Volatile private var worstMs = 0L

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, ClockAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Sets the next alarm, or cancels it if the clock isn't on the back screen. */
    fun update(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Not while another app has the back screen: the clock is underneath it.
        val wanted = RearState.snapshot(context).guardsWallpaper && WallpaperSettings.showClock(context) &&
            RearHostActivity.isClockVisible()
        if (!wanted) {
            if (due != 0L) alarms.cancel(intent(context))
            due = 0L
            return
        }
        val next = ClockTicker.nextMinute(System.currentTimeMillis())
        if (next == due) return
        due = next
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent(context))
        } else {
            // Without exact alarm access it may come a few minutes late; the app warns about it.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent(context))
        }
    }

    fun onAlarm(context: Context) {
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BackScreen:clock")
            .acquire(WAKE_LOCK_MS)
        val lateMs = if (due == 0L) 0L else System.currentTimeMillis() - due
        due = 0L
        fired++
        if (lateMs > LATE_MS) {
            late++
            BackScreen.log("Clock update came ${lateMs / 1000} s late")
        }
        if (lateMs > worstMs) worstMs = lateMs
        RearHostActivity.onMinuteAlarm()
        update(context)
    }

    /** A line for the app's details, or null before any alarm. */
    fun summary(): String? =
        if (fired == 0) null
        else "Clock updates: $fired, $late late (worst ${"%.1f".format(worstMs / 1000f)} s)"
}

class ClockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ClockAlarm.onAlarm(context)
}
