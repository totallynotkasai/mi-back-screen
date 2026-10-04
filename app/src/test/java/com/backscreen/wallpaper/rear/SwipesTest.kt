package com.backscreen.wallpaper.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipesTest {

    // The 17 Pro Max's back screen.
    private val w = 976
    private val h = 596

    private fun swipe(x0: Number, y0: Number, x1: Number, y1: Number, ms: Long = 150) =
        Swipes.classify(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), ms, w, h)

    @Test
    fun swipesMeasuredOnThePhoneCount() {
        // Left from the middle: 34-46% of the width at 2.3-3.3 px/ms.
        assertEquals(Swipe.LEFT, swipe(700, 300, 368, 310, ms = 145))
        assertEquals(Swipe.LEFT, swipe(800, 250, 351, 280, ms = 136))
        // Down from the top: 44-81% of the height.
        assertEquals(Swipe.DOWN, swipe(500, 40, 510, 302, ms = 90))
        assertEquals(Swipe.DOWN, swipe(450, 20, 430, 503, ms = 160))
    }

    @Test
    fun tooShortIsNothing() {
        // Under 30% of the width, or 25% of the height.
        assertNull(swipe(600, 300, 320, 300))
        assertNull(swipe(500, 100, 500, 240))
    }

    @Test
    fun tooSlowIsNothing() {
        assertNull(swipe(700, 300, 300, 300, ms = 1200))
    }

    @Test
    fun diagonalIsNothing() {
        // Both far enough, but neither twice the other.
        assertNull(swipe(800, 50, 400, 350))
    }

    @Test
    fun theWrongWayIsNothing() {
        assertNull(swipe(300, 300, 700, 300))
        assertNull(swipe(500, 500, 500, 100))
    }

    @Test
    fun xiaomisGestureStripsDontCount() {
        // Starting in Xiaomi's back strip down the right edge.
        assertNull(swipe(930, 300, 500, 300))
        // Just left of it, as a natural swipe on the phone started (x 907).
        assertEquals(Swipe.LEFT, swipe(907, 288, 312, 400, ms = 265))
        // Starting in Xiaomi's Home strip along the bottom.
        assertNull(swipe(700, 560, 300, 560))
    }

    @Test
    fun downOnlyFromTheTopHalf() {
        assertNull(swipe(500, 320, 500, 580))
    }

    @Test
    fun aPullDownStartsInTheTopHalfAndGoesDown() {
        assertTrue(Swipes.startsPull(500f, 40f, 505f, 60f, slop = 13f, height = h))
        // Not yet past the slop, sideways, or from the bottom half.
        assertFalse(Swipes.startsPull(500f, 40f, 500f, 50f, slop = 13f, height = h))
        assertFalse(Swipes.startsPull(500f, 40f, 540f, 60f, slop = 13f, height = h))
        assertFalse(Swipes.startsPull(500f, 320f, 500f, 400f, slop = 13f, height = h))
    }

    @Test
    fun aPullOpensWhenItWentAsFarAsASwipe() {
        assertTrue(Swipes.pullOpens(dx = 10f, dy = 160f, height = h))
        assertFalse(Swipes.pullOpens(dx = 10f, dy = 120f, height = h))
        assertFalse(Swipes.pullOpens(dx = 100f, dy = 160f, height = h))
    }

    @Test
    fun aPushUpCloses() {
        assertTrue(Swipes.pushCloses(dx = 5f, dy = -100f, height = h))
        assertFalse(Swipes.pushCloses(dx = 5f, dy = -60f, height = h))
        assertFalse(Swipes.pushCloses(dx = 5f, dy = 100f, height = h))
        assertFalse(Swipes.pushCloses(dx = 80f, dy = -100f, height = h))
    }

    @Test
    fun nothingOnAnUnmeasuredScreen() {
        assertNull(Swipes.classify(700f, 300f, 300f, 300f, 150, 0, 0))
    }
}
