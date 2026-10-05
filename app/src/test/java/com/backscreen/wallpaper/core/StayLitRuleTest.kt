package com.backscreen.wallpaper.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StayLitRuleTest {

    @Test
    fun itHoldsWhileYoureUsingThePhoneWithTheWallpaperUp() {
        assertTrue(StayLitRule.holds(option = true, wallpaperUp = true, unlocked = true, mainOn = true))
        assertFalse(StayLitRule.holds(option = false, wallpaperUp = true, unlocked = true, mainOn = true))
        // An app lent the back screen, or the wallpaper off.
        assertFalse(StayLitRule.holds(option = true, wallpaperUp = false, unlocked = true, mainOn = true))
    }

    @Test
    fun lockingLetsItDim() {
        // The lock screen showing, with the main screen on.
        assertFalse(StayLitRule.holds(option = true, wallpaperUp = true, unlocked = false, mainOn = true))
    }

    @Test
    fun theMainScreenGoingOffLetsItDim() {
        // Before the phone locks itself.
        assertFalse(StayLitRule.holds(option = true, wallpaperUp = true, unlocked = true, mainOn = false))
    }

    @Test
    fun itLightsADimmedBackScreenOnlyAsTheHoldStarts() {
        // At unlock the back screen is dimmed: light it.
        assertTrue(StayLitRule.lightsUp(held = false, holds = true, rearLit = false))
        // Lit already, or held all along, or no longer held: nothing to do.
        assertFalse(StayLitRule.lightsUp(held = false, holds = true, rearLit = true))
        assertFalse(StayLitRule.lightsUp(held = true, holds = true, rearLit = false))
        assertFalse(StayLitRule.lightsUp(held = true, holds = false, rearLit = false))
    }
}
