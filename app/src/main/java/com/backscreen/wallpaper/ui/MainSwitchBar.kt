package com.backscreen.wallpaper.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.backscreen.wallpaper.R
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * A section's main switch, pinned at the top of its tab like Android Settings' per-page switch
 * bar: the whole bar is one big switch, tinted while it's on. Screen readers hear it as a
 * switch with its title and summary.
 */
class MainSwitchBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Called when you flip it, not when [isChecked] is set from code. */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    // Kept here rather than read from the switch, which Android could otherwise restore on its
    // own after a configuration change, leaving the bar's colour behind.
    var isChecked = false
        set(value) {
            if (field == value) return
            field = value
            toggle.isChecked = value
            updateColors(animate = isLaidOut)
        }

    private val title: TextView
    private val summary: TextView
    private val toggle: MaterialSwitch
    private val shape = GradientDrawable()
    private var shownColor = 0
    private var colorAnimator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val dp = resources.displayMetrics.density
        minimumHeight = (72 * dp).toInt()
        setPaddingRelative((24 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
        LayoutInflater.from(context).inflate(R.layout.view_main_switch_bar, this)
        title = findViewById(R.id.mainSwitchTitle)
        summary = findViewById(R.id.mainSwitchSummary)
        toggle = findViewById(R.id.mainSwitchToggle)
        toggle.isSaveEnabled = false
        context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.text)).apply {
            title.text = getText(0)
            recycle()
        }

        shape.cornerRadius = 28 * dp
        val ripple = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorControlHighlight)
        background = RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            isChecked = !isChecked
            onCheckedChange?.invoke(isChecked)
        }
        updateColors(animate = false)
    }

    fun setTitle(text: Int) = title.setText(text)

    fun setSummary(text: CharSequence?) {
        summary.text = text
        summary.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setSummary(text: Int) = setSummary(context.getText(text))

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        toggle.isEnabled = enabled
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        title.alpha = alpha
        summary.alpha = alpha
    }

    // The bar takes every touch, so dragging the switch's thumb can't flip it behind our back.
    override fun onInterceptTouchEvent(event: MotionEvent) = true

    override fun getAccessibilityClassName(): CharSequence = Switch::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = isChecked
    }

    private fun updateColors(animate: Boolean) {
        val on = isChecked
        val bg = color(if (on) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainerHighest)
        title.setTextColor(color(if (on) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurface))
        summary.setTextColor(color(if (on) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurfaceVariant))
        colorAnimator?.cancel()
        if (!animate) {
            setShownColor(bg)
            return
        }
        colorAnimator = ValueAnimator.ofArgb(shownColor, bg).apply {
            duration = COLOR_FADE_MS
            addUpdateListener { setShownColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun setShownColor(color: Int) {
        shownColor = color
        shape.setColor(color)
    }

    private fun color(attr: Int) = MaterialColors.getColor(this, attr)

    private companion object {
        const val DISABLED_ALPHA = 0.38f
        const val COLOR_FADE_MS = 200L
    }
}
