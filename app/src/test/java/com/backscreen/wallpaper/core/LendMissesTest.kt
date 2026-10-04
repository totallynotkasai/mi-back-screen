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
    fun oneMissIsEnoughWhenTheWallpaperIsShowingAgain() {
        // Xiaomi Camera closed from Xiaomi's back strip, and the wallpaper came back (Phase 7).
        assertTrue(LendMisses().check(false, wallpaperShowing = true))
        // But the app still in front, or an unread list, doesn't end it.
        assertFalse(LendMisses().check(true, wallpaperShowing = true))
        assertFalse(LendMisses().check(null, wallpaperShowing = true))
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

    @Test
    fun xiaomiCameraIsFoundByItsPackage() {
        // Its task isn't known when it's opened. Taken on the phone with it up (Phase 7): its
        // own page is in front on the main screen, and the camera on the back screen.
        val camera = Lend(LendReason.CAMERA, -1, "com.android.camera")
        val up = """
            RootTask id=3453 bounds=[0,0][1200,2608] displayId=0 userId=0
              taskId=3453: com.android.camera/com.android.camera.fragment.presentation.MainScreenSelfieActivity bounds=[0,0][1200,2608] userId=0 visible=true
            RootTask id=3451 bounds=[0,0][1200,2608] displayId=0 userId=0
              taskId=3451: com.backscreen.wallpaper/com.backscreen.wallpaper.MainActivity bounds=[0,0][1200,2608] userId=0 visible=false
            RootTask id=3452 bounds=[0,0][976,596] displayId=1 userId=0
              taskId=3452: com.android.camera/com.android.camera.Camera bounds=[0,0][976,596] userId=0 visible=true
            RootTask id=3449 bounds=[0,0][976,596] displayId=1 userId=0
              taskId=3449: com.backscreen.wallpaper/com.backscreen.wallpaper.rear.RearHostActivity bounds=[0,0][976,596] userId=0 visible=false
        """.trimIndent()
        assertEquals(true, LendMisses.inFront(up, 1, camera))
        // Closed: the wallpaper is in front again, though a finished camera task can linger behind it.
        val closed = """
            RootTask id=3449 bounds=[0,0][976,596] displayId=1 userId=0
              taskId=3449: com.backscreen.wallpaper/com.backscreen.wallpaper.rear.RearHostActivity bounds=[0,0][976,596] userId=0 visible=true
            RootTask id=3443 bounds=[0,0][976,596] displayId=1 userId=0
              taskId=3443: com.android.camera/com.android.camera.Camera bounds=[0,0][976,596] userId=0 visible=false
        """.trimIndent()
        assertEquals(false, LendMisses.inFront(closed, 1, camera))
    }
}
