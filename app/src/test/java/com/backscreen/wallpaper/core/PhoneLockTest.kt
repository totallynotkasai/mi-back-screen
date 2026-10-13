package com.backscreen.wallpaper.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLockTest {

    @Test
    fun lockedIsLocked() {
        assertTrue(PhoneLock.rule(locked = true, secure = true, mainScreenOn = true))
        assertTrue(PhoneLock.rule(locked = true, secure = false, mainScreenOn = false))
    }

    @Test
    fun aSecurePhoneCountsAsLockedOnceItsMainScreenIsOff() {
        // Before Android's lock delay is up.
        assertTrue(PhoneLock.rule(locked = false, secure = true, mainScreenOn = false))
    }

    @Test
    fun inUseItIsnt() {
        assertFalse(PhoneLock.rule(locked = false, secure = true, mainScreenOn = true))
        // With no secure lock, a dark main screen alone doesn't count.
        assertFalse(PhoneLock.rule(locked = false, secure = false, mainScreenOn = false))
    }
}
