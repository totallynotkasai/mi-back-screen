package com.backscreen.wallpaper.ui

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import android.widget.Switch

/**
 * An option's row and its switch as one control: a tap anywhere on the row flips the switch,
 * and screen readers hear the row as one switch, named by the row's text, with its state.
 * Otherwise TalkBack stops on the row, then again on a switch with no name.
 */
object SwitchRow {

    fun bind(row: View, switch: CompoundButton) {
        row.setOnClickListener { switch.toggle() }
        // The row takes the touches; the switch only shows the state.
        switch.isClickable = false
        switch.isFocusable = false
        switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = switch.isChecked
            }
        }
    }
}
