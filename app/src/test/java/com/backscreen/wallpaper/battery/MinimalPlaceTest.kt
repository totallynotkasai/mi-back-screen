package com.backscreen.wallpaper.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalPlaceTest {

    // The back screen's space for the clock: 976 x 596, beside the 296 px camera strip, less the
    // clock's 41 px margin.
    private val area = Box(337f, 41f, 935f, 555f)

    // Minimal at the back screen's size: about 160 x 60, 18 px from the clock.
    private val w = 160f
    private val h = 60f
    private val gap = 18f

    // Each clock style's time and date, roughly, at the back screen's size.
    private val clocks = mapOf(
        "Classic" to (450f to 262f),
        "Light" to (450f to 258f),
        "Stacked" to (210f to 450f),
        "Digital" to (357f to 181f),
        "Serif" to (400f to 230f),
    )

    /** The clock's box at ([x], [y]), placed as the clock places itself. */
    private fun clockAt(size: Pair<Float, Float>, x: Float, y: Float): Box {
        val left = area.left + (area.width - size.first) * x
        val top = area.top + (area.height - size.second) * y
        return Box(left, top, left + size.first, top + size.second)
    }

    private fun minimal(clock: Box, x: Float): Box {
        val (left, top) = MinimalPlace.place(area, clock, x, w, h, gap)
        return Box(left, top, left + w, top + h)
    }

    @Test
    fun everyClockStyleAndPlaceLeavesItOnScreenAndClearOfTheClock() {
        val places = listOf(0f, 0.5f, 1f)
        for ((style, size) in clocks) for (x in places) for (y in places) {
            val clock = clockAt(size, x, y)
            val placed = minimal(clock, x)
            assertTrue("$style at $x, $y: $placed is off the screen", area.contains(placed))
            assertFalse("$style at $x, $y: $placed covers the clock", clock.overlaps(placed))
        }
    }

    @Test
    fun belowTheClockWhereThereIsRoomAndAboveItAtTheBottom() {
        // Classic in the middle: just below, centred under it.
        val middle = clockAt(clocks.getValue("Classic"), 0.5f, 0.5f)
        val below = minimal(middle, 0.5f)
        assertEquals(middle.bottom + gap, below.top, 0.01f)
        assertEquals(middle.centreX, below.centreX, 0.01f)
        // Light at the bottom left: just above, lined up on the left.
        val corner = clockAt(clocks.getValue("Light"), 0f, 1f)
        val above = minimal(corner, 0f)
        assertEquals(corner.top - gap, above.bottom, 0.01f)
        assertEquals(corner.left, above.left, 0.01f)
    }

    @Test
    fun aStackedClockAtTheSideGetsItBesideTheDateEvenToAPixel() {
        // Stacked at the bottom right fills the height: beside it, level with the date.
        val stacked = clockAt(clocks.getValue("Stacked"), 1f, 1f)
        val beside = minimal(stacked, 1f)
        assertEquals(stacked.left - gap, beside.right, 0.01f)
        assertEquals(stacked.bottom, beside.bottom, 0.01f)
        // The clock rounds its margin to whole pixels, so it can reach a fraction past the space
        // worked out here; that still counts as fitting, rather than going to the far corner.
        val rounded = Box(stacked.left, stacked.top + 0.7f, stacked.right, stacked.bottom + 0.7f)
        val stillBeside = minimal(rounded, 1f)
        assertEquals(rounded.left - gap, stillBeside.right, 0.01f)
        assertTrue(area.contains(stillBeside))
    }
}
