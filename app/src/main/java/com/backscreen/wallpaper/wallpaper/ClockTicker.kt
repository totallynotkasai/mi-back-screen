package com.backscreen.wallpaper.wallpaper

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.SystemClock
import android.provider.Settings
import com.backscreen.wallpaper.core.BackScreen
import java.util.TimeZone

/**
 * Keeps the clocks on time. They always show the real time, read afresh on every update, so a
 * tick that runs late still shows the right minute instead of counting on from a stale one.
 *
 * While any clock is listening it ticks at each minute boundary, and also updates on every sign
 * that the phone may have slept or the time changed: the system's minute tick, the time, time
 * zone, date or language changing, the 12/24-hour setting, and the screen turning on.
 *
 * The tick counts uptime, so it stalls while the CPU sleeps. That's fine in the app, but the
 * back screen stays visible while the phone sleeps, so there [ClockAlarm] wakes the CPU at each
 * minute instead.
 */
// Only ever holds the application context.
@SuppressLint("StaticFieldLeak")
object ClockTicker {
    private const val MINUTE_MS = 60_000L

    /**
     * The first minute boundary after [now]. Every time zone in use today is a whole number of
     * minutes from UTC, so a UTC minute boundary is a local one too.
     */
    fun nextMinute(now: Long): Long = Math.floorDiv(now, MINUTE_MS) * MINUTE_MS + MINUTE_MS

    fun interface Listener {
        fun onTimeChanged()
    }

    // Only touched on the main thread.
    private val listeners = mutableSetOf<Listener>()
    private var registeredWith: Context? = null

    private val tick: Runnable by lazy { Runnable { update() } }

    private val receiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Make sure the next read uses the new zone, as TextClock does.
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) TimeZone.setDefault(null)
                update()
            }
        }
    }

    private val formatObserver by lazy {
        object : ContentObserver(BackScreen.mainHandler) {
            override fun onChange(selfChange: Boolean) = update()
        }
    }

    /** Starts telling [listener] about the time changing. Main thread only. */
    fun add(context: Context, listener: Listener) {
        if (!listeners.add(listener) || registeredWith != null) return
        val app = context.applicationContext
        registeredWith = app
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        app.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.TIME_12_24), false, formatObserver
        )
        schedule()
    }

    fun remove(listener: Listener) {
        if (!listeners.remove(listener) || listeners.isNotEmpty()) return
        val app = registeredWith ?: return
        registeredWith = null
        app.unregisterReceiver(receiver)
        app.contentResolver.unregisterContentObserver(formatObserver)
        BackScreen.mainHandler.removeCallbacks(tick)
    }

    private fun update() {
        for (listener in listeners.toList()) listener.onTimeChanged()
        schedule()
    }

    private fun schedule() {
        BackScreen.mainHandler.removeCallbacks(tick)
        if (listeners.isEmpty()) return
        val now = System.currentTimeMillis()
        BackScreen.mainHandler.postAtTime(tick, SystemClock.uptimeMillis() + nextMinute(now) - now)
    }
}
