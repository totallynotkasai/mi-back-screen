package com.backscreen.wallpaper.battery

import kotlin.math.roundToInt

/**
 * One moment of the charging animation. Every part runs 0 to 1, except [scale] and [percent].
 *
 * @property veil how much of the dark (or light) veil over the wallpaper shows
 * @property content how solid the ring, bolt and text are
 * @property scale the ring, bolt and text's size, settling to 1
 * @property boltTrace how much of the bolt's outline is drawn
 * @property boltFill how solid its fill is
 * @property ring how far round the ring is filled, as a share of a full turn
 * @property percent the number in the ring
 * @property label how solid the line under the ring is; it also rises into place as it shows
 */
data class ChargingFrame(
    val veil: Float,
    val content: Float,
    val scale: Float,
    val boltTrace: Float,
    val boltFill: Float,
    val ring: Float,
    val percent: Int,
    val label: Float,
)

/**
 * The charging animation, as a function of time: the wallpaper dims, a bolt draws in, the ring
 * fills to the battery's level as the number counts up, "Charging" appears under it, and after
 * a moment it all fades back to the wallpaper. About 3.5 s. Plain Kotlin, so it's unit tested.
 */
object ChargingTimeline {
    const val DURATION_MS = 3500L

    // When each part runs, in ms from the start.
    private const val IN_END = 300L
    private const val SCALE_END = 450L
    private const val BOLT_START = 100L
    private const val BOLT_END = 650L
    private const val FILL_START = 450L
    private const val FILL_END = 800L
    private const val RING_START = 250L
    private const val RING_END = 1650L
    private const val LABEL_START = 550L
    private const val LABEL_END = 900L
    private const val OUT_START = 3000L

    private const val START_SCALE = 0.92f

    /** The frame [elapsedMs] into the animation, for a battery at [level] percent. */
    fun frame(elapsedMs: Long, level: Int): ChargingFrame {
        val t = elapsedMs.coerceIn(0L, DURATION_MS)
        val target = level.coerceIn(0, 100)
        val fadeIn = progress(t, 0L, IN_END)
        val fadeOut = 1f - progress(t, OUT_START, DURATION_MS)
        // The ring slows as it reaches the level, and the number keeps pace with it.
        val filled = decelerate(progress(t, RING_START, RING_END))
        return ChargingFrame(
            veil = fadeIn * fadeOut,
            content = fadeIn * fadeOut,
            scale = START_SCALE + (1f - START_SCALE) * decelerate(progress(t, 0L, SCALE_END)),
            boltTrace = smooth(progress(t, BOLT_START, BOLT_END)),
            boltFill = progress(t, FILL_START, FILL_END),
            ring = filled * target / 100f,
            percent = (filled * target).roundToInt(),
            label = smooth(progress(t, LABEL_START, LABEL_END)),
        )
    }

    /**
     * Everything in place and still: the app's preview at rest, and the whole animation while
     * the phone's animations are turned off.
     */
    fun still(level: Int) = frame(OUT_START, level)

    /** How far [t] is from [start] to [end], 0 to 1. */
    private fun progress(t: Long, start: Long, end: Long) = ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)

    private fun decelerate(x: Float): Float {
        val r = 1f - x
        return 1f - r * r * r
    }

    private fun smooth(x: Float) = x * x * (3f - 2f * x)
}
