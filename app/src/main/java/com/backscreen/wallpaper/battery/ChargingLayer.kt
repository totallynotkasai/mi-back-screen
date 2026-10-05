package com.backscreen.wallpaper.battery

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.backscreen.wallpaper.wallpaper.ClockLayer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The charging animation over the wallpaper, in the chosen [style] ([ChargingTimeline] says what
 * shows when):
 *
 * - Ring: the wallpaper dims under a veil, a bolt draws in, a ring fills to the battery's level
 *   as the number counts up, with "Charging" or "Wireless charging" under it, then it all fades.
 * - Edge glow: a soft glow along the back screen's rounded edge ([EdgePath]), with a brighter head
 *   running round as far as the level; then, in the wallpaper, a faint glow stays while it
 *   charges ([showFaint]). No veil, and the clock stays.
 * - Minimal: a small bolt and the level, counting up, just below the [clock] (or where it would
 *   be), and fading after a moment. No veil, and the clock stays.
 *
 * The ring, glow and bolt are in [accent], or Auto: the wallpaper's colour ([setAutoAccent]), or
 * the clock's text colour without one. The number and label keep the clock's text colour, so
 * they stay readable.
 *
 * Drawn on a Canvas, with no image assets, and sized as a share of the layer's height, so the
 * app's small preview looks just like the back screen. Padding keeps the ring and Minimal clear
 * of the camera, as the clock does; the veil and the glow cover everything, the glow round the
 * camera too, since the picture shows round the lenses. The glow is drawn once into a bitmap with
 * a blur, for each size, and drawn from that: a faint glow left showing is never redrawn, and the
 * panning wallpaper underneath isn't slowed.
 */
class ChargingLayer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Called when an animation ends: played out, stopped or cancelled. */
    var onDone: (() -> Unit)? = null

    /**
     * Called with how much of the Ring's veil shows, 0 to 1, as that changes. The clock fades out
     * by as much: it would show through the veil and crowd the ring.
     */
    var onShown: ((Float) -> Unit)? = null
    private var shown = 0f

    /** Whether an animation is running, rather than nothing, the still or the faint glow. */
    var isPlaying = false
        private set

    /** Whether the faint Edge glow is showing on its own, as it does while charging. */
    var isFaint = false
        private set

    /** The style the next [play] or [showStill] uses. */
    var style = ChargingStyle.RING

    /** The chosen colour for the ring, glow and bolt; null for Auto. */
    var accent: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Edge glow: whether it settles to the faint glow after playing (in the wallpaper), or fades away. */
    var stayFaint = false

    /** Minimal: centred, as in a pop-over, which has no clock. */
    var centred = false

    /** Minimal sits by this clock, which fills the same frame, or where it would be if it's off; it follows it. */
    var clock: ClockLayer? = null
        set(value) {
            field = value
            value?.onPlaced = { if (frame is MinimalFrame) invalidate() }
        }

    /** The back screen's rounded corners, as a share of its height. */
    var cornerShare = DEFAULT_CORNER_SHARE
        set(value) {
            if (field == value) return
            field = value
            dropGlow()
            invalidate()
        }

    private var level = 0
    private var label = ""
    private var frame: StyleFrame? = null

    // Fades everything out early when you unplug, and the still in after a play.
    private var exit = 1f

    private var textColor = Color.WHITE
    private var veilColor = Color.BLACK
    private var autoAccent: Int? = null

    // A change of Auto colour while it shows fades in from this.
    private var accentFrom: Int? = null
    private var accentFade = 1f
    private var accentAnimator: ValueAnimator? = null

    private var animator: ValueAnimator? = null
    private var exitAnimator: ValueAnimator? = null
    private val stillDone = Runnable { finish() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val boltFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boltLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // Every digit the same width, so the number doesn't jiggle as it counts.
        fontFeatureSettings = "tnum"
    }
    private val percent = Paint(number)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.02f
    }
    private val oval = RectF()
    private val traced = Path()
    private val boltLength = PathMeasure(BOLT, true).length
    private val boltMeasure = PathMeasure(BOLT, true)

    // The Edge glow: its outline, the glow drawn once along it, and what's drawn from that.
    private var edge: EdgePath? = null
    private val edgePath = Path()
    private val edgeMeasure = PathMeasure()
    private val segment = Path()
    private var glow: Bitmap? = null
    private var glowShader: BitmapShader? = null
    private var glowFilterColor = 0
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = Rect()

    /** The text and veil colours: the clock's, so it matches the wallpaper. */
    fun setColors(text: Int, veil: Int) {
        if (text == textColor && veil == veilColor) return
        textColor = text
        veilColor = veil
        invalidate()
    }

    /**
     * The wallpaper's colour, for Auto ([com.backscreen.wallpaper.wallpaper.WallpaperColour]);
     * null without one. A change while the glow shows fades to the new colour.
     */
    fun setAutoAccent(color: Int?) {
        if (color == autoAccent) return
        val from = accentNow()
        autoAccent = color
        if (accent == null && (isPlaying || isFaint) && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            fadeAccent(from)
        } else {
            invalidate()
        }
    }

    /** The ring's, glow's or bolt's colour now: the chosen one, else the wallpaper's, else the clock's. */
    val accentColor get() = accent ?: autoAccent ?: textColor

    /** Plays it from the start for [status], replacing one that's running. */
    fun play(status: ChargingStatus) {
        clear()
        setStatus(status)
        isPlaying = true
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // The phone's animations are off: show it still for a while instead.
            frame = ChargingTimeline.still(style, level)
            changed()
            postDelayed(stillDone, STILL_MS)
            return
        }
        frame = frameAt(0)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ChargingTimeline.DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                frame = frameAt(it.currentPlayTime)
                changed()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) finish()
                }
            })
            start()
        }
    }

    private fun frameAt(elapsedMs: Long) = ChargingTimeline.frame(style, elapsedMs, level, stayFaint)

    /**
     * You unplugged: a running animation, or the faint glow, fades away now, rather than saying
     * "Charging".
     */
    fun stop() {
        if ((!isPlaying && !isFaint) || exitAnimator != null) return
        removeCallbacks(stillDone)
        // Held where it is while it fades.
        animator?.let {
            animator = null
            it.cancel()
        }
        exitAnimator = ValueAnimator.ofFloat(exit, 0f).apply {
            duration = STOP_FADE_MS
            addUpdateListener {
                exit = it.animatedValue as Float
                changed()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (exitAnimator === animation) end(faint = false)
                }
            })
            start()
        }
    }

    /**
     * Ends a running animation at once: the back screen dimmed, and would keep a half-faded frame
     * of it. An Edge glow that stays goes straight to the faint glow.
     */
    fun cancel() {
        if (isPlaying) finish()
    }

    /** The faint Edge glow on its own, as it shows while charging. */
    fun showFaint() {
        clear()
        isFaint = true
        frame = ChargingTimeline.faint
        changed()
    }

    /** Everything in place and still, for the app's preview at rest; [fadeIn] to ease it in. */
    fun showStill(status: ChargingStatus, fadeIn: Boolean = false) {
        clear()
        setStatus(status)
        frame = ChargingTimeline.still(style, level)
        if (fadeIn) {
            exit = 0f
            exitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = STILL_FADE_MS
                addUpdateListener {
                    exit = it.animatedValue as Float
                    changed()
                }
                start()
            }
        }
        changed()
    }

    /** Shows nothing, with no [onDone]. */
    fun clear() {
        stopAnimators()
        isPlaying = false
        isFaint = false
        frame = null
        exit = 1f
        changed()
    }

    /** The frame or the fade moved on: draw it, and say how much of the veil shows. */
    private fun changed() {
        invalidate()
        val now = ((frame as? ChargingFrame)?.veil ?: 0f) * exit
        if (now == shown) return
        shown = now
        onShown?.invoke(now)
    }

    private fun setStatus(status: ChargingStatus) {
        level = status.level
        label = context.getString(status.label)
    }

    /** Played out: an Edge glow in the wallpaper settles to the faint glow. */
    private fun finish() = end(faint = style == ChargingStyle.EDGE_GLOW && stayFaint)

    private fun end(faint: Boolean) {
        val played = isPlaying
        if (faint) showFaint() else clear()
        if (played) onDone?.invoke()
    }

    private fun stopAnimators() {
        removeCallbacks(stillDone)
        animator?.let {
            animator = null
            it.cancel()
        }
        exitAnimator?.let {
            exitAnimator = null
            it.cancel()
        }
    }

    private fun accentNow(): Int = accentFrom?.let { ColorUtils.blendARGB(it, accentColor, accentFade) } ?: accentColor

    private fun fadeAccent(from: Int) {
        accentAnimator?.cancel()
        accentFrom = from
        accentFade = 0f
        accentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ACCENT_FADE_MS
            addUpdateListener {
                accentFade = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (accentAnimator !== animation) return
                    accentAnimator = null
                    accentFrom = null
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimators()
        accentAnimator?.cancel()
        accentAnimator = null
        accentFrom = null
        dropGlow()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dropGlow()
    }

    override fun onDraw(canvas: Canvas) {
        when (val f = frame) {
            is ChargingFrame -> drawRing(canvas, f)
            is GlowFrame -> drawGlow(canvas, f)
            is MinimalFrame -> drawMinimal(canvas, f)
            null -> {}
        }
    }

    private fun drawRing(canvas: Canvas, f: ChargingFrame) {
        val veil = f.veil * exit
        if (veil > 0f) canvas.drawColor(withAlpha(veilColor, VEIL_OPACITY * veil))
        val alpha = f.content * exit
        val u = (height - paddingTop - paddingBottom).toFloat()
        if (alpha <= 0f || u <= 0f) return
        val accent = accentNow()
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val top = paddingTop.toFloat()
        val cy = top + RING_Y * u
        val r = RING_RADIUS * u

        canvas.save()
        canvas.scale(f.scale, f.scale, cx, top + u / 2)

        // The ring: a faint track, filled clockwise from the top as far as the level.
        ring.strokeWidth = RING_STROKE * u
        ring.color = withAlpha(accent, TRACK_OPACITY * alpha)
        canvas.drawCircle(cx, cy, r, ring)
        if (f.ring > 0f) {
            ring.color = withAlpha(accent, alpha)
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, -90f, 360f * f.ring, false, ring)
        }

        drawBolt(canvas, cx, cy + BOLT_Y * u, BOLT_HEIGHT * u, accent, f.boltTrace, f.boltFill, alpha)

        // The number, with a smaller percent sign after it, centred together.
        number.textSize = NUMBER_SIZE * u
        percent.textSize = NUMBER_SIZE * PERCENT_SCALE * u
        number.color = withAlpha(textColor, alpha)
        number.clearShadowLayer()
        percent.color = number.color
        percent.clearShadowLayer()
        val digits = f.percent.toString()
        val digitsWidth = number.measureText(digits)
        val x = cx - (digitsWidth + percent.measureText(PERCENT)) / 2
        val baseline = cy + NUMBER_BASELINE * u
        canvas.drawText(digits, x, baseline, number)
        canvas.drawText(PERCENT, x + digitsWidth, baseline, percent)

        // "Charging", rising into place under the ring as it appears.
        if (f.label > 0f) {
            labelPaint.textSize = LABEL_SIZE * u
            labelPaint.color = withAlpha(textColor, LABEL_OPACITY * alpha * f.label)
            canvas.drawText(label, cx, cy + r + (LABEL_GAP + (1 - f.label) * LABEL_RISE) * u, labelPaint)
        }
        canvas.restore()
    }

    /**
     * The Edge glow: the soft glow round the whole edge, the brighter part as far as the level
     * (the same glow, drawn again along that much of the edge), and a bright head at its end.
     */
    private fun drawGlow(canvas: Canvas, f: GlowFrame) {
        val bitmap = glowBitmap() ?: return
        val edge = edge ?: return
        val color = accentNow()
        if (glowPaint.colorFilter == null || glowFilterColor != color) {
            // The glow is drawn in white; this tints it, keeping its softness.
            val filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            glowPaint.colorFilter = filter
            bandPaint.colorFilter = filter
            glowFilterColor = color
        }
        if (f.edge > 0f) {
            glowPaint.alpha = alpha255(f.edge * exit)
            canvas.drawBitmap(bitmap, 0f, 0f, glowPaint)
        }
        if (f.fill > 0f && f.filled > 0f) {
            segment.reset()
            edgeMeasure.setPath(edgePath, false)
            edgeMeasure.getSegment(0f, edgeMeasure.length * f.filled.coerceAtMost(1f), segment, true)
            bandPaint.shader = glowShader
            bandPaint.strokeWidth = BAND_WIDTH * height
            bandPaint.alpha = alpha255(f.fill * exit)
            canvas.drawPath(segment, bandPaint)
        }
        if (f.head > 0f) {
            val p = edge.pointAt(f.filled.coerceAtMost(0.9999f))
            val r = HEAD_RADIUS * height
            val core = ColorUtils.blendARGB(color, Color.WHITE, HEAD_WHITENESS)
            headPaint.shader = RadialGradient(p.x, p.y, r, withAlpha(core, f.head * exit), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(p.x, p.y, r, headPaint)
        }
    }

    /** The glow along the whole edge, in white, made once for this size and kept. */
    private fun glowBitmap(): Bitmap? {
        glow?.let { return it }
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val outline = EdgePath(w.toFloat(), h.toFloat(), cornerShare * h)
        edge = outline
        edgePath.reset()
        outline.addTo(edgePath)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        // Only inside the glass: half of each stroke would be outside it.
        c.clipPath(edgePath)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
        }
        // A wide soft glow, then a thin brighter line along the very edge.
        paint.strokeWidth = 2 * GLOW_DEPTH * h
        paint.maskFilter = BlurMaskFilter(GLOW_BLUR * h, BlurMaskFilter.Blur.NORMAL)
        paint.alpha = GLOW_OUTER_ALPHA
        c.drawPath(edgePath, paint)
        paint.strokeWidth = 2 * CORE_DEPTH * h
        paint.maskFilter = BlurMaskFilter(CORE_BLUR * h, BlurMaskFilter.Blur.NORMAL)
        paint.alpha = 255
        c.drawPath(edgePath, paint)
        glow = bitmap
        glowShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        return bitmap
    }

    private fun dropGlow() {
        glow?.recycle()
        glow = null
        glowShader = null
        bandPaint.shader = null
    }

    /** Minimal: a bolt and the number, just below the clock, rising into place as it appears. */
    private fun drawMinimal(canvas: Canvas, f: MinimalFrame) {
        val alpha = f.content * exit
        val u = (height - paddingTop - paddingBottom).toFloat()
        if (alpha <= 0f || u <= 0f) return
        number.textSize = MINI_NUMBER * u
        percent.textSize = MINI_NUMBER * PERCENT_SCALE * u
        val boltHeight = MINI_BOLT * u
        val boltWidth = boltHeight * BOLT_WIDTH_SHARE
        val gap = MINI_GAP * u
        // Laid out for the final number, so it doesn't shift as it counts.
        val textWidth = number.measureText(level.toString()) + percent.measureText(PERCENT)
        val contentWidth = boltWidth + gap + textWidth
        val digitsHeight = -number.fontMetrics.ascent * DIGIT_HEIGHT_SHARE
        val contentHeight = max(boltHeight, digitsHeight)
        val (left, top) = minimalPlace(contentWidth, contentHeight)
        val y = top + (1 - f.rise) * MINI_RISE * u

        // No veil behind it, so a shadow keeps it readable, as the clock's does.
        val shadow = if (ColorUtils.calculateLuminance(textColor) > 0.4) 0x99000000.toInt() else 0x66FFFFFF
        val shadowAlpha = withAlpha(shadow, alpha)
        val accent = accentNow()
        boltFill.setShadowLayer(0.02f * u, 0f, 0.005f * u, shadowAlpha)
        drawBolt(canvas, left + boltWidth / 2, y + contentHeight / 2, boltHeight, accent, 0f, 1f, alpha)
        boltFill.clearShadowLayer()

        number.color = withAlpha(textColor, alpha)
        number.setShadowLayer(0.02f * u, 0f, 0.005f * u, shadowAlpha)
        percent.color = number.color
        percent.setShadowLayer(0.02f * u, 0f, 0.005f * u, shadowAlpha)
        val digits = f.percent.toString()
        val x = left + boltWidth + gap
        val baseline = y + (contentHeight + digitsHeight) / 2
        canvas.drawText(digits, x, baseline, number)
        canvas.drawText(PERCENT, x + number.measureText(digits), baseline, percent)
    }

    /**
     * Where Minimal goes, as its top left: centred in a pop-over; with the clock off, where the
     * clock would be; otherwise by the clock ([MinimalPlace]).
     */
    private fun minimalPlace(w: Float, h: Float): Pair<Float, Float> {
        val inner = RectF(paddingLeft.toFloat(), paddingTop.toFloat(), (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        val clock = clock
        if (centred || clock == null) return inner.centerX() - w / 2 to inner.centerY() - h / 2
        // The space the clock itself keeps to, worked out as it does, in whole pixels.
        val margin = (ClockLayer.MARGIN * height).toInt().toFloat()
        val area = Box(inner.left + margin, inner.top + margin, max(inner.left + margin, inner.right - margin), max(inner.top + margin, inner.bottom - margin))
        val settings = clock.settings
        if (clock.visibility != VISIBLE || !clock.boxBounds(box)) {
            return area.left + max(0f, area.width - w) * settings.x to area.top + max(0f, area.height - h) * settings.y
        }
        val clockBox = Box(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
        return MinimalPlace.place(area, clockBox, settings.x, w, h, MINI_CLOCK_GAP * inner.height())
    }

    /**
     * The bolt, [height] tall and centred on ([x], [y]), in [color]: its outline traces in as far
     * as [trace], and it fills as solid as [fill].
     */
    private fun drawBolt(canvas: Canvas, x: Float, y: Float, height: Float, color: Int, trace: Float, fill: Float, alpha: Float) {
        val scale = height / BOLT_SIZE
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        canvas.translate(-BOLT_CENTRE_X, -BOLT_CENTRE_Y)
        if (fill > 0f) {
            boltFill.color = withAlpha(color, alpha * fill)
            canvas.drawPath(BOLT, boltFill)
        }
        if (trace > 0f) {
            traced.reset()
            boltMeasure.getSegment(0f, boltLength * trace, traced, true)
            boltLine.strokeWidth = BOLT_LINE
            boltLine.color = withAlpha(color, alpha)
            canvas.drawPath(traced, boltLine)
        }
        canvas.restore()
    }

    private fun withAlpha(color: Int, opacity: Float) =
        ColorUtils.setAlphaComponent(color, (Color.alpha(color) * opacity.coerceIn(0f, 1f)).roundToInt())

    private fun alpha255(opacity: Float) = (255 * opacity.coerceIn(0f, 1f)).roundToInt()

    companion object {
        /** The 17 Pro Max's back screen: 101 px corners on a 596 px height (first check 5, Phase 9). */
        const val DEFAULT_CORNER_SHARE = 101f / 596f

        private const val STILL_MS = 3000L
        private const val STOP_FADE_MS = 250L
        private const val STILL_FADE_MS = 300L
        private const val ACCENT_FADE_MS = 600L
        private const val PERCENT = "%"

        // Sizes and places, as shares of the height inside the padding. The ring, bolt, number
        // and label are centred together, the bolt and number inside the ring.
        private const val VEIL_OPACITY = 0.6f
        private const val RING_Y = 0.44f
        private const val RING_RADIUS = 0.24f
        private const val RING_STROKE = 0.032f
        private const val TRACK_OPACITY = 0.22f
        private const val BOLT_Y = -0.072f
        private const val BOLT_HEIGHT = 0.10f
        private const val NUMBER_SIZE = 0.135f
        private const val NUMBER_BASELINE = 0.112f
        private const val PERCENT_SCALE = 0.55f
        private const val LABEL_SIZE = 0.062f
        private const val LABEL_GAP = 0.125f
        private const val LABEL_RISE = 0.025f
        private const val LABEL_OPACITY = 0.85f

        // The Edge glow, as shares of the whole height: how far in the soft glow and the bright
        // line reach, and how soft each is; the brighter part's band, and the head.
        private const val GLOW_DEPTH = 0.05f
        private const val GLOW_BLUR = 0.05f
        private const val GLOW_OUTER_ALPHA = 170
        private const val CORE_DEPTH = 0.008f
        private const val CORE_BLUR = 0.01f
        private const val BAND_WIDTH = 0.36f
        private const val HEAD_RADIUS = 0.1f
        private const val HEAD_WHITENESS = 0.55f

        // Minimal, as shares of the height inside the padding.
        private const val MINI_BOLT = 0.1f
        private const val MINI_NUMBER = 0.11f
        private const val MINI_GAP = 0.02f
        private const val MINI_RISE = 0.04f
        private const val MINI_CLOCK_GAP = 0.03f

        // How tall digits are against the font's ascent.
        private const val DIGIT_HEIGHT_SHARE = 0.77f

        // A lightning bolt on a 24-unit grid, 20 units tall and 16 wide, and its outline's width there.
        private const val BOLT_SIZE = 20f
        private const val BOLT_WIDTH_SHARE = 16f / 20f
        private const val BOLT_LINE = 1.8f
        private const val BOLT_CENTRE_X = 12f
        private const val BOLT_CENTRE_Y = 12f
        private val BOLT = Path().apply {
            moveTo(13f, 2f)
            lineTo(4f, 14f)
            lineTo(11f, 14f)
            lineTo(10f, 22f)
            lineTo(20f, 10f)
            lineTo(13f, 10f)
            close()
        }
    }
}

/** The outline as an Android path, in the same order as [EdgePath.pointAt]: clockwise from the top edge's left end. */
private fun EdgePath.addTo(path: Path) {
    val r = radius
    val d = 2 * r
    path.moveTo(r, 0f)
    path.lineTo(width - r, 0f)
    path.arcTo(RectF(width - d, 0f, width, d), -90f, 90f)
    path.lineTo(width, height - r)
    path.arcTo(RectF(width - d, height - d, width, height), 0f, 90f)
    path.lineTo(r, height)
    path.arcTo(RectF(0f, height - d, d, height), 90f, 90f)
    path.lineTo(0f, r)
    path.arcTo(RectF(0f, 0f, d, d), 180f, 90f)
    path.close()
}
