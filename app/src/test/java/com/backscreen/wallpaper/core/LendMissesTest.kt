package com.backscreen.wallpaper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LendMissesTest {

    private val youtube = Lend(LendReason.QUICK_SWITCH, 3377, "app.revanced.android.youtube")

    private fun rear(vararg tasks: Pair<Int, String>) = buildString {
        appendLine("RootTask id=1 bounds=[0,0][1200,2608] displayId=0 userId=0")
        appendLine("  taskId=12: com.teslacoilsw.launcher/com.teslacoilsw.launcher.NovaLauncher bounds=[0,0][1200,2608] userId=0 visible=true")
        for ((id, pkg) in tasks) {
            appendLine("RootTask id=$id bounds=[0,0][976,596] displayId=1 userId=0")
            appendLine("  taskId=$id: $pkg/$pkg.Main bounds=[0,0][976,596] userId=0 visible=true")
        }
    }

    @Test
    fun oneMissIsNotEnough() {
        val misses = LendMisses()
        assertFalse(misses.check(false))
        assertTrue(misses.check(false))
    }

    @Test
    fun beingSeenAgainStartsTheCountAfresh() {
        val misses = LendMisses()
        assertFalse(misses.check(false))
        assertFalse(misses.check(true))
        assertFalse(misses.check(false))
        assertTrue(misses.check(false))
    }

    @Test
    fun anUnreadListCountsForNothing() {
        val misses = LendMisses()
        assertFalse(misses.check(false))
        assertFalse(misses.check(null))
        assertFalse(misses.check(null))
        assertTrue(misses.check(false))
    }

    @Test
    fun inFrontMeansFirstOnTheBackScreen() {
        assertEquals(true, LendMisses.inFront(rear(3377 to youtube.packageName, 3363 to "com.backscreen.wallpaper"), 1, youtube))
        // Sent behind the wallpaper (BACK on its first screen), or closed.
        assertEquals(false, LendMisses.inFront(rear(3363 to "com.backscreen.wallpaper", 3377 to youtube.packageName), 1, youtube))
        assertEquals(false, LendMisses.inFront(rear(3363 to "com.backscreen.wallpaper"), 1, youtube))
        // The list once left out the back screen altogether.
        assertEquals(false, LendMisses.inFront(rear(), 1, youtube))
        assertNull(LendMisses.inFront(null, 1, youtube))
    }

    @Test
    fun anotherTaskOfTheSameAppCounts() {
        assertEquals(true, LendMisses.inFront(rear(3390 to youtube.packageName, 3377 to youtube.packageName), 1, youtube))
    }
}
