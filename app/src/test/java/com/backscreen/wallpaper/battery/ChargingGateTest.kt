package com.backscreen.wallpaper.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingGateTest {

    @Test
    fun theFirstPlugInPlays() {
        assertTrue(ChargingGate().onPlugged(1_000))
    }

    @Test
    fun pluggingInAndOutFiveTimesQuicklyPlaysOnce() {
        val gate = ChargingGate()
        // In every 0.8 s, unplugging in between.
        val played = (0 until 5).count { gate.onPlugged(10_000L + it * 800) }
        assertEquals(1, played)
    }

    @Test
    fun aGapLongerThanAnAnimationPlaysAgain() {
        val gate = ChargingGate()
        assertTrue(gate.onPlugged(0))
        assertFalse(gate.onPlugged(ChargingGate.MIN_GAP_MS - 1))
        assertTrue(gate.onPlugged(ChargingGate.MIN_GAP_MS + 100))
        assertTrue(ChargingGate.MIN_GAP_MS > ChargingTimeline.DURATION_MS)
    }

    @Test
    fun aHeldBackPlugInDoesntPutOffTheNext() {
        // Only one that plays counts, so a cable jiggling for a while can't hold it off for good.
        val gate = ChargingGate()
        assertTrue(gate.onPlugged(0))
        assertFalse(gate.onPlugged(4_000))
        assertTrue(gate.onPlugged(5_000))
    }
}
