package com.backscreen.wallpaper.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraDimTest {

    @Test
    fun itClosesWhenTheBackScreenDims() {
        // Swiped left on the lit wallpaper.
        val dim = CameraDim()
        dim.opened(now = 0, lit = true)
        assertNull(dim.closeReason(lit = true, now = 8000))
        assertEquals("the back screen dimmed", dim.closeReason(lit = false, now = 12000))
    }

    @Test
    fun itWaitsForTheBackScreenToLightUp() {
        // Open camera from the tab while the back screen was dim: Xiaomi Camera lights it itself.
        val dim = CameraDim()
        dim.opened(now = 0, lit = false)
        assertNull(dim.closeReason(lit = false, now = 400))
        assertNull(dim.closeReason(lit = true, now = 900))
        assertEquals("the back screen dimmed", dim.closeReason(lit = false, now = 20000))
    }

    @Test
    fun aBackScreenThatNeverLightsUpClosesIt() {
        val dim = CameraDim()
        dim.opened(now = 0, lit = false)
        assertNull(dim.closeReason(lit = false, now = CameraDim.LIGHT_WITHIN_MS - 1))
        assertEquals("the back screen didn't light up", dim.closeReason(lit = false, now = CameraDim.LIGHT_WITHIN_MS))
    }

    @Test
    fun eachOpeningStartsAfresh() {
        val dim = CameraDim()
        dim.opened(now = 0, lit = true)
        assertEquals("the back screen dimmed", dim.closeReason(lit = false, now = 5000))
        // Opened again later, from the tab, with the back screen dim.
        dim.opened(now = 60000, lit = false)
        assertNull(dim.closeReason(lit = false, now = 61000))
    }
}
