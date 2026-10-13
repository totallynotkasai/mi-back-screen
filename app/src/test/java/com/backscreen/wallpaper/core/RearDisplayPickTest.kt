package com.backscreen.wallpaper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RearDisplayPickTest {

    // The 17 Pro Max's own two, as `dumpsys display` lists them (HyperOS 3.0.319).
    private val main = DisplayCandidate(0, 1200, 2608)
    private val rear = DisplayCandidate(1, 976, 596)

    // What Android lists ahead of them: a cast screen, or a simulated display (seen as "Overlay #1").
    private val cast = DisplayCandidate(2, 1920, 1080)
    private val overlay = DisplayCandidate(3, 1920, 1080)

    @Test
    fun theBackScreenOverACastOrSimulatedDisplay() {
        assertEquals(1, RearDisplayPick.choose(listOf(cast, overlay, main, rear), rememberedId = null))
        assertEquals(1, RearDisplayPick.choose(listOf(main, rear), rememberedId = 1))
    }

    @Test
    fun turnedToPortraitByAnAppItsStillTheBackScreen() {
        assertEquals(1, RearDisplayPick.choose(listOf(main, cast, DisplayCandidate(1, 596, 976)), rememberedId = null))
    }

    @Test
    fun anAppsPrivateDisplayIsNeverIt() {
        val private = DisplayCandidate(7, 976, 596, isPrivate = true)
        assertNull(RearDisplayPick.choose(listOf(main, private), rememberedId = null))
        assertEquals(1, RearDisplayPick.choose(listOf(main, private, rear), rememberedId = null))
    }

    @Test
    fun theOneFoundLastTimeIfNoneHasItsSize() {
        val resized = DisplayCandidate(1, 1080, 660)
        assertEquals(1, RearDisplayPick.choose(listOf(main, cast, resized), rememberedId = 1))
        // Not if it has gone.
        assertNull(RearDisplayPick.choose(listOf(main, cast), rememberedId = 1))
    }

    @Test
    fun noneWithOnlyAnExternalDisplay() {
        assertNull(RearDisplayPick.choose(listOf(main, cast), rememberedId = null))
        assertNull(RearDisplayPick.choose(listOf(main), rememberedId = null))
    }

    @Test
    fun theMainScreenIsNeverIt() {
        assertNull(RearDisplayPick.choose(listOf(DisplayCandidate(0, 976, 596)), rememberedId = 0))
    }
}
