package com.backscreen.wallpaper

import android.content.Context
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** The dialog for adding or editing a schedule, and how schedules are written out. */
object ScheduleEditor {

    /** Shows the editor. [onSave] gets the new schedule, or null if it was deleted. */
    fun show(activity: AppCompatActivity, schedule: Schedule?, onSave: (Schedule?) -> Unit) {
        var start = schedule?.start ?: LocalTime.of(7, 0)
        var end = schedule?.end ?: LocalTime.of(23, 0)

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_schedule, null)
        val startButton = view.findViewById<Button>(R.id.startButton)
        val endButton = view.findViewById<Button>(R.id.endButton)
        val overnight = view.findViewById<TextView>(R.id.overnightNote)
        val chips = view.findViewById<ChipGroup>(R.id.dayChips)

        fun showTimes() {
            startButton.text = formatTime(activity, start)
            endButton.text = formatTime(activity, end)
            overnight.visibility = if (end <= start) View.VISIBLE else View.GONE
        }
        showTimes()

        val days = orderedDays()
        for (day in days) {
            val chip = LayoutInflater.from(activity).inflate(R.layout.chip_day, chips, false) as Chip
            chip.text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            chip.contentDescription = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
            chip.isChecked = (schedule?.days ?: Schedule.EVERY_DAY) and (1 shl (day.value - 1)) != 0
            chips.addView(chip)
        }
        fun selectedDays() = days.withIndex()
            .filter { (i, _) -> (chips.getChildAt(i) as Chip).isChecked }
            .fold(0) { bits, (_, day) -> bits or (1 shl (day.value - 1)) }

        startButton.setOnClickListener {
            pickTime(activity, R.string.schedule_on_at, start) { start = it; showTimes() }
        }
        endButton.setOnClickListener {
            pickTime(activity, R.string.schedule_off_at, end) { end = it; showTimes() }
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(if (schedule == null) R.string.add_schedule else R.string.edit_schedule)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                onSave(
                    Schedule(
                        id = schedule?.id ?: System.currentTimeMillis(),
                        start = start,
                        end = end,
                        days = selectedDays(),
                        enabled = schedule?.enabled ?: true,
                    )
                )
            }
        if (schedule != null) builder.setNeutralButton(R.string.delete) { _, _ -> onSave(null) }
        val dialog = builder.show()

        // A schedule needs at least one day.
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        chips.setOnCheckedStateChangeListener { _, _ -> save.isEnabled = selectedDays() != 0 }
    }

    private fun pickTime(activity: AppCompatActivity, title: Int, time: LocalTime, onPicked: (LocalTime) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(activity)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(time.hour)
            .setMinute(time.minute)
            .setTitleText(title)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(LocalTime.of(picker.hour, picker.minute)) }
        picker.show(activity.supportFragmentManager, "time")
    }

    /** The week in the order the phone's region uses, e.g. Monday first in the UK. */
    private fun orderedDays(): List<DayOfWeek> {
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
