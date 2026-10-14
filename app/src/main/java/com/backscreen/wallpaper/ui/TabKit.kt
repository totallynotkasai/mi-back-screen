package com.backscreen.wallpaper.ui

import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.Fragment
import com.backscreen.wallpaper.MainActivity
import com.backscreen.wallpaper.R
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar

/** How faded a card or row is while it doesn't apply: Material's strength for something disabled. */
const val DISABLED_ALPHA = 0.38f

/** Shizuku isn't ready, and has had its moment to connect: a tab that needs it says it's waiting. */
val Fragment.shizukuMissing: Boolean
    get() = (activity as? MainActivity)?.shizukuMissing == true

/** Something this tab does needs Shizuku, which isn't ready: says so, with Setup help. */
fun Fragment.needShizuku() = (requireActivity() as MainActivity).needShizuku()

/** A message at the foot of the app's window, with an [action] if given ([MainActivity.snackbar]). */
fun Fragment.snackbar(text: Int, length: Int = Snackbar.LENGTH_SHORT, action: Int? = null, onAction: () -> Unit = {}): Snackbar =
    (requireActivity() as MainActivity).snackbar(text, length, action, onAction)

fun Fragment.snackbar(text: CharSequence, length: Int = Snackbar.LENGTH_SHORT, action: Int? = null, onAction: () -> Unit = {}): Snackbar =
    (requireActivity() as MainActivity).snackbar(text, length, action, onAction)

/**
 * Fills the group with a choice chip for each of [options], [selected] checked and each named by
 * [label]; [onChosen] when you pick another. The chips aren't restored after a rotation
 * (`chip_style`): the tab checks one from the settings each time. Returns the chips, in order.
 */
fun <T> ChipGroup.addChoiceChips(options: List<T>, selected: T, label: (T) -> CharSequence, onChosen: (T) -> Unit): List<Chip> {
    val inflater = LayoutInflater.from(context)
    val chips = options.map { option ->
        val chip = inflater.inflate(R.layout.chip_style, this, false) as Chip
        chip.id = View.generateViewId()
        chip.tag = option
        chip.text = label(option)
        chip.isChecked = option == selected
        addView(chip)
        chip
    }
    setOnCheckedStateChangeListener { group, ids ->
        @Suppress("UNCHECKED_CAST")
        val chosen = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as T } ?: return@setOnCheckedStateChangeListener
        onChosen(chosen)
    }
    return chips
}

/** Every chip in the group on or off. */
fun ChipGroup.setChipsEnabled(enabled: Boolean) {
    for (i in 0 until childCount) getChildAt(i).isEnabled = enabled
}
