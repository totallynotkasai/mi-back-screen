package com.backscreen.wallpaper.battery

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** A point on the back screen, in its pixels. */
data class EdgePoint(val x: Float, val y: Float)

/**
 * The back screen's outline: a rectangle [width] x [height] with every corner rounded to
 * [radius] (101 px on the 17 Pro Max's 976 x 596, from the display's own rounded corners; first
 * check 5, Phase 9). The picture shows round the camera lenses, so the outline runs round the
 * camera side too. The Edge glow follows it, clockwise from the top edge's left end, so a share
 * of the way round can stand for the battery's level. Plain Kotlin, so it's unit tested.
 */
class EdgePath(val width: Float, val height: Float, radius: Float) {
    val radius = radius.coerceIn(0f, min(width, height) / 2)

    private val across = width - 2 * this.radius
    private val down = height - 2 * this.radius
    private val corner = (PI / 2 * this.radius).toFloat()

    /** All the way round. */
    val length = 2 * across + 2 * down + 4 * corner

    /** The point [share] of the way round, clockwise from the top edge's left end; past 1 it goes round again. */
    fun pointAt(share: Float): EdgePoint {
        var d = (share - floor(share)) * length
        val r = radius
        // Top, top-right corner, right, bottom-right, bottom, bottom-left, left, top-left.
        if (d <= across) return EdgePoint(r + d, 0f)
        d -= across
        if (d <= corner) return onCorner(width - r, r, -90.0, d)
        d -= corner
        if (d <= down) return EdgePoint(width, r + d)
        d -= down
        if (d <= corner) return onCorner(width - r, height - r, 0.0, d)
        d -= corner
        if (d <= across) return EdgePoint(width - r - d, height)
        d -= across
        if (d <= corner) return onCorner(r, height - r, 90.0, d)
        d -= corner
        if (d <= down) return EdgePoint(0f, height - r - d)
        d -= down
        return onCorner(r, r, 180.0, d.coerceAtMost(corner))
    }

    /** [along] the corner centred on ([cx], [cy]) that starts at [startDegrees], clockwise. */
    private fun onCorner(cx: Float, cy: Float, startDegrees: Double, along: Float): EdgePoint {
        if (radius == 0f) return EdgePoint(cx, cy)
        val angle = Math.toRadians(startDegrees) + along / radius
        return EdgePoint((cx + radius * cos(angle)).toFloat(), (cy + radius * sin(angle)).toFloat())
    }
}
