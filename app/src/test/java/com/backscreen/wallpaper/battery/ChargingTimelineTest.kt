package com.backscreen.wallpaper.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingTimelineTest {

    private fun frames(level: Int) = (0..ChargingTimeline.DURATION_MS step 10).map { ChargingTimeline.frame(it, level) }

    @Test
    fun itStartsWithNothingShowing() {
        val start = ChargingTimeline.frame(0, 87)
        assertEquals(0f, start.veil)
        assertEquals(0f, start.content)
        assertEquals(0f, start.ring)
        assertEquals(0, start.percent)
        assertEquals(0f, start.boltTrace)
        assertEquals(0f, start.label)
    }

    @Test
    fun theRingFillsToTheLevelAsTheNumberCountsUp() {
        val filled = ChargingTimeline.frame(1700, 87)
        assertEquals(0.87f, filled.ring, 0.0001f)
        assertEquals(87, filled.percent)
        // Part way, the number matches the ring.
        val partWay = ChargingTimeline.frame(700, 87)
        assertTrue(partWay.percent in 1 until 87)
        assertEquals(partWay.ring * 100, partWay.percent.toFloat(), 1f)
    }

    @Test
    fun theNumberOnlyCountsUpAndNeverPassesTheLevel() {
        for (level in listOf(0, 1, 15, 50, 99, 100)) {
            val numbers = frames(level).map { it.percent }
            assertEquals(numbers.sorted(), numbers)
            assertTrue(numbers.all { it <= level })
            assertEquals(level, numbers.last())
        }
    }

    @Test
    fun everythingIsInPlaceBeforeItFades() {
        val held = ChargingTimeline.frame(2500, 40)
        assertEquals(1f, held.veil)
        assertEquals(1f, held.content)
        assertEquals(1f, held.scale)
        assertEquals(1f, held.boltTrace)
        assertEquals(1f, held.boltFill)
        assertEquals(1f, held.label)
        assertEquals(40, held.percent)
    }

    @Test
    fun itFadesBackToTheWallpaperByTheEnd() {
        val end = ChargingTimeline.frame(ChargingTimeline.DURATION_MS, 40)
        assertEquals(0f, end.veil)
        assertEquals(0f, end.content)
        // And past the end, as a late frame might ask for.
        assertEquals(0f, ChargingTimeline.frame(ChargingTimeline.DURATION_MS + 500, 40).content)
        // It fades steadily, never coming back.
        val fading = frames(40).filter { it.ring > 0 }.map { it.content }.dropWhile { it < 1f }
        assertEquals(fading.sortedDescending(), fading)
    }

    @Test
    fun theStillIsTheMiddleOfTheAnimation() {
        assertEquals(ChargingTimeline.frame(2500, 63), ChargingTimeline.still(63))
        assertEquals(1f, ChargingTimeline.still(63).content)
    }

    @Test
    fun levelsOutsideZeroToAHundredAreKeptInside() {
        assertEquals(100, ChargingTimeline.still(140).percent)
        assertEquals(1f, ChargingTimeline.still(140).ring)
        assertEquals(0, ChargingTimeline.still(-5).percent)
    }
}
