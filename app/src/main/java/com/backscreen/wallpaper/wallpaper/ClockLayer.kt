package com.backscreen.wallpaper.wallpaper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The looks the clock can have, and where each sits until it's moved (see [ClockSettings]). */
enum class ClockStyle(val label: Int, val x: Float, val y: Float) {
    CLASSIC(R.string.clock_classic, 0.5f, 0.5f),
    LIGHT(R.string.clock_light, 0f, 1f),
    STACKED(R.string.clock_stacked, 1f, 0.5f),
    DIGITAL(R.string.clock_digital, 1f, 1f),
    SERIF(R.string.clock_serif, 0.5f, 0f),
}

/**
 * How the clock looks. [x] and [y] place it in the space it can move in: 0 is the left or top
 * edge, 1 the right or bottom. A null colour is picked to stand out from the image.
 */
data class ClockSettings(
    val style: ClockStyle = ClockStyle.CLASSIC,
    val x: Float = style.x,
    val y: Float = style.y,
    val color: Int? = null,
    val bgColor: Int? = null,
    val bgOpacity: Int = 0,
)

/**
 * The time and date, drawn over the wallpaper. Everything is sized as a share of the layer's
 * height, so the app's small preview looks just like the rear display. Keep it clear of the
 * camera with padding.
 *
 * Plain text views that [ClockTicker] keeps up to date, not TextClocks: a TextClock counts to
 * its next tick in uptime and stops ticking while its window is hidden, so on a back screen
 * that stays visible while the phone sleeps it fell minutes behind.
 */
class ClockLayer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs), ClockTicker.Listener {

    var settings = ClockSettings()
        set(value) {
            val old = field
            field = value
            if (value.style != old.style) build() else applyLook()
            requestLayout()
        }

    /** What the clock is drawn over, read to pick automatic colours. Same bounds as this. */
    var backdrop: View? = null
        set(value) {
            field = value
            backdropChanged()
        }

    /** Set to let the clock be dragged; called with its new place when let go. */
    var onMoved: ((x: Float, y: Float) -> Unit)? = null

    /** Called when the automatic colours change, e.g. for a new image. */
    var onColorsChanged: (() -> Unit)? = null

    /** Called when the time or date shown changes. */
    var onTextChanged: (() -> Unit)? = null

    /** The colours Auto gives right now, and the colours in use. */
    val autoTextColor get() = if (lightText) shadeLight else shadeDark
    val textColor get() = settings.color ?: autoTextColor
    val autoBgColor get() = if (isLight(textColor)) shadeDark else shadeLight
    val bgColor get() = settings.bgColor ?: autoBgColor

    private val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** One line of the clock: its formats, and its size as a share of the height. */
    private class Field(val view: TextView, val size: Float, val format12: String, val format24: String)

    private val fields = mutableListOf<Field>()

    // The date formats depend on the language, so they're rebuilt when it changes.
    private var builtFor: Locale? = null

    // Automatic colours: a light and a dark shade of the image under the clock; the text gets
    // whichever stands out more, and the background the other.
    private var shadeLight = Color.WHITE
    private var shadeDark = Color.BLACK
    private var lightText = true
    private val sampled = Rect()

    // A change of automatic colours while the image pans fades in, from these colours.
    private var fadeFrom: Look? = null
    private var fadeAmount = 1f
    private var fade: ValueAnimator? = null

    private var dragging = false
    private var grabX = 0f
    private var grabY = 0f
    private var snapped = false
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = resources.displayMetrics.density * 1.5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val scratch = RectF()

    init {
        addView(box, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setWillNotDraw(false)
        build()
    }

    private fun build() {
        box.removeAllViews()
        fields.clear()
        builtFor = Locale.getDefault()
        when (settings.style) {
            ClockStyle.CLASSIC -> {
                add(time(), 0.30f, "sans-serif-medium")
                add(date("EEEEdMMMM"), 0.075f, "sans-serif")
            }
            ClockStyle.LIGHT -> {
                add(time(), 0.30f, "sans-serif-thin")
                add(date("EEEEdMMMM"), 0.07f, "sans-serif-light")
            }
            ClockStyle.STACKED -> {
                add(clock("hh", "HH"), 0.29f, "sans-serif-black")
                add(clock("mm", "mm"), 0.29f, "sans-serif-black")
                add(date("EEEdMMM"), 0.065f, "sans-serif-medium")
            }
            ClockStyle.DIGITAL -> {
                add(time(), 0.20f, "monospace")
                add(date("EEEdMMM"), 0.06f, "monospace")
            }
            ClockStyle.SERIF -> {
                add(date("EEEEdMMMM"), 0.05f, "serif") { isAllCaps = true; letterSpacing = 0.2f }
                add(time(), 0.28f, "serif")
            }
        }
        applyLook()
        updateTime()
    }

    private fun add(formats: Formats, size: Float, font: String, style: TextView.() -> Unit = {}) {
        val text = TextView(context).apply {
            typeface = Typeface.create(font, Typeface.NORMAL)
            includeFontPadding = false
            style()
        }
        box.addView(text)
        fields += Field(text, size, formats.format12, formats.format24)
    }

    private class Formats(val format12: String, val format24: String)

    private fun time() = clock("h:mm", "HH:mm")

    /** The date the way the phone's region writes it, from a [skeleton] like "EEEEdMMMM". */
    private fun date(skeleton: String): Formats {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        return clock(pattern, pattern)
    }

    private fun clock(format12: String, format24: String) = Formats(format12, format24)

    /**
     * Shows the time now, in the phone's 12 or 24-hour style. Cheap when nothing has changed,
     * so call it whenever the phone may have slept.
     */
    fun updateTime() {
        if (Locale.getDefault() != builtFor) return build()
        val now = Calendar.getInstance()
        val is24 = DateFormat.is24HourFormat(context)
        var changed = false
        for (field in fields) {
            val text = DateFormat.format(if (is24) field.format24 else field.format12, now).toString()
            // Only a real change, so checking again doesn't re-measure or redraw anything.
            if (field.view.text.toString() != text) {
                field.view.text = text
                changed = true
            }
        }
        if (changed) onTextChanged?.invoke()
    }

    override fun onTimeChanged() {
        if (isShown) updateTime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ClockTicker.add(context, this)
        updateTime()
    }

    override fun onDetachedFromWindow() {
        ClockTicker.remove(this)
        stopFade()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // Shown again, perhaps after a long time hidden, with the image panned on under it.
        if (isVisible) {
            updateTime()
            backdropChanged()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Changing sizes during layout needs another pass, so do it just after.
        post { applyLook() }
    }

    /** Sizes, colours and background, from [settings] and the layer's height. */
    private fun applyLook() {
        val h = height
        for (field in fields) {
            if (h > 0) field.view.setTextSize(TypedValue.COMPLEX_UNIT_PX, field.size * h)
        }
        box.gravity = when {
            settings.x < 1 / 3f -> Gravity.START
            settings.x > 2 / 3f -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        if (bgAlpha() > 0) {
            val pad = (0.035f * h).toInt()
            box.setPadding(pad * 2, pad, pad * 2, pad)
            box.background = GradientDrawable().apply { cornerRadius = 0.05f * h }
        } else {
            box.setPadding(0, 0, 0, 0)
            box.background = null
        }
        applyColors()
    }

    /** The colours of the text, the background behind it, and the text's shadow. */
    private class Look(val text: Int, val bg: Int, val shadow: Int)

    private fun bgAlpha() = settings.bgOpacity.coerceIn(0, 100) * 255 / 100

    /** The colours the settings and the image call for. */
    private fun look(): Look {
        val text = textColor
        val bgAlpha = bgAlpha()
        // A shadow helps the text stand out, unless there's a solid background doing that.
        val shadow = when {
            bgAlpha > 100 -> Color.TRANSPARENT
            isLight(text) -> 0x99000000.toInt()
            else -> 0x66FFFFFF
        }
        return Look(text, ColorUtils.setAlphaComponent(bgColor, bgAlpha), shadow)
    }

    /** The colours on screen: part way from the old ones while a change fades in. */
    private fun shownLook(): Look {
        val to = look()
        val from = fadeFrom ?: return to
        return Look(
            ColorUtils.blendARGB(from.text, to.text, fadeAmount),
            ColorUtils.blendARGB(from.bg, to.bg, fadeAmount),
            ColorUtils.blendARGB(from.shadow, to.shadow, fadeAmount),
        )
    }

    private fun applyColors() {
        val h = height
        val look = shownLook()
        for (field in fields) {
            field.view.setTextColor(look.text)
            field.view.setShadowLayer(max(0.02f * h, 0.01f), 0f, 0.005f * h, look.shadow)
        }
        (box.background as? GradientDrawable)?.setColor(look.bg)
    }

    /** Changes the colours gradually, from [from] to what they should be now. */
    private fun fadeColors(from: Look) {
        stopFade()
        fadeFrom = from
        fadeAmount = 0f
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COLOR_FADE_MS
            addUpdateListener {
                fadeAmount = it.animatedValue as Float
                applyColors()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (fade !== animation) return
                    fade = null
                    fadeFrom = null
                    applyColors()
                }
            })
            start()
        }
    }

    private fun stopFade() {
        val running = fade ?: return
        fade = null
        fadeFrom = null
        running.cancel()
    }

    /** The space the clock can move in: inside the padding, less a margin. */
    private fun area(): Rect {
        val margin = (0.07f * height).toInt()
        return Rect(
            paddingLeft + margin, paddingTop + margin,
            max(paddingLeft + margin, width - paddingRight - margin),
            max(paddingTop + margin, height - paddingBottom - margin)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val area = area()
        val w = box.measuredWidth
        val h = box.measuredHeight
        val x = area.left + (max(0, area.width() - w) * settings.x).roundToInt()
        val y = area.top + (max(0, area.height() - h) * settings.y).roundToInt()
        box.layout(x, y, x + w, y + h)
        // Moved onto a different part of the image.
        if (!dragging && !sampled.equals(x, y, x + w, y + h)) post { sampleBackdrop() }
    }

    private fun Rect.equals(l: Int, t: Int, r: Int, b: Int) = left == l && top == t && right == r && bottom == b

    /** The image under the clock changed; pick new automatic colours. */
    fun backdropChanged() {
        sampled.setEmpty()
        post { sampleBackdrop() }
    }

    /**
     * The image under the clock moved, as it pans. Checks the automatic colours again, but only
     * changes them when the text should flip between light and dark, and then fades: a clock
     * changing tint every few seconds would be distracting.
     */
    fun backdropMoved() {
        if (!isShown || dragging) return
        // Both colours chosen, so there's nothing automatic to keep up with.
        if (settings.color != null && (settings.bgColor != null || settings.bgOpacity == 0)) return
        sampleBackdrop(moving = true)
    }

    /** Works out the automatic colours from the average colour under the clock. */
    private fun sampleBackdrop(moving: Boolean = false) {
        val src = backdrop ?: return
        if (box.width == 0 || src.width == 0) return
        sampled.set(box.left, box.top, box.right, box.bottom)
        // Where the clock is, in the image's own coordinates (they're side by side in one frame).
        val region = Rect(sampled).apply { offset(this@ClockLayer.left - src.left, this@ClockLayer.top - src.top) }
        val average = averageColor(src, region) ?: return
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(average, it) }
        // A tint of the image's colour, not so strong that it's hard to read.
        val saturation = min(hsl[1], 0.45f)
        val light = ColorUtils.HSLToColor(floatArrayOf(hsl[0], saturation, 0.95f))
        val dark = ColorUtils.HSLToColor(floatArrayOf(hsl[0], saturation, 0.12f))
        val useLight = ColorUtils.calculateContrast(light, average) >= ColorUtils.calculateContrast(dark, average)
        if (moving && useLight == lightText) return
        if (light == shadeLight && dark == shadeDark && useLight == lightText) return
        val shown = shownLook()
        shadeLight = light
        shadeDark = dark
        lightText = useLight
        if (moving) {
            fadeColors(from = shown)
        } else {
            stopFade()
            applyLook()
        }
        onColorsChanged?.invoke()
    }

    /** The average colour of [region] of [src], over black like the back screen. */
    private fun averageColor(src: View, region: Rect): Int? {
        if (!region.intersect(0, 0, src.width, src.height)) return Color.BLACK
        // A small copy is plenty for an average.
        val scale = min(1f, SAMPLE_SIZE / max(region.width(), region.height()))
        val bitmap = Bitmap.createBitmap(
            max(1, (region.width() * scale).roundToInt()), max(1, (region.height() * scale).roundToInt()),
            Bitmap.Config.ARGB_8888
        )
        try {
            Canvas(bitmap).apply {
                scale(scale, scale)
                translate(-region.left.toFloat(), -region.top.toFloat())
                src.draw(this)
            }
        } catch (e: RuntimeException) {
            // e.g. an image kept in graphics memory, which can't be read back here.
            BackScreen.log("Couldn't read image colours: ${e.message}")
            bitmap.recycle()
            return null
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            val a = Color.alpha(p)
            r += Color.red(p) * a / 255
            g += Color.green(p) * a / 255
            b += Color.blue(p) * a / 255
        }
        val n = pixels.size
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun isLight(color: Int) = ColorUtils.calculateLuminance(color) > 0.4

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val onMoved = onMoved ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the clock itself; a tap elsewhere goes to the preview.
                val slop = (12 * resources.displayMetrics.density).toInt()
                val hit = Rect(box.left - slop, box.top - slop, box.right + slop, box.bottom + slop)
                if (visibility != VISIBLE || !hit.contains(event.x.toInt(), event.y.toInt())) return false
                dragging = true
                snapped = true
                grabX = event.x - box.left
                grabY = event.y - box.top
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val area = area()
                val x = snap((event.x - grabX - area.left) / max(1, area.width() - box.width))
                val y = snap((event.y - grabY - area.top) / max(1, area.height() - box.height))
                // A tick as it lines up with an edge or the middle.
                val nowSnapped = x.isSnapPoint() || y.isSnapPoint()
                if (nowSnapped && !snapped) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                snapped = nowSnapped
                settings = settings.copy(x = x, y = y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                invalidate()
                sampleBackdrop()
                onMoved(settings.x, settings.y)
            }
        }
        return true
    }

    /** Pulls [value] onto an edge or the middle when close, and keeps it in 0..1. */
    private fun snap(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return SNAP_POINTS.firstOrNull { abs(it - v) < SNAP_DISTANCE } ?: v
    }

    private fun Float.isSnapPoint() = this in SNAP_POINTS

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!dragging) return
        val inset = outline.strokeWidth * 2
        scratch.set(box.left - inset, box.top - inset, box.right + inset, box.bottom + inset)
        val corner = 8 * resources.displayMetrics.density
        canvas.drawRoundRect(scratch, corner, corner, outline)
    }

    private companion object {
        const val SAMPLE_SIZE = 48f
        const val COLOR_FADE_MS = 600L
        const val SNAP_DISTANCE = 0.04f
        val SNAP_POINTS = listOf(0f, 0.5f, 1f)
    }
}
