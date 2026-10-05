package com.backscreen.wallpaper.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class EdgePathTest {

    // The 17 Pro Max's back screen: 976 x 596, every corner rounded to 101 px.
    private val edge = EdgePath(976f, 596f, 101f)

    private fun assertAt(x: Float, y: Float, p: EdgePoint) {
        assertEquals("x of $p", x, p.x, 0.01f)
        assertEquals("y of $p", y, p.y, 0.01f)
    }

    @Test
    fun itsTheRoundedRectanglesLength() {
        val straight = 2 * (976 - 202) + 2 * (596 - 202)
        assertEquals(straight + 2 * PI.toFloat() * 101, edge.length, 0.01f)
        // Corners bigger than the screen allows are kept to a half of its height.
        assertEquals(298f, EdgePath(976f, 596f, 400f).radius)
    }

    @Test
    fun itGoesClockwiseFromTheTopLeft() {
        assertAt(101f, 0f, edge.pointAt(0f))
        // Halfway round is the opposite end of the bottom edge.
        assertAt(875f, 596f, edge.pointAt(0.5f))
        // The top edge, then down the right side.
        val top = edge.pointAt(300f / edge.length)
        assertAt(401f, 0f, top)
        val right = edge.pointAt((774f + PI.toFloat() * 101 / 2 + 50f) / edge.length)
        assertAt(976f, 151f, right)
        // All the way round, and on again.
        assertAt(101f, 0f, edge.pointAt(1f))
        val quarter = edge.pointAt(0.25f)
        val again = edge.pointAt(1.25f)
        assertAt(quarter.x, quarter.y, again)
    }

    @Test
    fun theCornersFollowTheGlass() {
        // Every point on a corner is 101 px from that corner's centre, and inside the screen.
        val centres = listOf(875f to 101f, 875f to 495f, 101f to 495f, 101f to 101f)
        for (i in 0..1000) {
            val p = edge.pointAt(i / 1000f)
            assertTrue(p.x in -0.01f..976.01f && p.y in -0.01f..596.01f)
            val onCorner = (p.x < 101f || p.x > 875f) && (p.y < 101f || p.y > 495f)
            if (!onCorner) continue
            val nearest = centres.minOf { (cx, cy) -> hypot(p.x - cx, p.y - cy) }
            assertEquals(101f, nearest, 0.05f)
        }
        // The top-right corner's middle.
        val r = 101f
        val mid = edge.pointAt((774f + PI.toFloat() * r / 4) / edge.length)
        assertAt(875f + r * 0.70711f, 101f - r * 0.70711f, mid)
    }
}
