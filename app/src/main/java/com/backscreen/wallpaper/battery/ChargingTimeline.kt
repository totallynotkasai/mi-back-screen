package com.backscreen.wallpaper.battery

import kotlin.math.roundToInt

/** One moment of one style of the charging animation. */
sealed interface StyleFrame

/**
 * One moment of the Ring. Every part runs 0 to 1, except [scale] and [percent].
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
) : StyleFrame

/**
 * One moment of the Edge glow. There's no veil, and the clock stays.
 *
 * @property edge how solid the soft glow round the whole edge is, 0 to 1
 * @property filled how far round the brighter part reaches, clockwise from the top left, as a
 *   share of the way round: the battery's level
 * @property fill how solid the brighter part is
 * @property head how solid the bright spot at its end is
 */
data class GlowFrame(val edge: Float, val filled: Float, val fill: Float, val head: Float) : StyleFrame

/**
 * One moment of Minimal: a small bolt and the level. There's no veil, and the clock stays.
 *
 * @property content how solid it is
 * @property rise how far it has risen into place, 0 to 1
 * @property percent the number, counting up
 */
data class MinimalFrame(val content: Float, val rise: Float, val percent: Int) : StyleFrame

/**
 * The charging animation, as a function of time, for each style. About 3.5 s each. Plain Kotlin,
 * so it's unit tested.
 *
 * - Ring ([frame]): the wallpaper dims, a bolt draws in, the ring fills to the battery's level
 *   as the number counts up, "Charging" appears under it, and after a moment it all fades back
 *   to the wallpaper.
 * - Edge glow ([glow]): a soft glow fades in round the edge, a brighter head runs round
 *   clockwise from the top left as far as the level, then the whole edge settles to a faint
 *   glow that stays while it charges; or fades away, over Xiaomi's screen.
 * - Minimal ([minimal]): a bolt and the number rise into place as it counts up, and fade.
 */
object ChargingTimeline {
    const val DURATION_MS = 3500L

    /** How solid the Edge glow is while it plays, and once it has settled while charging. */
    const val GLOW_FULL = 0.55f
    const val GLOW_FAINT = 0.3f

    // The Edge glow's parts, in ms from the start.
    private const val GLOW_IN_END = 500L
    private const val HEAD_START = 300L
    private const val HEAD_IN_END = 450L
    private const val HEAD_END = 2000L
    private const val HEAD_OUT_END = 2600L
    private const val SETTLE_START = 2600L

    // Minimal's parts.
    private const val MINIMAL_RISE_END = 400L
    private const val COUNT_START = 200L
    private const val COUNT_END = 1400L

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

    /**
     * The Edge glow [elapsedMs] in, for a battery at [level] percent. With [stayFaint] it ends
     * at [GLOW_FAINT] round the whole edge, as it stays while charging; without, at nothing.
     */
    fun glow(elapsedMs: Long, level: Int, stayFaint: Boolean): GlowFrame {
        val t = elapsedMs.coerceIn(0L, DURATION_MS)
        val target = level.coerceIn(0, 100) / 100f
        val settle = smooth(progress(t, SETTLE_START, DURATION_MS))
        val end = if (stayFaint) GLOW_FAINT else 0f
        return GlowFrame(
            edge = GLOW_FULL * smooth(progress(t, 0L, GLOW_IN_END)) * (1 - settle) + end * settle,
            filled = decelerate(progress(t, HEAD_START, HEAD_END)) * target,
            fill = progress(t, HEAD_START, HEAD_IN_END) * (1 - settle),
            head = progress(t, HEAD_START, HEAD_IN_END) * (1 - progress(t, HEAD_END, HEAD_OUT_END)),
        )
    }

    /** The Edge glow at its fullest, still: the app's preview at rest, and while animations are off. */
    fun stillGlow(level: Int) = glow(HEAD_END, level, stayFaint = false)

    /** The faint glow on its own, as it stays while charging. */
    val faint = GlowFrame(GLOW_FAINT, 0f, 0f, 0f)

    /** Minimal [elapsedMs] in, for a battery at [level] percent. */
    fun minimal(elapsedMs: Long, level: Int): MinimalFrame {
        val t = elapsedMs.coerceIn(0L, DURATION_MS)
        val counted = decelerate(progress(t, COUNT_START, COUNT_END))
        return MinimalFrame(
            content = progress(t, 0L, IN_END) * (1f - progress(t, OUT_START, DURATION_MS)),
            rise = decelerate(progress(t, 0L, MINIMAL_RISE_END)),
            percent = (counted * level.coerceIn(0, 100)).roundToInt(),
        )
    }

    /** Minimal in place, still. */
    fun stillMinimal(level: Int) = minimal(OUT_START, level)

    /** [style] [elapsedMs] in; [stayFaint] is the Edge glow's. */
    fun frame(style: ChargingStyle, elapsedMs: Long, level: Int, stayFaint: Boolean): StyleFrame = when (style) {
        ChargingStyle.RING -> frame(elapsedMs, level)
        ChargingStyle.EDGE_GLOW -> glow(elapsedMs, level, stayFaint)
        ChargingStyle.MINIMAL -> minimal(elapsedMs, level)
    }

    /** [style] in place and still. */
    fun still(style: ChargingStyle, level: Int): StyleFrame = when (style) {
        ChargingStyle.RING -> still(level)
        ChargingStyle.EDGE_GLOW -> stillGlow(level)
        ChargingStyle.MINIMAL -> stillMinimal(level)
    }

    /** How far [t] is from [start] to [end], 0 to 1. */
    private fun progress(t: Long, start: Long, end: Long) = ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)

    private fun decelerate(x: Float): Float {
        val r = 1f - x
        return 1f - r * r * r
    }

    private fun smooth(x: Float) = x * x * (3f - 2f * x)
}
