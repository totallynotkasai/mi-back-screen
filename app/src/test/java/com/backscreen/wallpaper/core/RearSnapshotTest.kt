package com.backscreen.wallpaper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RearSnapshotTest {

    private val youtube = Lend(LendReason.QUICK_SWITCH, 42, "com.google.android.youtube")
    private val camera = Lend(LendReason.CAMERA, 43, "com.android.camera")

    private val xiaomi = RearSnapshot(wallpaperOn = false)
    private val wallpaper = RearSnapshot(wallpaperOn = true)

    @Test
    fun theWallpaperSwitchDecidesWhoNormallyOwnsIt() {
        assertEquals(RearOwner.Xiaomi, xiaomi.owner)
        assertEquals(RearOwner.Host(HostMode.WALLPAPER), wallpaper.owner)
        assertTrue(wallpaper.guardsWallpaper)
        assertFalse(xiaomi.guardsWallpaper)
    }

    @Test
    fun aLentAppIsNeverCovered() {
        val lent = wallpaper.lent(youtube)!!
        assertEquals(RearOwner.Lent(youtube), lent.owner)
        // The keeper doesn't put the wallpaper back over it, and nothing pops over it.
        assertFalse(lent.guardsWallpaper)
        assertEquals(OverlayRoute.DROP, lent.routeOverlay(popoverAllowed = true))
        // Only one app at a time.
        assertNull(lent.lent(camera))
    }

    @Test
    fun whenTheAppLeavesItGoesToWhateverTheSwitchSaysNow() {
        // A schedule turned the wallpaper off while YouTube was on the back screen.
        val lent = wallpaper.lent(youtube)!!.withWallpaper(false)
        assertEquals(RearOwner.Lent(youtube), lent.owner)
        assertEquals(RearOwner.Xiaomi, lent.returned(youtube)!!.owner)
        // And on again.
        assertEquals(RearOwner.Host(HostMode.WALLPAPER), lent.withWallpaper(true).returned(youtube)!!.owner)
    }

    @Test
    fun onlyTheLendThatsThereCanEnd() {
        // A second Bring back, or a late reply about an earlier lend, changes nothing.
        val lent = wallpaper.lent(youtube)!!
        assertNull(lent.returned(youtube.copy(id = 7)))
        assertNull(lent.returned(camera))
        assertEquals(RearOwner.Lent(youtube), lent.owner)
    }

    @Test
    fun nothingEndsWithNoLend() {
        assertNull(wallpaper.returned(youtube))
        assertNull(wallpaper.lent(youtube)!!.returned(youtube)!!.returned(youtube))
    }

    @Test
    fun twoCameraLendsAreDistinct() {
        // Same reason, task and package; only the id tells an old camera lend from a new one.
        val first = Lend(LendReason.CAMERA, -1, "com.android.camera", id = 1)
        val second = Lend(LendReason.CAMERA, -1, "com.android.camera", id = 2)
        assertFalse(first == second)
        val lent = xiaomi.lent(second)!!
        assertNull(lent.returned(first))
        assertEquals(RearOwner.Xiaomi, lent.returned(second)!!.owner)
    }

    @Test
    fun popoversOnlyGoOverXiaomisScreen() {
        val popover = xiaomi.startPopover()!!
        assertEquals(RearOwner.Popover, popover.owner)
        assertFalse(popover.guardsWallpaper)
        assertEquals(RearOwner.Xiaomi, popover.endPopover().owner)
        // With the wallpaper on, it goes in the host instead; with an app lent, nowhere.
        assertNull(wallpaper.startPopover())
        assertNull(xiaomi.lent(camera)!!.startPopover())
        assertNull(popover.startPopover())
    }

    @Test
    fun overlaysGoInTheHostWhenItsUp() {
        assertEquals(OverlayRoute.HOST, wallpaper.routeOverlay(popoverAllowed = false))
        assertEquals(OverlayRoute.HOST, xiaomi.startPopover()!!.routeOverlay(popoverAllowed = false))
        assertEquals(OverlayRoute.POPOVER, xiaomi.routeOverlay(popoverAllowed = true))
        assertEquals(OverlayRoute.DROP, xiaomi.routeOverlay(popoverAllowed = false))
    }

    @Test
    fun turningTheWallpaperOnEndsAPopover() {
        val on = xiaomi.startPopover()!!.withWallpaper(true)
        assertFalse(on.popover)
        assertEquals(RearOwner.Host(HostMode.WALLPAPER), on.owner)
        assertEquals(RearOwner.Xiaomi, on.withWallpaper(false).owner)
    }

    @Test
    fun theShadeOnlyOpensOverTheWallpaper() {
        val shade = wallpaper.openShade()!!
        assertEquals(RearOwner.Host(HostMode.SHADE), shade.owner)
        assertTrue(shade.guardsWallpaper)
        assertEquals(RearOwner.Host(HostMode.WALLPAPER), shade.closeShade().owner)
        assertNull(xiaomi.openShade())
        assertNull(shade.openShade())
        assertNull(wallpaper.lent(youtube)!!.openShade())
        // Turning the wallpaper off or lending the back screen closes it.
        assertFalse(shade.withWallpaper(false).shadeOpen)
        assertFalse(shade.lent(youtube)!!.shadeOpen)
    }

    @Test
    fun aLendEndsAPopover() {
        val lent = xiaomi.startPopover()!!.lent(camera)!!
        assertFalse(lent.popover)
        assertEquals(RearOwner.Xiaomi, lent.returned(camera)!!.owner)
    }

    @Test
    fun theKeeperRunsWhileAnythingNeedsIt() {
        assertFalse(xiaomi.keeperNeeded(batteryOn = false))
        assertTrue(xiaomi.keeperNeeded(batteryOn = true))
        assertTrue(wallpaper.keeperNeeded(batteryOn = false))
        assertTrue(xiaomi.lent(youtube)!!.keeperNeeded(batteryOn = false))
        assertTrue(xiaomi.startPopover()!!.keeperNeeded(batteryOn = false))
        // Quick Switch on: the tile asks the running keeper.
        assertTrue(xiaomi.keeperNeeded(batteryOn = false, mirrorOn = true))
    }
}
