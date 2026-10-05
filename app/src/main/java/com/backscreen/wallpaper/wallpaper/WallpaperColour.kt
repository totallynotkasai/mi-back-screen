package com.backscreen.wallpaper.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.palette.graphics.Palette
import com.backscreen.wallpaper.core.BackScreen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The charging animation's Auto colour: a vivid colour from the image on the back screen, the way
 * phone themes pick one (AndroidX Palette, worked out on the phone; nothing is sent anywhere),
 * lifted if needed so it reads as a glow on the dark back screen. A GIF gives its first frame.
 * A grey or black-and-white image has none: the charging animation then takes the clock's text
 * colour. The choosing is plain Kotlin, so it's unit tested.
 */
object WallpaperColour {
    // A small copy is plenty for picking colours.
    private const val SAMPLE_SIZE = 112f

    // Less colourful than this is grey, for a glow.
    private const val MIN_SATURATION = 0.25f
    private const val MIN_LIGHTNESS = 0.08f
    private const val MAX_LIGHTNESS = 0.95f

    // Lifted into this range, so it glows rather than smoulders.
    private const val GLOW_SATURATION = 0.6f
    private const val GLOW_LIGHTNESS_MIN = 0.55f
    private const val GLOW_LIGHTNESS_MAX = 0.75f

    /** From [drawable], as decoded for the back screen. Off the main thread; null if it has no colour to speak of. */
    fun of(drawable: Drawable): Int? {
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        val scale = min(1f, SAMPLE_SIZE / max(w, h))
        val bitmap = Bitmap.createBitmap(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()), Bitmap.Config.ARGB_8888)
        return try {
            val bounds = drawable.copyBounds()
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            drawable.bounds = bounds
            val palette = Palette.from(bitmap).generate()
            pick(
                listOfNotNull(
                    palette.vibrantSwatch, palette.lightVibrantSwatch, palette.darkVibrantSwatch,
                    palette.mutedSwatch, palette.lightMutedSwatch, palette.darkMutedSwatch,
                ).map { it.rgb }
            )
        } catch (e: RuntimeException) {
            BackScreen.log("Couldn't pick a colour from the image: ${e.message}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** The first of [candidates] (the most vivid first) with colour enough, lifted to glow; null if none has. */
    fun pick(candidates: List<Int>): Int? = candidates.firstOrNull { color ->
        val (_, s, l) = hsl(color)
        s >= MIN_SATURATION && l in MIN_LIGHTNESS..MAX_LIGHTNESS
    }?.let(::lift)

    /** [color], bright and colourful enough to read as a glow on the back screen, with its hue kept. */
    fun lift(color: Int): Int {
        val (h, s, l) = hsl(color)
        return fromHsl(h, max(s, GLOW_SATURATION), l.coerceIn(GLOW_LIGHTNESS_MIN, GLOW_LIGHTNESS_MAX))
    }

    /** Hue (0 to 360), saturation and lightness (0 to 1) of an opaque [color]. */
    fun hsl(color: Int): Triple<Float, Float, Float> {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val most = max(r, max(g, b))
        val least = min(r, min(g, b))
        val l = (most + least) / 2
        val d = most - least
        if (d == 0f) return Triple(0f, 0f, l)
        val s = d / (1 - abs(2 * l - 1))
        val h = when (most) {
            r -> ((g - b) / d).mod(6f)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return Triple(h, s.coerceIn(0f, 1f), l)
    }

    /** The opaque colour with hue [h] (0 to 360), saturation [s] and lightness [l] (0 to 1). */
    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60).mod(2f) - 1))
        val m = l - c / 2
        val (r, g, b) = when ((h.mod(360f) / 60).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
