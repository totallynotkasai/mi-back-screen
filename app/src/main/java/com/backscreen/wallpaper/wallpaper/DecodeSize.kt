package com.backscreen.wallpaper.wallpaper

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The size to decode an image at for the back screen: no bigger than it's shown, and never so
 * big it can't be held or drawn. An extreme panorama shown at the screen's height would otherwise
 * decode to hundreds of MB, and a bitmap over 100 MB can't be drawn at all.
 *
 * With [crop] (Actual size), only the [width] x [height] part at [left], [top] of the image is
 * decoded, at its own size; otherwise all of it, scaled to [width] x [height].
 */
data class DecodeSize(
    val width: Int,
    val height: Int,
    val crop: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
) {
    companion object {
        /** About 64 MB at 4 bytes a pixel. */
        const val MAX_PIXELS = 16_000_000L
        const val MAX_SIDE = 8192

        /** For a [w] x [h] image on a [screenW] x [screenH] screen, fitted with [scaling]. */
        fun of(w: Int, h: Int, screenW: Int, screenH: Int, scaling: Scaling): DecodeSize {
            if (scaling == Scaling.NONE) {
                // Shown at its own size, centred: keep just the middle that fits on the screen.
                var cw = min(min(w, screenW), MAX_SIDE)
                var ch = min(min(h, screenH), MAX_SIDE)
                val over = sqrt(MAX_PIXELS.toDouble() / (cw.toLong() * ch))
                if (over < 1) {
                    cw = capped(cw, over)
                    ch = capped(ch, over)
                }
                return DecodeSize(cw, ch, crop = true, left = (w - cw) / 2, top = (h - ch) / 2)
            }
            // Downscale large images to just what's shown; saves memory and battery.
            val sx = screenW.toDouble() / w
            val sy = screenH.toDouble() / h
            val shown = if (scaling == Scaling.FIT) min(sx, sy) else max(sx, sy)
            // Then no more than the caps allow, however it's shown.
            val cap = minOf(
                MAX_SIDE.toDouble() / w, MAX_SIDE.toDouble() / h, sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h)),
            )
            return when {
                cap < 1 && cap < shown -> DecodeSize(capped(w, cap), capped(h, cap))
                shown < 1 -> DecodeSize(max(1, (w * shown).roundToInt()), max(1, (h * shown).roundToInt()))
                else -> DecodeSize(w, h)
            }
        }

        /** [side] at [scale], rounded down so it stays under the cap (with a little slack for doubles: 8192.0 / w * w). */
        private fun capped(side: Int, scale: Double) = max(1, floor(side * scale + 1e-6).toInt())
    }
}
