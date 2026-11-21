package com.backscreen.wallpaper.core

/** A display the phone has, as [RearDisplayPick] sees it: its id, size in pixels, and whether it's private to an app. */
data class DisplayCandidate(val id: Int, val width: Int, val height: Int, val isPrivate: Boolean = false)

/**
 * Which display is the back screen. Not simply the first "presentation" display, as before:
 * Android lists a cast screen, a USB-C monitor or a simulated display ahead of the phone's own
 * (DisplayManager.getDisplays), so one of those would have got the wallpaper, the wake keys and
 * Quick Switch's size and rotation (seen with a simulated display, HyperOS 3.0.319).
 *
 * The back screen is the display that isn't the main one, isn't private to an app, and has the
 * size of a known rear panel in either orientation. If no size matches, reuse the display
 * found last time if it's still there.
 */
object RearDisplayPick {
    private val rearSizes = setOf(
        976 to 596, // Xiaomi 17 Pro Max
        912 to 596, // Xiaomi 18 Pro
    )

    // Display.DEFAULT_DISPLAY.
    private const val MAIN_DISPLAY = 0

    /** The back screen's id among [candidates], or null if none is; [rememberedId] is the one found last time. */
    fun choose(candidates: List<DisplayCandidate>, rememberedId: Int?): Int? {
        val others = candidates.filter { it.id != MAIN_DISPLAY && !it.isPrivate }
        val sized = others.filter { display ->
            val longSide = maxOf(display.width, display.height)
            val shortSide = minOf(display.width, display.height)
            (longSide to shortSide) in rearSizes
        }
        return sized.firstOrNull { it.id == rememberedId }?.id
            ?: sized.minByOrNull { it.id }?.id
            ?: others.firstOrNull { it.id == rememberedId }?.id
    }
}
