package com.backscreen.wallpaper.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperColourTest {

    @Test
    fun aGreyOrBlackAndWhiteImageHasNoColourSoTheClocksIsUsed() {
        val greys = listOf(0xFF808080.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF7A7D82.toInt())
        assertNull(WallpaperColour.pick(greys))
        // No images, or nothing Palette could find.
        assertNull(WallpaperColour.pick(emptyList()))
        // A colour that's almost black has nothing to glow with either.
        assertNull(WallpaperColour.pick(listOf(0xFF0A0002.toInt())))
    }

    @Test
    fun theMostVividColourIsLiftedToGlowWithItsHueKept() {
        // A dark navy, past a grey: picked, and brightened.
        val navy = 0xFF102060.toInt()
        val picked = WallpaperColour.pick(listOf(0xFF808080.toInt(), navy))!!
        val (h0, _, _) = WallpaperColour.hsl(navy)
        val (h, s, l) = WallpaperColour.hsl(picked)
        assertEquals(h0, h, 2f)
        assertTrue("saturation $s", s >= 0.59f)
        assertTrue("lightness $l", l in 0.54f..0.76f)
        // A bright colour stays much as it is.
        val pink = 0xFFE91E63.toInt()
        val (ph, ps, pl) = WallpaperColour.hsl(WallpaperColour.lift(pink))
        assertEquals(WallpaperColour.hsl(pink).first, ph, 2f)
        assertEquals(WallpaperColour.hsl(pink).second, ps, 0.02f)
        assertEquals(0.55f, pl, 0.02f)
    }
}
