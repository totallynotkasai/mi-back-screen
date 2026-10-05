package com.backscreen.wallpaper.battery

import kotlin.math.max

/** A rectangle, in pixels: plain Kotlin, so [MinimalPlace] is unit tested. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centreX get() = (left + right) / 2
    val centreY get() = (top + bottom) / 2

    fun contains(other: Box, slack: Float = 0f) =
        other.left >= left - slack && other.top >= top - slack && other.right <= right + slack && other.bottom <= bottom + slack

    fun overlaps(other: Box) = other.left < right && other.right > left && other.top < bottom && other.bottom > top
}

/**
 * Where the charging animation's Minimal style goes beside the clock. Clocks come in five styles,
 * anywhere on the back screen (a Stacked clock at the side fills its height), so rather than
 * assume a place, it tries them in turn and takes the first that fits inside [area] (the space the
 * clock itself keeps to) without touching the clock:
 *
 * 1. just below the clock, lined up with it (left, centre or right, as the clock is);
 * 2. just above it;
 * 3. beside it, level with its last line (the date), on the side with more room;
 * 4. beside it, level with its first line;
 * 5. the corner of [area] farthest from the clock.
 */
object MinimalPlace {

    // A pixel's rounding between the clock's layout and this.
    private const val SLACK = 1f

    /**
     * The top left of a [width] x [height] Minimal by the clock's [clock] box, [gap] apart.
     * [clockX] is the clock's place across, 0 (left) to 1 (right).
     */
    fun place(area: Box, clock: Box, clockX: Float, width: Float, height: Float, gap: Float): Pair<Float, Float> {
        val lined = when {
            clockX < 1 / 3f -> clock.left
            clockX > 2 / 3f -> clock.right - width
            else -> clock.centreX - width / 2
        }.coerceIn(area.left, max(area.left, area.right - width))
        val side = if (clock.left - area.left > area.right - clock.right) clock.left - gap - width else clock.right + gap
        val places = listOf(
            lined to clock.bottom + gap,
            lined to clock.top - gap - height,
            side to clock.bottom - height,
            side to clock.top,
        )
        for ((x, y) in places) {
            val box = Box(x, y, x + width, y + height)
            if (area.contains(box, SLACK) && !clock.overlaps(box)) return keepInside(area, x, y, width, height)
        }
        val x = if (clock.centreX > area.centreX) area.left else area.right - width
        val y = if (clock.centreY > area.centreY) area.top else area.bottom - height
        return keepInside(area, x, y, width, height)
    }

    /** Pulled back inside [area] by the slack it was allowed. */
    private fun keepInside(area: Box, x: Float, y: Float, width: Float, height: Float) =
        x.coerceIn(area.left, max(area.left, area.right - width)) to y.coerceIn(area.top, max(area.top, area.bottom - height))
}
