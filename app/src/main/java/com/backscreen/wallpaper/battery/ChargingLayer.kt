package com.backscreen.wallpaper.battery

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * The charging animation, over the wallpaper ([ChargingTimeline] says what shows when): the
 * wallpaper dims under a veil, a bolt draws in, a ring fills to the battery's level as the
 * number counts up, with "Charging" or "Wireless charging" under it, then it all fades away.
 *
 * Drawn on a Canvas, with no image assets, and sized as a share of the layer's height, so the
 * app's small preview looks just like the back screen. Padding keeps it clear of the camera,
 * as the clock does; the veil covers everything.
 */
class ChargingLayer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Called when an animation ends: played out, stopped or cancelled. */
    var onDone: (() -> Unit)? = null

    /**
     * Called with how much of it shows, 0 to 1, as that changes. The clock fades out by as much:
     * it would show through the veil and crowd the ring.
     */
    var onShown: ((Float) -> Unit)? = null
    private var shown = 0f

    /** Whether an animation is running, rather than nothing or the still. */
    var isPlaying = false
        private set

    private var level = 0
    private var label = ""
    private var frame: ChargingFrame? = null

    // Fades everything out early when you unplug, and the still in after a play.
    private var exit = 1f

    private var textColor = Color.WHITE
    private var veilColor = Color.BLACK

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

    /** The text and veil colours: the clock's, so it matches the wallpaper. */
    fun setColors(text: Int, veil: Int) {
        if (text == textColor && veil == veilColor) return
        textColor = text
        veilColor = veil
        invalidate()
    }

    /** Plays it from the start for [status], replacing one that's running. */
    fun play(status: ChargingStatus) {
        clear()
        setStatus(status)
        isPlaying = true
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // The phone's animations are off: show it still for a while instead.
            frame = ChargingTimeline.still(level)
            changed()
            postDelayed(stillDone, STILL_MS)
            return
        }
        frame = ChargingTimeline.frame(0, level)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ChargingTimeline.DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                frame = ChargingTimeline.frame(it.currentPlayTime, level)
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

    /** You unplugged: a running animation fades away now, rather than saying "Charging". */
    fun stop() {
        if (!isPlaying || exitAnimator != null) return
        removeCallbacks(stillDone)
        exitAnimator = ValueAnimator.ofFloat(exit, 0f).apply {
            duration = STOP_FADE_MS
            addUpdateListener {
                exit = it.animatedValue as Float
                changed()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (exitAnimator === animation) finish()
                }
            })
            start()
        }
    }

    /** Ends a running animation at once: the back screen dimmed, and would keep a half-faded frame. */
    fun cancel() {
        if (isPlaying) finish()
    }

    /** Everything in place and still, for the app's preview at rest; [fadeIn] to ease it in. */
    fun showStill(status: ChargingStatus, fadeIn: Boolean = false) {
        clear()
        setStatus(status)
        frame = ChargingTimeline.still(level)
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
        frame = null
        exit = 1f
        changed()
    }

    /** The frame or the fade moved on: draw it, and say how much shows. */
    private fun changed() {
        invalidate()
        val now = (frame?.veil ?: 0f) * exit
        if (now == shown) return
        shown = now
        onShown?.invoke(now)
    }

    private fun setStatus(status: ChargingStatus) {
        level = status.level
        label = context.getString(status.label)
    }

    private fun finish() {
        clear()
        onDone?.invoke()
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

    override fun onDetachedFromWindow() {
        stopAnimators()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return
        val veil = f.veil * exit
        if (veil > 0f) canvas.drawColor(withAlpha(veilColor, VEIL_OPACITY * veil))
        val alpha = f.content * exit
        val u = (height - paddingTop - paddingBottom).toFloat()
        if (alpha <= 0f || u <= 0f) return
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val top = paddingTop.toFloat()
        val cy = top + RING_Y * u
        val r = RING_RADIUS * u

        canvas.save()
        canvas.scale(f.scale, f.scale, cx, top + u / 2)

        // The ring: a faint track, filled clockwise from the top as far as the level.
        ring.strokeWidth = RING_STROKE * u
        ring.color = withAlpha(textColor, TRACK_OPACITY * alpha)
        canvas.drawCircle(cx, cy, r, ring)
        if (f.ring > 0f) {
            ring.color = withAlpha(textColor, alpha)
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, -90f, 360f * f.ring, false, ring)
        }

        drawBolt(canvas, cx, cy + BOLT_Y * u, BOLT_HEIGHT * u, f, alpha)

        // The number, with a smaller percent sign after it, centred together.
        number.textSize = NUMBER_SIZE * u
        percent.textSize = NUMBER_SIZE * PERCENT_SCALE * u
        number.color = withAlpha(textColor, alpha)
        percent.color = number.color
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

    /** The bolt, [height] tall and centred on ([x], [y]): its outline traces in, then it fills. */
    private fun drawBolt(canvas: Canvas, x: Float, y: Float, height: Float, f: ChargingFrame, alpha: Float) {
        val scale = height / BOLT_SIZE
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        canvas.translate(-BOLT_CENTRE_X, -BOLT_CENTRE_Y)
        if (f.boltFill > 0f) {
            boltFill.color = withAlpha(textColor, alpha * f.boltFill)
            canvas.drawPath(BOLT, boltFill)
        }
        if (f.boltTrace > 0f) {
            traced.reset()
            boltMeasure.getSegment(0f, boltLength * f.boltTrace, traced, true)
            boltLine.strokeWidth = BOLT_LINE
            boltLine.color = withAlpha(textColor, alpha)
            canvas.drawPath(traced, boltLine)
        }
        canvas.restore()
    }

    private fun withAlpha(color: Int, opacity: Float) =
        ColorUtils.setAlphaComponent(color, (Color.alpha(color) * opacity.coerceIn(0f, 1f)).roundToInt())

    private companion object {
        const val STILL_MS = 3000L
        const val STOP_FADE_MS = 250L
        const val STILL_FADE_MS = 300L
        const val PERCENT = "%"

        // Sizes and places, as shares of the height inside the padding. The ring, bolt, number
        // and label are centred together, the bolt and number inside the ring.
        const val VEIL_OPACITY = 0.6f
        const val RING_Y = 0.44f
        const val RING_RADIUS = 0.24f
        const val RING_STROKE = 0.032f
        const val TRACK_OPACITY = 0.22f
        const val BOLT_Y = -0.072f
        const val BOLT_HEIGHT = 0.10f
        const val NUMBER_SIZE = 0.135f
        const val NUMBER_BASELINE = 0.112f
        const val PERCENT_SCALE = 0.55f
        const val LABEL_SIZE = 0.062f
        const val LABEL_GAP = 0.125f
        const val LABEL_RISE = 0.025f
        const val LABEL_OPACITY = 0.85f

        // A lightning bolt on a 24-unit grid, 20 units tall, and its outline's width there.
        const val BOLT_SIZE = 20f
        const val BOLT_LINE = 1.8f
        const val BOLT_CENTRE_X = 12f
        const val BOLT_CENTRE_Y = 12f
        val BOLT = Path().apply {
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
