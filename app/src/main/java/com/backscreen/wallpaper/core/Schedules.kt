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
import java.time.format.DateTimeFormatter
import java.util.Locale

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
 * Stores the schedules and turns the wallpaper on and off with them.
 *
 * The schedules switch the wallpaper only when their combined state flips: a start or end that
 * really changes off to on or back. One schedule ending inside another changes nothing, and an
 * every-day 24-hour schedule never flips. Only the next flip is set as an alarm, and a switch by
 * hand lasts until then.
 *
 * A flip missed while the phone was off, the clock or time zone was changed or the app was
 * stopped is caught up ([catchUp]): the latest one since the switch was last decided is applied,
 * unless you switched it by hand after that flip. When the switch was last decided is saved, with
 * the time zone: by a flip, by a switch by hand, and by saving the schedules (which never switches
 * by itself).
 *
 * The rules ([flips], [nextChange], [missed], [inZone]) take time zones, so they're unit tested.
 */
object Schedules {
    private const val PREFS = "schedules"
    private const val KEY_LIST = "list"
    private const val KEY_DECIDED_AT = "decided_at"
    private const val KEY_DECIDED_ZONE = "decided_zone"
    const val ACTION_ALARM = "com.backscreen.wallpaper.SCHEDULE_ALARM"

    // Without exact alarm access the system may deliver the alarm up to this late.
    private const val INEXACT_WINDOW_MS = 10 * 60 * 1000L

    // How far ahead the next flip is looked for, and back for a missed one. Schedules repeat
    // weekly, so any flip there is comes round within a week; the extra day covers clock changes.
    private const val LOOK_MS = 8 * 24 * 60 * 60 * 1000L

    // A flip applied later than this is logged as caught up rather than on time.
    private const val LATE_MS = 60 * 1000L

    private val LOG_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)

    /** At [time] the schedules switch the wallpaper [on], or off. */
    data class Flip(val time: Long, val on: Boolean)

    private class Window(val start: Long, val end: Long) {
        operator fun contains(time: Long) = time >= start && time < end
    }

    fun load(context: Context): List<Schedule> {
        val json = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
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

    /** Saves the schedules and sets the next flip. Doesn't switch the wallpaper: the next flip does. */
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
        prefs(context).edit()
            .putString(KEY_LIST, array.toString())
            .putLong(KEY_DECIDED_AT, System.currentTimeMillis())
            .putString(KEY_DECIDED_ZONE, ZoneId.systemDefault().id)
            .apply()
        update(context)
    }

    /** Whether the schedules say the wallpaper should be on at [time]. */
    fun activeAt(schedules: List<Schedule>, time: Long, zone: ZoneId = ZoneId.systemDefault()) =
        windows(schedules, time, time, zone).any { time in it }

    /** The enabled schedules' flips after [from], within the next week, soonest first. */
    fun flips(schedules: List<Schedule>, from: Long, zone: ZoneId = ZoneId.systemDefault()): List<Flip> =
        flipsIn(schedules.filter { it.enabled }, from, from + LOOK_MS, zone)

    /**
     * The next time the schedules would switch the wallpaper away from [on], and whether that
     * switch is to on: the first flip to the other state. Null if there's none within a week.
     */
    fun nextChange(
        schedules: List<Schedule>, from: Long, on: Boolean, zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<Long, Boolean>? = flips(schedules, from, zone).firstOrNull { it.on != on }?.let { it.time to it.on }

    /**
     * The flip to apply at [now], if one was missed: the latest flip after [decidedAt] (when the
     * switch was last decided) up to [now], if it says other than the switch ([on]). A [decidedAt]
     * that's missing, or after [now] because the clock was set back, counts as [now], so nothing
     * is replayed.
     */
    fun missed(
        schedules: List<Schedule>, decidedAt: Long?, now: Long, on: Boolean, zone: ZoneId = ZoneId.systemDefault(),
    ): Flip? {
        val after = if (decidedAt == null || decidedAt > now) now else maxOf(decidedAt, now - LOOK_MS)
        return flipsIn(schedules.filter { it.enabled }, after, now, zone).lastOrNull()?.takeIf { it.on != on }
    }

    /**
     * [decidedAt], decided while the phone was in [decidedZone], for [missed] in [zone]. After a
     * time zone change it's the same clock time in the new zone: schedules go by the clock, so
     * decided at 20:30 in London and now 00:00 in Dubai, 23:00's off has passed since.
     */
    fun inZone(decidedAt: Long, decidedZone: ZoneId, zone: ZoneId): Long =
        if (decidedZone == zone) {
            decidedAt
        } else {
            Instant.ofEpochMilli(decidedAt).atZone(decidedZone).toLocalDateTime().atZone(zone).toInstant().toEpochMilli()
        }

    /** Sets an alarm for the next flip, or cancels it if there's none. */
    fun update(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ScheduleReceiver::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val next = flips(load(context), System.currentTimeMillis()).firstOrNull()?.time
        when {
            next == null -> alarms.cancel(intent)
            // Exact alarms are also what allow the service to be started from the background.
            alarms.canScheduleExactAlarms() ->
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
            else -> alarms.setWindow(AlarmManager.RTC_WAKEUP, next, INEXACT_WINDOW_MS, intent)
        }
    }

    /**
     * Applies a flip that's due ([missed]): the alarm's, or one missed while the phone was off,
     * the clock changed or the app was stopped. Then sets the next alarm. From the alarm, a
     * restart, a time or time zone change, an app update, and the app's process starting.
     */
    fun catchUp(context: Context) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val p = prefs(context)
        val decidedZone = p.getString(KEY_DECIDED_ZONE, null)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
        val decidedAt = if (p.contains(KEY_DECIDED_AT)) inZone(p.getLong(KEY_DECIDED_AT, now), decidedZone, zone) else null
        val flip = missed(load(context), decidedAt, now, WallpaperSettings.isEnabled(context), zone)
        // A flip Android wouldn't let us apply from here stays missed, for the next catch-up.
        if (flip == null || switchTo(context, flip, now)) decided(context, now)
        update(context)
    }

    /**
     * The switch was decided at [time]: by a flip, or by you (see [WallpaperSettings.setEnabled]).
     * With the time zone, so a change of zone can be caught up ([inZone]).
     */
    fun decided(context: Context, time: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putLong(KEY_DECIDED_AT, time)
            .putString(KEY_DECIDED_ZONE, ZoneId.systemDefault().id)
            .apply()
    }

    /** Switches the wallpaper as [flip] says. False if Android wouldn't allow it from here. */
    private fun switchTo(context: Context, flip: Flip, now: Long): Boolean {
        val what = if (flip.on) "turning on" else "turning off"
        if (now - flip.time > LATE_MS) {
            val at = Instant.ofEpochMilli(flip.time).atZone(ZoneId.systemDefault()).format(LOG_TIME)
            BackScreen.log("Schedule: $what, catching up with the change at $at")
        } else {
            BackScreen.log("Schedule: $what")
        }
        return try {
            KeeperService.start(context, if (flip.on) KeeperService.ACTION_SCHEDULED_ON else KeeperService.ACTION_RESTORE)
            true
        } catch (e: IllegalStateException) {
            // Android blocks this from the background unless the alarm was exact.
            BackScreen.log("Schedule couldn't start: ${e.message}")
            false
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The flips in ([after], [upTo]], soonest first. */
    private fun flipsIn(active: List<Schedule>, after: Long, upTo: Long, zone: ZoneId): List<Flip> {
        if (active.isEmpty() || upTo <= after) return emptyList()
        val windows = windows(active, after, upTo, zone)
        return windows.flatMap { listOf(it.start, it.end) }
            .filter { it > after && it <= upTo }
            .distinct()
            .sorted()
            .mapNotNull { time ->
                val on = windows.any { time in it }
                if (on != windows.any { time - 1 in it }) Flip(time, on) else null
            }
    }

    /**
     * Each schedule's windows that may hold a time from [from] to [to]: those starting from the
     * day before [from]'s (one running past midnight) to [to]'s day.
     */
    private fun windows(schedules: List<Schedule>, from: Long, to: Long, zone: ZoneId): List<Window> {
        var date = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(1)
        val last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        val windows = mutableListOf<Window>()
        while (!date.isAfter(last)) {
            for (s in schedules) {
                if (!s.runsOn(date.dayOfWeek)) continue
                val endDate = if (s.end <= s.start) date.plusDays(1) else date
                windows += Window(
                    date.atTime(s.start).atZone(zone).toInstant().toEpochMilli(),
                    endDate.atTime(s.end).atZone(zone).toInstant().toEpochMilli(),
                )
            }
            date = date.plusDays(1)
        }
        return windows
    }
}
