package com.backscreen.wallpaper.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

class PanPlannerTest {

    // The 17 Pro Max's back screen, and the part beside its camera.
    private val w = 976
    private val h = 596
    private val besideCamera = 976 - 296

    private fun plan(iw: Int, ih: Int, speed: PanSpeed = PanSpeed.MEDIUM, aw: Int = w) =
        PanPlanner.plan(iw, ih, aw, h, h, speed)

    private fun assertNear(expected: Double, actual: Number, within: Double) =
        assertTrue("expected $expected ± $within, got $actual", abs(expected - actual.toDouble()) <= within)

    @Test
    fun portraitPhotosPanUpAndDownALot() {
        val photo = plan(3000, 4000)
        assertEquals(PanAxis.VERTICAL, photo.axis)
        // Covering 976 wide makes it 1301 tall.
        assertNear(705.0, photo.overflow, 1.0)
        // The plan's example: about 24 s at Medium, plus easing in and out.
        assertNear(24_000.0, photo.sweepMs, 1600.0)

        val tall = plan(1080, 1920)
        assertEquals(PanAxis.VERTICAL, tall.axis)
        assertNear(1139.0, tall.overflow, 1.0)
    }

    @Test
    fun landscapeCameraPhotosStillPanUpAndDown() {
        // 4:3 is 1.33:1, taller than the screen's 1.64:1.
        val photo = plan(4000, 3000)
        assertEquals(PanAxis.VERTICAL, photo.axis)
        assertNear(136.0, photo.overflow, 1.0)
    }

    @Test
    fun wideImagesPanSideToSide() {
        val video = plan(1920, 1080)
        assertEquals(PanAxis.HORIZONTAL, video.axis)
        assertNear(83.6, video.overflow, 1.0)

        val panorama = plan(8000, 2000)
        assertEquals(PanAxis.HORIZONTAL, panorama.axis)
        assertNear(1408.0, panorama.overflow, 1.0)
        // Speed is distance per second, so a panorama takes longer than a slightly wide image.
        assertTrue(panorama.sweepMs > 5 * video.sweepMs)
    }

    @Test
    fun imagesTheScreensShapeDontPan() {
        // 976 x 596 is 1.638:1; within 3% either way fits.
        for ((iw, ih) in listOf(976 to 596, 1640 to 1000, 1680 to 1000, 1600 to 1000, 4880 to 2980)) {
            val fits = plan(iw, ih)
            assertEquals("$iw x $ih", PanAxis.NONE, fits.axis)
            assertEquals(0f, fits.offsetAt(12_345))
        }
        // 3.8% wider than the screen does pan, a little.
        assertEquals(PanAxis.HORIZONTAL, plan(1700, 1000).axis)
    }

    @Test
    fun besideTheCameraTheScreensShapeChanges() {
        // 680 x 596 is 1.14:1, so a 4:3 photo is now the wider one.
        assertEquals(PanAxis.HORIZONTAL, plan(4000, 3000, aw = besideCamera).axis)
        assertEquals(PanAxis.VERTICAL, plan(3000, 4000, aw = besideCamera).axis)
    }

    @Test
    fun fasterSpeedsSweepSooner() {
        val slow = plan(3000, 4000, PanSpeed.SLOW).sweepMs
        val medium = plan(3000, 4000, PanSpeed.MEDIUM).sweepMs
        val fast = plan(3000, 4000, PanSpeed.FAST).sweepMs
        assertTrue(slow > medium && medium > fast)
        // Slow is 2% of the screen's height a second: 705 px at 11.9 px/s.
        assertNear(59_100.0 + PanPlan.RAMP_MS, slow, 300.0)
    }

    @Test
    fun itHoldsGlidesHoldsAndComesBack() {
        val p = plan(3000, 4000)
        val sweep = p.sweepMs
        val hold = PanPlan.HOLD_MS
        assertEquals(0f, p.offsetAt(0))
        assertEquals(0f, p.offsetAt(hold - 1))
        assertNear(p.overflow / 2.0, p.offsetAt(hold + sweep / 2), 1.0)
        assertEquals(p.overflow, p.offsetAt(hold + sweep))
        assertEquals(p.overflow, p.offsetAt(2 * hold + sweep - 1))
        assertNear(p.overflow / 2.0, p.offsetAt(2 * hold + sweep + sweep / 2), 1.0)
        assertEquals(0f, p.offsetAt(p.cycleMs))
        // And round again.
        assertEquals(p.offsetAt(1234), p.offsetAt(p.cycleMs * 3 + 1234))
    }

    @Test
    fun itGlidesAtTheChosenSpeedAndEasesAtTheEnds() {
        val p = plan(3000, 4000)
        val pxPerMs = 0.05 * h / 1000
        val start = PanPlan.HOLD_MS
        // Mid-sweep, a second covers 5% of the screen's height.
        val mid = start + p.sweepMs / 2
        assertNear(pxPerMs * 1000, p.offsetAt(mid + 500) - p.offsetAt(mid - 500), 0.5)
        // Just after setting off, it's barely moving.
        assertTrue(p.offsetAt(start + 100) - p.offsetAt(start) < pxPerMs * 100 / 10)
        // It never goes past either end, and only goes one way in each sweep.
        var last = 0f
        for (t in start..start + p.sweepMs step 50) {
            val offset = p.offsetAt(t)
            assertTrue(offset in 0f..p.overflow && offset >= last)
            last = offset
        }
    }

    @Test
    fun aShortPanEasesAllTheWay() {
        // 16:9 at Fast is only 1.4 s of gliding, shorter than a ramp.
        val p = plan(1920, 1080, PanSpeed.FAST)
        assertEquals(p.overflow, p.offsetAt(PanPlan.HOLD_MS + p.sweepMs))
        assertNear(p.overflow / 2.0, p.offsetAt(PanPlan.HOLD_MS + p.sweepMs / 2), 0.5)
    }

    @Test
    fun itsRedrawnOnlyForEachNewPixel() {
        val p = plan(3000, 4000, PanSpeed.SLOW)
        var t = 0L
        var redraws = 0
        var drawn = p.offsetAt(0).roundToInt()
        while (t < p.cycleMs) {
            val next = p.nextMoveAt(t)
            assertTrue(next > t)
            t = next
            val offset = p.offsetAt(t).roundToInt()
            if (offset != drawn) {
                // One pixel at a time, never skipping.
                assertEquals(1, abs(offset - drawn))
                redraws++
            }
            drawn = offset
        }
        // Each way, one redraw per pixel, and none while it holds.
        assertEquals(2 * ceil(p.overflow - 0.5).toInt(), redraws)
    }

    @Test
    fun aNewSpeedCarriesOnFromTheSamePlace() {
        val medium = plan(3000, 4000, PanSpeed.MEDIUM)
        val fast = plan(3000, 4000, PanSpeed.FAST)
        for (t in listOf(500L, PanPlan.HOLD_MS + 4000, PanPlan.HOLD_MS + medium.sweepMs + 700, medium.cycleMs - 3000)) {
            val same = fast.matching(medium, t)
            assertNear(medium.offsetAt(t).toDouble(), fast.offsetAt(same), 1.0)
        }
        // Going back, it keeps going back.
        val back = medium.cycleMs - 3000
        val there = fast.matching(medium, back)
        assertTrue(fast.offsetAt(there + 100) < fast.offsetAt(there))
    }

    @Test
    fun aDifferentDirectionStartsAgain() {
        val upAndDown = plan(4000, 3000)
        val sideToSide = plan(4000, 3000, aw = besideCamera)
        assertEquals(0L, sideToSide.matching(upAndDown, 9000))
    }

    @Test
    fun nothingToPanWithoutASize() {
        assertEquals(PanAxis.NONE, PanPlanner.plan(0, 0, w, h, h, PanSpeed.FAST).axis)
        assertEquals(PanAxis.NONE, PanPlanner.plan(3000, 4000, 0, 0, 0, PanSpeed.FAST).axis)
    }
}
