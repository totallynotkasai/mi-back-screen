package com.backscreen.wallpaper.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockReturnTest {

    private val settle = 1_500L

    // Everything met: on, locked, the back screen dark 6 s, the app sent a minute ago.
    private fun bringsBack(
        on: Boolean = true,
        locked: Boolean = true,
        rearLit: Boolean = false,
        darkForMs: Long = 6_000,
        lendAgeMs: Long = 60_000,
    ) = LockReturn.shouldBringBack(on, locked, rearLit, darkForMs, lendAgeMs, settle)

    @Test
    fun allMetItComesBack() {
        assertTrue(bringsBack())
        assertTrue(bringsBack(darkForMs = LockReturn.DARK_MS))
    }

    @Test
    fun notWithTheOptionOff() {
        // As before the option: it stays on the locked back screen.
        assertFalse(bringsBack(on = false))
    }

    @Test
    fun notWhileUnlocked() {
        assertFalse(bringsBack(locked = false))
    }

    @Test
    fun notWhileTheBackScreenIsLit() {
        // You're using it there after pressing Power.
        assertFalse(bringsBack(rearLit = true))
    }

    @Test
    fun notUntilItsBeenDarkFiveSeconds() {
        assertFalse(bringsBack(darkForMs = 4_999))
    }

    @Test
    fun notForAnAppJustSent() {
        // Xiaomi's back screen can dim for a moment as it settles after a send.
        assertFalse(bringsBack(lendAgeMs = 1_000))
    }

    @Test
    fun itLooksAgainWhenItCouldBeTime() {
        assertEquals(3_000, LockReturn.waitMs(darkForMs = 2_000, lendAgeMs = 60_000, settleMs = settle))
        assertEquals(5_000, LockReturn.waitMs(darkForMs = 0, lendAgeMs = 0, settleMs = settle))
        assertEquals(0, LockReturn.waitMs(darkForMs = 9_000, lendAgeMs = 60_000, settleMs = settle))
    }
}
