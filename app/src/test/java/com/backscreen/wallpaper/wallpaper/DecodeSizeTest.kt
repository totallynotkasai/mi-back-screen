package com.backscreen.wallpaper.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeSizeTest {

    // The back screen in its natural landscape.
    private val sw = 976
    private val sh = 596

    @Test
    fun aLargePhotoIsDownscaledToWhatIsShown() {
        // 4000 x 3000 filling 976 x 596: the width decides, 976 / 4000.
        assertEquals(DecodeSize(976, 732), DecodeSize.of(4000, 3000, sw, sh, Scaling.FILL))
        // Fitted, the height decides.
        assertEquals(DecodeSize(795, 596), DecodeSize.of(4000, 3000, sw, sh, Scaling.FIT))
    }

    @Test
    fun aSmallImageIsDecodedAsItIs() {
        assertEquals(DecodeSize(500, 300), DecodeSize.of(500, 300, sw, sh, Scaling.FILL))
        assertEquals(DecodeSize(500, 300), DecodeSize.of(500, 300, sw, sh, Scaling.FIT))
    }

    @Test
    fun anExtremePanoramaIsCapped() {
        // Filling the screen's height it would stay 100000 x 500: 200 MB.
        val size = DecodeSize.of(100_000, 500, sw, sh, Scaling.FILL)
        assertEquals(DecodeSize.MAX_SIDE, size.width)
        assertEquals(40, size.height)

        // A huge square photo, fitted: the screen's size is already far under the caps.
        assertEquals(DecodeSize(596, 596), DecodeSize.of(20_000, 20_000, sw, sh, Scaling.FIT))

        // Big on both sides but not past either side's cap: the pixel cap decides.
        val tall = DecodeSize.of(8000, 8000, 100, 8000, Scaling.FILL)
        assertTrue(tall.width.toLong() * tall.height <= DecodeSize.MAX_PIXELS)
        assertEquals(4000, tall.width)
    }

    @Test
    fun actualSizeKeepsTheMiddle() {
        // Centred: 4000 x 3000 keeps the screen-sized middle.
        assertEquals(
            DecodeSize(976, 596, crop = true, left = 1512, top = 1202),
            DecodeSize.of(4000, 3000, sw, sh, Scaling.NONE),
        )
        // Smaller than the screen: all of it.
        assertEquals(DecodeSize(500, 300, crop = true), DecodeSize.of(500, 300, sw, sh, Scaling.NONE))
        // A panorama: as tall as it is, as wide as the screen.
        assertEquals(
            DecodeSize(976, 500, crop = true, left = 49_512),
            DecodeSize.of(100_000, 500, sw, sh, Scaling.NONE),
        )
    }
}
