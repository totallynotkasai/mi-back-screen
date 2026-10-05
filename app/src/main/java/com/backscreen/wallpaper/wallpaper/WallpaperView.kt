package com.backscreen.wallpaper.wallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.ImageView
import com.backscreen.wallpaper.core.BackScreen
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One wallpaper image, fitted the way [scaling] says, or panning across it at [pan]. The app's
 * preview is smaller than the back screen, so it sets [ratio] (preview size / back screen size)
 * for [Scaling.NONE] to show the image at the size it will really be. A pan's speed is a share of
 * the view's height, so the preview pans in step with the back screen without it.
 *
 * It only moves (the pan, and a GIF's animation) while [moving], which its owner turns off
 * whenever it can't be seen. Even then it's redrawn only when the pan reaches a new whole pixel:
 * about 12 times a second at Slow, rather than every frame.
 *
 * A plain ImageView on purpose: the back screen's window uses a platform theme, where an
 * AppCompat view logs an error each time it's created, and wallpapers never need tinting.
 */
@SuppressLint("AppCompatCustomView")
class WallpaperView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    var scaling = Scaling.FILL
        set(value) {
            field = value
            updateScaleType()
            updateMatrix()
        }

    var ratio = 1f
        set(value) {
            if (field == value) return
            field = value
            updateMatrix()
        }

    /** Pan across the image at this speed instead of [scaling]; null for no panning. */
    var pan: PanSpeed? = null
        set(value) {
            if (field == value) return
            field = value
            updateScaleType()
            replan(restart = false)
        }

    /**
     * Images that would pan less than this share of the screen stay still and centred instead
     * (Skip short pans); 0 pans them all.
     */
    var minTravel = 0.0
        set(value) {
            if (field == value) return
            field = value
            replan(restart = false)
        }

    /** Whether the pan and a GIF play. Off while it can't be seen, which saves battery. */
    var moving = false
        set(value) {
            if (field == value) return
            if (value) {
                runningSince = SystemClock.uptimeMillis()
                BackScreen.start(drawable)
            } else {
                panned = panTime()
                runningSince = NOT_RUNNING
                BackScreen.stop(drawable)
            }
            field = value
            scheduleTick()
        }

    /** Called every few seconds while it pans, as the part of the image under the clock changes. */
    var onPanned: (() -> Unit)? = null

    // What panning does with this image, once it's laid out; null while panning is off.
    private var plan: PanPlan? = null

    // The pan's time: what it had panned before this run, plus this run.
    private var panned = 0L
    private var runningSince = NOT_RUNNING
    private var drawnOffset = 0
    private var lastPannedCall = 0L
    private val panMatrix = Matrix()
    private val tick = Runnable { onTick() }

    init {
        scaleType = Scaling.FILL.scaleType
        // Padding keeps the image clear of the camera; don't draw into it.
        cropToPadding = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        // Called from ImageView's constructor, before this class has set its fields.
        @Suppress("SENSELESS_COMPARISON")
        if (panMatrix == null) return super.setImageDrawable(drawable)
        BackScreen.stop(this.drawable)
        super.setImageDrawable(drawable)
        if (moving) BackScreen.start(drawable)
        // A new image pans from the start.
        replan(restart = true)
    }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        // A new size or camera padding: same place in the pan, new distances.
        replan(restart = false)
        return changed
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun updateScaleType() {
        scaleType = if (pan != null) ScaleType.MATRIX else scaling.scaleType
    }

    /**
     * Works the pan out again for the image, size and speed. With [restart] it starts from the
     * beginning; otherwise it carries on from the same point.
     */
    private fun replan(restart: Boolean) {
        val speed = pan
        val d = drawable
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val new = if (speed == null || d == null || w <= 0 || h <= 0) null
        else PanPlanner.plan(d.intrinsicWidth, d.intrinsicHeight, w, h, height, speed, minTravel)
        val old = plan
        if (new == old && !restart) return updateMatrix()
        val time = panTime()
        plan = new
        panned = if (restart || new == null) 0 else new.matching(old, time)
        if (runningSince != NOT_RUNNING) runningSince = SystemClock.uptimeMillis()
        if (new != null && new != old) {
            BackScreen.trace(
                if (new.skipped) "Pan: skipped, only ${(new.travel * 100).roundToInt()}%"
                else "Pan: ${new.axis}, ${new.overflow.roundToInt()} px, ${new.sweepMs} ms each way"
            )
        }
        updateMatrix()
        scheduleTick()
        // The same image, somewhere else (skipped and centred, or panning again): the clock looks again.
        if (!restart && old != null && new != null) onPanned?.invoke()
    }

    private fun panTime(): Long =
        panned + if (runningSince == NOT_RUNNING) 0 else SystemClock.uptimeMillis() - runningSince

    /** The pan moved on: redraw if it reached a new pixel, then wait for the next. */
    private fun onTick() {
        val plan = plan ?: return
        if (plan.offsetAt(panTime()).roundToInt() != drawnOffset) updateMatrix()
        val now = SystemClock.uptimeMillis()
        if (now - lastPannedCall >= RESAMPLE_MS) {
            lastPannedCall = now
            onPanned?.invoke()
        }
        scheduleTick()
    }

    private fun scheduleTick() {
        removeCallbacks(tick)
        val plan = plan ?: return
        if (!moving || !plan.moves || !isAttachedToWindow) return
        val time = panTime()
        // On a frame, so each new pixel shows as soon as it's due.
        postOnAnimationDelayed(tick, max(1, plan.nextMoveAt(time) - time))
    }

    /** Panning: cover scale, at the pan's offset. [Scaling.NONE]: its own size, times [ratio], centred. */
    private fun updateMatrix() {
        // Called from ImageView's constructor, before this class has set its fields.
        @Suppress("SENSELESS_COMPARISON")
        if (scaling == null || panMatrix == null) return
        val d = drawable ?: return
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val plan = plan
        if (plan != null) {
            val offset = plan.offsetAt(panTime()).roundToInt()
            drawnOffset = offset
            // Whole pixels, so the image stays sharp as it moves.
            val dx = ((w - d.intrinsicWidth * plan.scale) / 2).roundToInt().toFloat()
            val dy = ((h - d.intrinsicHeight * plan.scale) / 2).roundToInt().toFloat()
            panMatrix.setScale(plan.scale, plan.scale)
            when (plan.axis) {
                PanAxis.HORIZONTAL -> panMatrix.postTranslate(-offset.toFloat(), dy)
                PanAxis.VERTICAL -> panMatrix.postTranslate(dx, -offset.toFloat())
                PanAxis.NONE -> panMatrix.postTranslate(dx, dy)
            }
            imageMatrix = panMatrix
            return
        }
        if (pan != null || scaling != Scaling.NONE) return
        imageMatrix = Matrix().apply {
            setScale(ratio, ratio)
            postTranslate((w - d.intrinsicWidth * ratio) / 2f, (h - d.intrinsicHeight * ratio) / 2f)
        }
    }

    private companion object {
        const val NOT_RUNNING = -1L

        /** How often the clock's automatic colours are checked while it pans. */
        const val RESAMPLE_MS = 3000L
    }
}
