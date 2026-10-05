package com.backscreen.wallpaper.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickUpRuleTest {

    @Test
    fun pickingItUpOffTheTableLightsIt() {
        val rule = PickUpRule()
        // Lying on its back: the first reading is covered.
        assertFalse(rule.reading(covered = true))
        assertTrue(rule.reading(covered = false))
    }

    @Test
    fun aClearBackToBeginWithLightsNothing() {
        // In your hand, after Xiaomi switched the dimmed back screen off.
        val rule = PickUpRule()
        assertFalse(rule.reading(covered = false))
        assertFalse(rule.reading(covered = false))
    }

    @Test
    fun eachPickUpNeedsACoverFirst() {
        val rule = PickUpRule()
        rule.reading(covered = true)
        assertTrue(rule.reading(covered = false))
        assertFalse(rule.reading(covered = false))
        // Put down and picked up again.
        assertFalse(rule.reading(covered = true))
        assertTrue(rule.reading(covered = false))
        // A cover seen before it stopped watching doesn't count.
        rule.reading(covered = true)
        rule.reset()
        assertFalse(rule.reading(covered = false))
    }

    @Test
    fun itWatchesOnlyWhileTheBackScreenIsOffAndYoureUsingThePhone() {
        assertTrue(PickUpRule.watches(option = true, wallpaperUp = true, unlocked = true, mainOn = true, rearOff = true))
        // Dimmed or lit: Xiaomi brings it back by itself, or it's lit already.
        assertFalse(PickUpRule.watches(option = true, wallpaperUp = true, unlocked = true, mainOn = true, rearOff = false))
        // Locked, or the main screen off: nobody's looking.
        assertFalse(PickUpRule.watches(option = true, wallpaperUp = true, unlocked = false, mainOn = true, rearOff = true))
        assertFalse(PickUpRule.watches(option = true, wallpaperUp = true, unlocked = true, mainOn = false, rearOff = true))
        // The option off, or no wallpaper.
        assertFalse(PickUpRule.watches(option = false, wallpaperUp = true, unlocked = true, mainOn = true, rearOff = true))
        assertFalse(PickUpRule.watches(option = true, wallpaperUp = false, unlocked = true, mainOn = true, rearOff = true))
    }
}
