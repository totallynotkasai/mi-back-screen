package com.backscreen.wallpaper.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTimerTest {

    @Test
    fun coveringForAMomentAndAHalfCounts() {
        val timer = CoverTimer()
        timer.reading(covered = false, now = 0)
        assertEquals(1000L + CoverTimer.HOLD_MS, timer.reading(covered = true, now = 1000))
        assertFalse(timer.isDue(2000))
        assertTrue(timer.isDue(2500))
    }

    @Test
    fun aBriefCoverDoesNot() {
        // Holding the phone to watch the back screen covered it for 1.1 and 1.25 s (Phase 6).
        val timer = CoverTimer()
        timer.reading(covered = false, now = 0)
        timer.reading(covered = true, now = 1000)
        timer.reading(covered = false, now = 2100)
        assertFalse(timer.isDue(2500))
        timer.reading(covered = true, now = 3000)
        timer.reading(covered = false, now = 4250)
        assertFalse(timer.isDue(4600))
    }

    @Test
    fun aCoverAlreadyThereWaitsForTheBackToBeClear() {
        // Sent with the phone lying on its back: it doesn't come straight back.
        val timer = CoverTimer()
        assertNull(timer.reading(covered = true, now = 0))
        assertFalse(timer.isDue(5000))
        timer.reading(covered = false, now = 6000)
        timer.reading(covered = true, now = 7000)
        assertTrue(timer.isDue(8500))
    }

    @Test
    fun aRepeatedReadingKeepsTheFirstTime() {
        val timer = CoverTimer()
        timer.reading(covered = false, now = 0)
        timer.reading(covered = true, now = 1000)
        assertNull(timer.reading(covered = true, now = 2000))
        assertTrue(timer.isDue(2500))
    }
}
