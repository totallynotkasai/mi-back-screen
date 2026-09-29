package com.backscreen.wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Locale
import kotlin.math.roundToInt

/** A dialog to choose a colour, or Auto to have one picked to suit the image. */
object ColorPicker {

    private val PRESETS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF9E9E9E.toInt(), 0xFF424242.toInt(), 0xFF000000.toInt(),
        0xFFF44336.toInt(), 0xFFFF9800.toInt(), 0xFFFFEB3B.toInt(), 0xFF8BC34A.toInt(),
        0xFF009688.toInt(), 0xFF00BCD4.toInt(), 0xFF2196F3.toInt(), 0xFF3F51B5.toInt(),
        0xFF9C27B0.toInt(), 0xFFE91E63.toInt(), 0xFFFFCCBC.toInt(), 0xFF795548.toInt(),
    )
    private const val COLUMNS = 8

    /**
     * Shows the dialog. [current] is the chosen colour, null for Auto, which is [auto] right
     * now; [autoSummary] says what Auto does. [onPicked] gets the choice, null for Auto.
     */
    fun show(
        context: Context, title: Int, autoSummary: Int, current: Int?, auto: Int,
        onPicked: (Int?) -> Unit,
    ) {
        val builder = MaterialAlertDialogBuilder(context)
        val ctx = builder.context
        val dp = ctx.resources.displayMetrics.density
        val hsv = FloatArray(3).also { Color.colorToHSV(current ?: auto, it) }
        var isAuto = current == null
        var syncing = false

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }

        // Auto on/off.
        val autoSwitch = MaterialSwitch(ctx)
        val autoRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * dp).toInt()
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(ctx, R.string.colour_auto, com.google.android.material.R.attr.textAppearanceBodyLarge,
                    com.google.android.material.R.attr.colorOnSurface))
                addView(text(ctx, autoSummary, com.google.android.material.R.attr.textAppearanceBodySmall,
                    com.google.android.material.R.attr.colorOnSurfaceVariant))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(autoSwitch)
            setOnClickListener { autoSwitch.toggle() }
        }
        content.addView(autoRow)

        // Everything below is the manual choice; dimmed while Auto is on.
        val manual = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(manual)

        val swatch = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke((1 * dp).toInt(), MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutline, Color.GRAY))
            }
        }
        val hex = EditText(ctx).apply {
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(7), InputFilter.AllCaps())
            contentDescription = ctx.getString(R.string.colour_hex)
        }
        manual.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(swatch, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
                marginEnd = (16 * dp).toInt()
            })
            addView(hex, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })

        val spectrum = SpectrumView(ctx)
        manual.addView(spectrum, LinearLayout.LayoutParams(MATCH_PARENT, (160 * dp).toInt()).apply {
            topMargin = (16 * dp).toInt()
        })

        val brightness = Slider(ctx).apply {
            valueFrom = 0f
            valueTo = 100f
            isTickVisible = false
            contentDescription = ctx.getString(R.string.colour_brightness)
            setLabelFormatter { "${it.roundToInt()}%" }
        }
        manual.addView(brightness, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = (8 * dp).toInt()
        })

        val presets = PRESETS.map { color -> SwatchView(ctx, color) }
        for (row in presets.chunked(COLUMNS)) {
            manual.addView(LinearLayout(ctx).apply {
                for (s in row) addView(s, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        /** Shows [hsv] everywhere; [from] is the control that changed it, left alone. */
        fun sync(from: View? = null) {
            syncing = true
            val color = Color.HSVToColor(hsv)
            (swatch.background as GradientDrawable).setColor(color)
            if (from !== hex) hex.setText("#%06X".format(Locale.US, color and 0xFFFFFF))
            if (from !== spectrum) spectrum.set(hsv[0], hsv[1], hsv[2])
            if (from !== brightness) brightness.value = (hsv[2] * 100).roundToInt().toFloat().coerceIn(0f, 100f)
            spectrum.value = hsv[2]
            for (p in presets) p.chosen = !isAuto && p.color == color
            autoSwitch.isChecked = isAuto
            manual.alpha = if (isAuto) 0.38f else 1f
            syncing = false
        }

        /** Choosing a colour turns Auto off. */
        fun chose(from: View?) {
            isAuto = false
            sync(from)
        }

        autoSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            isAuto = checked
            if (checked) Color.colorToHSV(auto, hsv)
            sync()
        }
        spectrum.onChanged = { h, s ->
            hsv[0] = h
            hsv[1] = s
            // Picking a hue on black would show nothing; lift it so the choice can be seen.
            if (hsv[2] < 0.2f) hsv[2] = 1f
            chose(spectrum)
        }
        brightness.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            hsv[2] = value / 100
            chose(brightness)
        }
        hex.doAfterTextChanged { text ->
            if (syncing) return@doAfterTextChanged
            val digits = text.toString().removePrefix("#")
            if (digits.length != 6) return@doAfterTextChanged
            val color = digits.toIntOrNull(16) ?: return@doAfterTextChanged
            Color.colorToHSV(color or 0xFF000000.toInt(), hsv)
            chose(hex)
        }
        for (p in presets) p.setOnClickListener {
            Color.colorToHSV(p.color, hsv)
            chose(null)
        }
        sync()

        builder
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(if (isAuto) null else Color.HSVToColor(hsv)) }
            .show()
    }

    private fun text(context: Context, text: Int, appearance: Int, color: Int) = TextView(context).apply {
        setText(text)
        val style = TypedValue().also { context.theme.resolveAttribute(appearance, it, true) }
        setTextAppearance(style.resourceId)
        setTextColor(MaterialColors.getColor(context, color, Color.BLACK))
    }

    /** Hue across, saturation down, at the chosen brightness. */
    private class SpectrumView(context: Context) : View(context) {
        var onChanged: ((hue: Float, saturation: Float) -> Unit)? = null

        private var hue = 0f
        private var saturation = 1f
        var value = 1f
            set(v) {
                field = v
                invalidate()
            }

        private val dp = resources.displayMetrics.density
        private val inset = 14 * dp
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val clip = Path()
        private val bounds = RectF()

        init {
            contentDescription = context.getString(R.string.colour_spectrum)
        }

        fun set(h: Float, s: Float, v: Float) {
            hue = h
            saturation = s
            value = v
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
            // Inset so the thumb isn't cut off at the edges.
            bounds.set(inset, inset, w - inset, h - inset)
            fill.shader = ComposeShader(
                LinearGradient(bounds.left, 0f, bounds.right, 0f, hues, null, Shader.TileMode.CLAMP),
                LinearGradient(0f, bounds.top, 0f, bounds.bottom, Color.TRANSPARENT, Color.WHITE, Shader.TileMode.CLAMP),
                PorterDuff.Mode.SRC_OVER
            )
            clip.reset()
            clip.addRoundRect(bounds, 16 * dp, 16 * dp, Path.Direction.CW)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(bounds, fill)
            shade.color = Color.argb(((1 - value) * 255).roundToInt(), 0, 0, 0)
            canvas.drawRect(bounds, shade)
            canvas.restore()
            val x = bounds.left + hue / 360f * bounds.width()
            val y = bounds.top + (1 - saturation) * bounds.height()
            thumb.color = Color.HSVToColor(floatArrayOf(hue, saturation, value))
            canvas.drawCircle(x, y, 11 * dp, thumb)
            ring.strokeWidth = 3 * dp
            ring.color = Color.WHITE
            canvas.drawCircle(x, y, 11 * dp, ring)
            ring.strokeWidth = 1 * dp
            ring.color = 0x66000000
            canvas.drawCircle(x, y, 12.5f * dp, ring)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            hue = ((event.x - bounds.left) / bounds.width()).coerceIn(0f, 1f) * 360f
            saturation = 1 - ((event.y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
            invalidate()
            onChanged?.invoke(hue, saturation)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    /** A round preset colour; a ring shows the one chosen. */
    private class SwatchView(context: Context, val color: Int) : View(context) {
        private val dp = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, Color.GRAY)
        private val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.BLUE)

        var chosen = false
            set(value) {
                field = value
                invalidate()
            }

        init {
            isClickable = true
            isFocusable = true
            contentDescription = "#%06X".format(Locale.US, color and 0xFFFFFF)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, w.coerceAtMost((48 * dp).toInt()))
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - 6 * dp
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1 * dp
            paint.color = outline
            canvas.drawCircle(cx, cy, r, paint)
            if (chosen) {
                paint.strokeWidth = 2.5f * dp
                paint.color = primary
                canvas.drawCircle(cx, cy, r + 4 * dp, paint)
            }
        }
    }
}
