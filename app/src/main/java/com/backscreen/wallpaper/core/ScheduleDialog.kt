package com.backscreen.wallpaper.core

import android.app.Dialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.FragmentManager
import com.backscreen.wallpaper.R
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Adding or editing a schedule. As a fragment it keeps its times and days through turning the
 * phone, and so do the time pickers it opens, which still set the time afterwards. What you
 * save or delete comes back as a fragment result ([RESULT]) to whoever showed it.
 */
class ScheduleDialog : AppCompatDialogFragment() {

    // The schedule being edited, or null for a new one.
    private var original: Schedule? = null
    private lateinit var start: LocalTime
    private lateinit var end: LocalTime
    private var days = Schedule.EVERY_DAY

    private var startButton: Button? = null
    private var endButton: Button? = null
    private var overnight: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        original = if (args.containsKey(ARG_ID)) read(args) else null
        val now = savedInstanceState?.let(::read) ?: original
        start = now?.start ?: LocalTime.of(7, 0)
        end = now?.end ?: LocalTime.of(23, 0)
        days = now?.days ?: Schedule.EVERY_DAY
        // A picker that was open before the phone turned is back, without what it was to set.
        for (tag in listOf(TAG_START, TAG_END)) {
            (childFragmentManager.findFragmentByTag(tag) as? MaterialTimePicker)?.let { listen(it, tag) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_schedule, null)
        startButton = view.findViewById<Button>(R.id.startButton).apply { setOnClickListener { pickTime(TAG_START) } }
        endButton = view.findViewById<Button>(R.id.endButton).apply { setOnClickListener { pickTime(TAG_END) } }
        overnight = view.findViewById(R.id.overnightNote)
        showTimes()

        val chips = view.findViewById<ChipGroup>(R.id.dayChips)
        for (day in ScheduleEditor.orderedDays()) {
            val bit = 1 shl (day.value - 1)
            val chip = LayoutInflater.from(context).inflate(R.layout.chip_day, chips, false) as Chip
            chip.text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            chip.contentDescription = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
            // The days are kept here: restored by id, a chip could take another's state.
            chip.isSaveEnabled = false
            chip.isChecked = days and bit != 0
            chip.setOnCheckedChangeListener { _, checked ->
                days = if (checked) days or bit else days and bit.inv()
                showSaveEnabled()
            }
            chips.addView(chip)
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(if (original == null) R.string.add_schedule else R.string.edit_schedule)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> saved() }
        if (original != null) builder.setNeutralButton(R.string.delete) { _, _ -> deleted() }
        return builder.create()
    }

    override fun onStart() {
        super.onStart()
        showSaveEnabled()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        write(outState, Schedule(0, start, end, days))
    }

    override fun onDestroyView() {
        startButton = null
        endButton = null
        overnight = null
        super.onDestroyView()
    }

    private fun showTimes() {
        val context = context ?: return
        startButton?.text = ScheduleEditor.formatTime(context, start)
        endButton?.text = ScheduleEditor.formatTime(context, end)
        // TalkBack says what each time is for: "On at 07:00, button". The labels above aren't read.
        startButton?.contentDescription = getString(R.string.schedule_on_at_time, startButton?.text)
        endButton?.contentDescription = getString(R.string.schedule_off_at_time, endButton?.text)
        overnight?.visibility = if (end <= start) View.VISIBLE else View.GONE
    }

    /** A schedule needs at least one day. */
    private fun showSaveEnabled() {
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = days != 0
    }

    private fun pickTime(tag: String) {
        if (childFragmentManager.findFragmentByTag(tag) != null || childFragmentManager.isStateSaved) return
        val time = if (tag == TAG_START) start else end
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(time.hour)
            .setMinute(time.minute)
            .setTitleText(if (tag == TAG_START) R.string.schedule_on_at else R.string.schedule_off_at)
            .build()
        listen(picker, tag)
        picker.show(childFragmentManager, tag)
    }

    /** [picker]'s OK sets the start or end time, as [tag] says. */
    private fun listen(picker: MaterialTimePicker, tag: String) {
        picker.addOnPositiveButtonClickListener {
            val time = LocalTime.of(picker.hour, picker.minute)
            if (tag == TAG_START) start = time else end = time
            showTimes()
        }
    }

    private fun saved() {
        val original = original
        val schedule = Schedule(
            id = original?.id ?: System.currentTimeMillis(),
            start = start,
            end = end,
            days = days,
            enabled = original?.enabled ?: true,
        )
        parentFragmentManager.setFragmentResult(RESULT, Bundle().also { write(it, schedule) })
    }

    private fun deleted() {
        val original = original ?: return
        parentFragmentManager.setFragmentResult(RESULT, Bundle().also {
            write(it, original)
            it.putBoolean(KEY_DELETED, true)
        })
    }

    companion object {
        /** The fragment result's key: a schedule ([schedule]), saved or [deleted]. */
        const val RESULT = "schedule"

        private const val TAG = "schedule_dialog"
        private const val TAG_START = "start"
        private const val TAG_END = "end"
        private const val ARG_ID = "id"
        private const val KEY_START = "start"
        private const val KEY_END = "end"
        private const val KEY_DAYS = "days"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DELETED = "deleted"

        /** Opens the editor for [schedule], or for a new one, unless it's open already. */
        fun show(fm: FragmentManager, schedule: Schedule?) {
            if (fm.findFragmentByTag(TAG) != null || fm.isStateSaved) return
            ScheduleDialog().apply {
                arguments = Bundle().also { if (schedule != null) write(it, schedule) }
            }.show(fm, TAG)
        }

        /** The schedule in a [RESULT]. */
        fun schedule(result: Bundle): Schedule = read(result)

        /** Whether a [RESULT] is a delete. */
        fun deleted(result: Bundle) = result.getBoolean(KEY_DELETED)

        private fun write(bundle: Bundle, schedule: Schedule) {
            bundle.putLong(ARG_ID, schedule.id)
            bundle.putInt(KEY_START, schedule.start.toSecondOfDay())
            bundle.putInt(KEY_END, schedule.end.toSecondOfDay())
            bundle.putInt(KEY_DAYS, schedule.days)
            bundle.putBoolean(KEY_ENABLED, schedule.enabled)
        }

        private fun read(bundle: Bundle) = Schedule(
            id = bundle.getLong(ARG_ID),
            start = LocalTime.ofSecondOfDay(bundle.getInt(KEY_START).toLong()),
            end = LocalTime.ofSecondOfDay(bundle.getInt(KEY_END).toLong()),
            days = bundle.getInt(KEY_DAYS),
            enabled = bundle.getBoolean(KEY_ENABLED, true),
        )
    }
}
