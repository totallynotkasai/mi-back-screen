package com.backscreen.wallpaper.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.backscreen.wallpaper.ScheduleReceiver
import com.backscreen.wallpaper.wallpaper.WallpaperSettings
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A time window when the wallpaper should be on, e.g. 07:00 to 23:00 on weekdays.
 * If [end] isn't after [start] the window runs past midnight into the next day.
 */
data class Schedule(
    val id: Long,
    val start: LocalTime,
    val end: LocalTime,
    /** One bit per day, Monday first (bit 0) to Sunday (bit 6); the day the window starts. */
    val days: Int,
    val enabled: Boolean = true,
) {
    fun runsOn(day: DayOfWeek) = days and (1 shl (day.value - 1)) != 0

    companion object {
        const val EVERY_DAY = 0x7F
        const val WEEKDAYS = 0x1F
        const val WEEKEND = 0x60
    }
}

/**
 * Stores the schedules and turns the wallpaper on and off with them. Only the next change is
 * ever set as an alarm; when it fires, the wallpaper is switched to match the schedules and the
 * one after is set. Switching by hand in between is kept until that next change.
 */
object Schedules {
    private const val PREFS = "schedules"
    private const val KEY_LIST = "list"
    const val ACTION_ALARM = "com.backscreen.wallpaper.SCHEDULE_ALARM"

    // Without exact alarm access the system may deliver the alarm up to this late.
    private const val INEXACT_WINDOW_MS = 10 * 60 * 1000L

    private class Window(val start: Long, val end: Long) {
        operator fun contains(time: Long) = time >= start && time < end
    }

    fun load(context: Context): List<Schedule> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Schedule(
                    id = o.getLong("id"),
                    start = LocalTime.ofSecondOfDay(o.getInt("start") * 60L),
                    end = LocalTime.ofSecondOfDay(o.getInt("end") * 60L),
                    days = o.getInt("days"),
                    enabled = o.getBoolean("enabled"),
                )
            }
        } catch (e: Exception) {
            BackScreen.log("Couldn't read schedules: ${e.message}")
            emptyList()
        }
    }

    fun save(context: Context, schedules: List<Schedule>) {
        val array = JSONArray()
        for (s in schedules) {
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("start", s.start.toSecondOfDay() / 60)
                    .put("end", s.end.toSecondOfDay() / 60)
                    .put("days", s.days)
                    .put("enabled", s.enabled)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LIST, array.toString()).apply()
        update(context)
    }

    /** Whether the schedules say the wallpaper should be on at [time]. */
    fun activeAt(schedules: List<Schedule>, time: Long) = windows(schedules, time).any { time in it }

    /**
     * The next time the schedules would switch the wallpaper away from [on], and whether that
     * switch is to on. Null if no enabled schedule changes it within the next week.
     */
    fun nextChange(schedules: List<Schedule>, from: Long, on: Boolean): Pair<Long, Boolean>? {
        val active = schedules.filter { it.enabled }
        val time = boundaries(active, from).firstOrNull { activeAt(active, it) != on } ?: return null
        return time to !on
    }

    /** Sets an alarm for the next time any enabled schedule starts or ends. */
    fun update(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ScheduleReceiver::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val next = boundaries(load(context).filter { it.enabled }, System.currentTimeMillis()).firstOrNull()
        when {
            next == null -> alarms.cancel(intent)
            // Exact alarms are also what allow the service to be started from the background.
            alarms.canScheduleExactAlarms() ->
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
            else -> alarms.setWindow(AlarmManager.RTC_WAKEUP, next, INEXACT_WINDOW_MS, intent)
        }
    }

    /** The alarm fired: switch the wallpaper to match the schedules, then set the next alarm. */
    fun onAlarm(context: Context) {
        val on = activeAt(load(context).filter { it.enabled }, System.currentTimeMillis())
        if (on != WallpaperSettings.isEnabled(context)) {
            BackScreen.log(if (on) "Schedule: turning on" else "Schedule: turning off")
            try {
                KeeperService.start(context, if (on) KeeperService.ACTION_SCHEDULED_ON else KeeperService.ACTION_RESTORE)
            } catch (e: IllegalStateException) {
                // Android blocks this from the background unless the alarm was exact.
                BackScreen.log("Schedule couldn't start: ${e.message}")
            }
        }
        update(context)
    }

    /** Every start and end after [from], soonest first. */
    private fun boundaries(schedules: List<Schedule>, from: Long) =
        windows(schedules, from).flatMap { listOf(it.start, it.end) }.filter { it > from }.distinct().sorted()

    /** Each schedule's windows from the day before [around] to a week after it. */
    private fun windows(schedules: List<Schedule>, around: Long): List<Window> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(around).atZone(zone).toLocalDate()
        val windows = mutableListOf<Window>()
        for (s in schedules) {
            for (offset in -1L..7L) {
                val date = today.plusDays(offset)
                if (!s.runsOn(date.dayOfWeek)) continue
                val endDate = if (s.end <= s.start) date.plusDays(1) else date
                windows += Window(
                    date.atTime(s.start).atZone(zone).toInstant().toEpochMilli(),
                    endDate.atTime(s.end).atZone(zone).toInstant().toEpochMilli(),
                )
            }
        }
        return windows
    }
}
