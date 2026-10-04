package com.backscreen.wallpaper.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.max

/**
 * The Camera tab's preview of the swipe: a fingertip that slides right to left across the
 * middle of the back screen, leaving a fading trail, then rests and goes again. It starts just
 * left of Xiaomi's back strip, as a swipe must. White with a soft shadow, so it shows on any
 * wallpaper. With the phone's animations off, it shows the swipe still.
 */
class SwipeHint @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Plays while the tab can be seen. */
    var playing = false
        set(value) {
            field = value
            update()
        }

    private var progress = STILL
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CYCLE_MS
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }
    private val glide = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val finger = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // The trail fades in from where the swipe started to the finger: one gradient from 0 to 1,
    // stretched to fit each frame, so nothing is made while drawing.
    private val fade = LinearGradient(0f, 0f, 1f, 0f, Color.TRANSPARENT, Color.WHITE, Shader.TileMode.CLAMP)
    private val stretch = Matrix()
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        shader = fade
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        update()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        update()
    }

    private fun update() {
        val animate = playing && isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()
        if (animate && !animator.isStarted) {
            animator.start()
        } else if (!animate) {
            animator.cancel()
            progress = STILL
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val t = progress * CYCLE_MS
        // Fade in where it starts, glide left, fade out where it ends, then rest.
        val alpha: Float
        val along: Float
        when {
            t < FADE_MS -> {
                alpha = t / FADE_MS
                along = 0f
            }
            t < FADE_MS + GLIDE_MS -> {
                alpha = 1f
                along = glide.getInterpolation((t - FADE_MS) / GLIDE_MS)
            }
            t < 2 * FADE_MS + GLIDE_MS -> {
                alpha = 1f - (t - FADE_MS - GLIDE_MS) / FADE_MS
                along = 1f
            }
            else -> return
        }
        val r = height * FINGER_RADIUS
        val y = height / 2f
        val startX = width * START_X
        val x = startX + (width * END_X - startX) * along
        if (along > 0f) {
            trail.strokeWidth = r * 1.1f
            trail.alpha = (alpha * TRAIL_ALPHA).toInt()
            stretch.setScale(x - startX, 1f)
            stretch.postTranslate(startX, 0f)
            fade.setLocalMatrix(stretch)
            canvas.drawLine(startX, y, x, y, trail)
        }
        finger.alpha = (alpha * FINGER_ALPHA).toInt()
        finger.setShadowLayer(max(1f, r * 0.5f), 0f, r * 0.1f, Color.argb((alpha * SHADOW_ALPHA).toInt(), 0, 0, 0))
        canvas.drawCircle(x, y, r, finger)
    }

    private companion object {
        const val CYCLE_MS = 2600L
        const val FADE_MS = 220f
        const val GLIDE_MS = 650f

        // Where the finger starts and ends, across the width: just left of Xiaomi's back strip
        // (x 914 of 976), and a little past the 30% a swipe must travel.
        const val START_X = 0.86f
        const val END_X = 0.38f
        const val FINGER_RADIUS = 0.075f
        const val FINGER_ALPHA = 235
        const val TRAIL_ALPHA = 150
        const val SHADOW_ALPHA = 110

        // With animations off: the finger at the end of the swipe, with its trail.
        const val STILL = (FADE_MS + GLIDE_MS) / CYCLE_MS
    }
}
