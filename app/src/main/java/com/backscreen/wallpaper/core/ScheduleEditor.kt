package com.backscreen.wallpaper.core

import android.content.Context
import android.text.format.DateFormat
import com.backscreen.wallpaper.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** How schedules are written out, for the Wallpaper tab and the editor ([ScheduleDialog]). */
object ScheduleEditor {

    /** The week in the order the phone's region uses, e.g. Monday first in the UK. */
    fun orderedDays(): List<DayOfWeek> {
        val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        return (0L until 7L).map { first.plus(it) }
    }

    fun formatTime(context: Context, time: LocalTime): String =
        time.format(formatter(context, "Hm", "hm"))

    /** "23:00" if [time] is later today, otherwise "Tue 07:00". */
    fun formatWhen(context: Context, time: Long): String {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(time).atZone(zone)
        val today = Instant.now().atZone(zone).toLocalDate()
        return if (at.toLocalDate() == today) at.format(formatter(context, "Hm", "hm"))
        else at.format(formatter(context, "EEEHm", "EEEhm"))
    }

    private fun formatter(context: Context, skeleton24: String, skeleton12: String): DateTimeFormatter {
        val skeleton = if (DateFormat.is24HourFormat(context)) skeleton24 else skeleton12
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton))
    }

    fun formatDays(context: Context, days: Int): String = when (days) {
        Schedule.EVERY_DAY -> context.getString(R.string.every_day)
        Schedule.WEEKDAYS -> context.getString(R.string.weekdays)
        Schedule.WEEKEND -> context.getString(R.string.weekends)
        else -> orderedDays()
            .filter { days and (1 shl (it.value - 1)) != 0 }
            .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }
}
