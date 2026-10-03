package com.backscreen.wallpaper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeThrottleTest {

    @Test
    fun aDarkBackScreenIsWoken() {
        assertTrue(WakeThrottle().allow(now = 1_000, lit = false))
    }

    @Test
    fun aLitOneIsntWokenAgain() {
        // It wouldn't stay on any longer for it.
        assertFalse(WakeThrottle().allow(now = 1_000, lit = true))
    }

    @Test
    fun aBurstWakesItOnce() {
        val throttle = WakeThrottle()
        val woken = (0 until 6).count { throttle.allow(now = 50_000L + it * 1_000, lit = false) }
        assertEquals(1, woken)
        assertTrue(throttle.allow(now = 50_000L + WakeThrottle.MIN_GAP_MS, lit = false))
    }

    @Test
    fun aWakeYouAskedForCountsToo() {
        val throttle = WakeThrottle()
        throttle.woke(now = 0)
        assertFalse(throttle.allow(now = 3_000, lit = false))
        assertTrue(throttle.allow(now = WakeThrottle.MIN_GAP_MS, lit = false))
    }

    @Test
    fun aRefusedWakeDoesntCount() {
        val throttle = WakeThrottle()
        assertFalse(throttle.allow(now = 0, lit = true))
        assertTrue(throttle.allow(now = 1_000, lit = false))
    }
}
