package com.backscreen.wallpaper.wallpaper

import com.backscreen.wallpaper.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** How fast panning glides: a share of the screen's height per second. */
enum class PanSpeed(val label: Int, val perSecond: Double) {
    SLOW(R.string.pan_slow, 0.02),
    MEDIUM(R.string.pan_medium, 0.05),
    FAST(R.string.pan_fast, 0.10),
}

/** Which way an image pans: along the side that overflows the screen. */
enum class PanAxis { NONE, HORIZONTAL, VERTICAL }

/**
 * One image's pan. Scaled to cover the area, as with Fill, it holds at the start, glides along
 * its overflowing side to the far end, holds, and glides back, easing in and out at each end.
 *
 * Where it is depends only on how long it has been panning, so after a pause it carries on from
 * exactly where it was. Positions are in screen pixels: 0 shows the top or left edge, [overflow]
 * the bottom or right.
 *
 * A [skipped] pan would move too little to be worth it: it holds still in the middle.
 */
data class PanPlan(
    val axis: PanAxis,
    /** The scale that covers the area. */
    val scale: Float,
    /** How far it travels each way, in screen pixels. */
    val overflow: Float,
    /** One sweep, end to end, in ms; the holds come on top. */
    val sweepMs: Long,
    private val rampMs: Long,
    /** Gliding speed between the ramps, in pixels per ms. */
    private val speed: Double,
    /** How far it travels each way, as a share of the area along the pan: 0.09 is 9%. */
    val travel: Double = 0.0,
    /** Shorter than the minimum asked for, so it stays still, centred. */
    val skipped: Boolean = false,
) {
    /** Hold, there, hold, back. */
    val cycleMs = 2 * (HOLD_MS + sweepMs)

    /** Whether it moves at all. */
    val moves get() = axis != PanAxis.NONE && !skipped

    /** How far along it is after panning for [elapsed] ms. */
    fun offsetAt(elapsed: Long): Float {
        if (axis == PanAxis.NONE) return 0f
        if (skipped) return overflow / 2
        val t = elapsed.mod(cycleMs)
        return when {
            t < HOLD_MS -> 0f
            t < HOLD_MS + sweepMs -> travelled(t - HOLD_MS)
            t < 2 * HOLD_MS + sweepMs -> overflow
            else -> overflow - travelled(t - 2 * HOLD_MS - sweepMs)
        }
    }

    /**
     * When, after [elapsed], it next moves to a different whole pixel, so it's redrawn only
     * then. While it holds, that's the end of the hold.
     */
    fun nextMoveAt(elapsed: Long): Long {
        if (!moves) return Long.MAX_VALUE
        val base = elapsed - elapsed.mod(cycleMs)
        val t = elapsed - base
        val end = when {
            t < HOLD_MS -> return base + HOLD_MS
            t < HOLD_MS + sweepMs -> HOLD_MS + sweepMs
            t < 2 * HOLD_MS + sweepMs -> return base + 2 * HOLD_MS + sweepMs
            else -> cycleMs
        }
        // Within a sweep it only moves one way, so the first time it reaches another pixel can
        // be found by halving.
        val now = offsetAt(elapsed).roundToInt()
        if (offsetAt(base + end - 1).roundToInt() == now) return base + end
        var lo = t
        var hi = end - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (offsetAt(base + mid).roundToInt() == now) lo = mid else hi = mid
        }
        return base + hi
    }

    /**
     * The time in this pan at the same point as [otherElapsed] in [other], so a change of speed
     * or size carries on from where it was instead of starting again.
     */
    fun matching(other: PanPlan?, otherElapsed: Long): Long {
        if (other == null || other.axis != axis || !moves || !other.moves) return 0
        val t = otherElapsed.mod(other.cycleMs)
        return when {
            t < HOLD_MS -> t
            t < HOLD_MS + other.sweepMs -> HOLD_MS + timeToTravel(other.travelled(t - HOLD_MS) / other.overflow)
            t < 2 * HOLD_MS + other.sweepMs -> HOLD_MS + sweepMs + (t - HOLD_MS - other.sweepMs)
            else -> 2 * HOLD_MS + sweepMs + timeToTravel(other.travelled(t - 2 * HOLD_MS - other.sweepMs) / other.overflow)
        }
    }

    /**
     * How far one sweep has gone after [s] ms. The speed eases up to [speed] over [rampMs] with
     * a smoothstep curve, glides, and eases down the same way.
     */
    private fun travelled(s: Long): Float {
        val r = rampMs.toDouble()
        val d = when {
            s <= 0 -> 0.0
            s >= sweepMs -> overflow.toDouble()
            s < rampMs -> speed * r * eased(s / r)
            s > sweepMs - rampMs -> overflow - speed * r * eased((sweepMs - s) / r)
            else -> speed * r / 2 + speed * (s - r)
        }
        return d.toFloat().coerceIn(0f, overflow)
    }

    /** Within one sweep, how long it takes to cover [fraction] of the way. */
    private fun timeToTravel(fraction: Float): Long {
        val target = fraction * overflow
        var lo = 0L
        var hi = sweepMs
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (travelled(mid) < target) lo = mid else hi = mid
        }
        return hi
    }

    companion object {
        /** How long it rests at each end. */
        const val HOLD_MS = 2000L

        /** The longest it takes to get up to speed, or slow down. */
        const val RAMP_MS = 1500L

        /** Distance covered while easing up to speed over a ramp, as a share of speed x ramp. */
        private fun eased(x: Double) = x * x * x - x * x * x * x / 2
    }
}

/**
 * Works out how an image pans from its shape and the screen's. The direction comes from
 * comparing the two shapes, not just portrait against landscape: on the 976 x 596 back screen
 * (1.64:1), a 4:3 photo pans up and down, and a 16:9 one side to side.
 */
object PanPlanner {

    /** An image within this much of the area's shape already fits. */
    const val FITS_WITHIN = 0.03

    /**
     * The pan for an image of [imageWidth] x [imageHeight] covering an area of [areaWidth] x
     * [areaHeight] (inside any camera padding), on a screen [screenHeight] tall, at [speed]. One
     * that would travel less than [minTravel] of the area along the pan (0.15 is 15%) stays still
     * in the middle instead; 0 pans every image that doesn't fit.
     */
    fun plan(
        imageWidth: Int, imageHeight: Int, areaWidth: Int, areaHeight: Int, screenHeight: Int, speed: PanSpeed,
        minTravel: Double = 0.0,
    ): PanPlan {
        if (imageWidth <= 0 || imageHeight <= 0 || areaWidth <= 0 || areaHeight <= 0) return still(1f)
        val iw = imageWidth.toDouble()
        val ih = imageHeight.toDouble()
        val scale = max(areaWidth / iw, areaHeight / ih)
        val shape = (iw / ih) / (areaWidth.toDouble() / areaHeight)
        if (abs(shape - 1) <= FITS_WITHIN || abs(1 / shape - 1) <= FITS_WITHIN) return still(scale.toFloat())
        val axis = if (shape > 1) PanAxis.HORIZONTAL else PanAxis.VERTICAL
        val overflow = if (axis == PanAxis.HORIZONTAL) iw * scale - areaWidth else ih * scale - areaHeight
        val travel = overflow / if (axis == PanAxis.HORIZONTAL) areaWidth else areaHeight
        if (travel < minTravel) return PanPlan(axis, scale.toFloat(), overflow.toFloat(), 0, 1, 0.0, travel, skipped = true)
        val pxPerMs = speed.perSecond * max(1, screenHeight) / 1000
        val glideMs = overflow / pxPerMs
        // A short pan doesn't reach full speed: it eases up for half the way and down for the rest.
        val ramp = minOf(PanPlan.RAMP_MS.toDouble(), glideMs).roundToInt().toLong().coerceAtLeast(1)
        return PanPlan(axis, scale.toFloat(), overflow.toFloat(), (glideMs + ramp).roundToInt().toLong(), ramp, pxPerMs, travel)
    }

    private fun still(scale: Float) = PanPlan(PanAxis.NONE, scale, 0f, 0, 1, 0.0)
}
