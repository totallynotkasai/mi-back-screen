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

    private fun glows(level: Int, stayFaint: Boolean = true) =
        (0..ChargingTimeline.DURATION_MS step 10).map { ChargingTimeline.glow(it, level, stayFaint) }

    @Test
    fun theGlowStartsWithNothingAndFadesIn() {
        val start = ChargingTimeline.glow(0, 63, stayFaint = true)
        assertEquals(GlowFrame(0f, 0f, 0f, 0f), start)
        assertEquals(ChargingTimeline.GLOW_FULL, ChargingTimeline.glow(1000, 63, stayFaint = true).edge, 0.0001f)
    }

    @Test
    fun theHeadRunsRoundAsFarAsTheLevel() {
        // 63% of the way round at 63%, and only ever forwards.
        assertEquals(0.63f, ChargingTimeline.glow(2000, 63, stayFaint = true).filled, 0.0001f)
        for (level in listOf(0, 15, 63, 100)) {
            val filled = glows(level).map { it.filled }
            assertEquals(filled.sorted(), filled)
            assertTrue(filled.all { it <= level / 100f + 0.0001f })
        }
        // All the way round when full, with the bright head showing on the way.
        assertEquals(1f, ChargingTimeline.glow(2000, 100, stayFaint = true).filled, 0.0001f)
        assertEquals(1f, ChargingTimeline.glow(1000, 100, stayFaint = true).head)
    }

    @Test
    fun theGlowSettlesToFaintWhileCharging() {
        val end = ChargingTimeline.glow(ChargingTimeline.DURATION_MS, 63, stayFaint = true)
        // Just the faint glow round the whole edge, as it stays while charging.
        assertEquals(ChargingTimeline.faint.edge, end.edge)
        assertEquals(ChargingTimeline.GLOW_FAINT, end.edge)
        assertEquals(0f, end.fill)
        assertEquals(0f, end.head)
        // And it stays there, however late a frame comes.
        assertEquals(end, ChargingTimeline.glow(ChargingTimeline.DURATION_MS + 900, 63, stayFaint = true))
    }

    @Test
    fun overXiaomisScreenTheGlowFadesAway() {
        val end = ChargingTimeline.glow(ChargingTimeline.DURATION_MS, 63, stayFaint = false)
        assertEquals(GlowFrame(0f, 0.63f, 0f, 0f), end)
        // It never goes past full on the way.
        assertTrue(glows(63, stayFaint = false).all { it.edge <= ChargingTimeline.GLOW_FULL + 0.0001f })
    }

    @Test
    fun minimalCountsUpToTheLevel() {
        for (level in listOf(0, 1, 63, 100)) {
            val numbers = (0..ChargingTimeline.DURATION_MS step 10).map { ChargingTimeline.minimal(it, level).percent }
            assertEquals(numbers.sorted(), numbers)
            assertTrue(numbers.all { it <= level })
            assertEquals(level, numbers.last())
        }
        // It rises into place as it appears.
        assertEquals(0f, ChargingTimeline.minimal(0, 63).rise)
        assertEquals(1f, ChargingTimeline.minimal(500, 63).rise)
    }

    @Test
    fun minimalHoldsThenFades() {
        assertEquals(0f, ChargingTimeline.minimal(0, 63).content)
        assertEquals(MinimalFrame(1f, 1f, 63), ChargingTimeline.minimal(2500, 63))
        assertEquals(MinimalFrame(1f, 1f, 63), ChargingTimeline.stillMinimal(63))
        assertEquals(0f, ChargingTimeline.minimal(ChargingTimeline.DURATION_MS, 63).content)
    }

    @Test
    fun levelsOutsideZeroToAHundredAreKeptInside() {
        assertEquals(100, ChargingTimeline.still(140).percent)
        assertEquals(1f, ChargingTimeline.still(140).ring)
        assertEquals(0, ChargingTimeline.still(-5).percent)
    }
}
