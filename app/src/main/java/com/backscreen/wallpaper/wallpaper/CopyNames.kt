package com.backscreen.wallpaper.wallpaper

import java.util.Locale

/**
 * Names for the app's copies of picked images: `img_<ms>_<index>`, so they sort in the order
 * they were added, and a new pick never reuses a name the back screen may still be showing
 * (it would take the new image for the one it has). Copies from before 2.0.2 (`img_000`…)
 * sort first.
 */
object CopyNames {
    private val NAME = Regex("img_(\\d{13})_\\d{3}")

    /** [count] new names after every name in [existing], made at [now] (ms). */
    fun next(existing: Collection<String>, count: Int, now: Long): List<String> {
        // After the newest, even if the clock has gone back since.
        val newest = existing.mapNotNull { NAME.matchEntire(it)?.groupValues?.get(1)?.toLong() }.maxOrNull()
        val start = if (newest != null && newest >= now) newest + 1 else now
        return List(count) { i -> "img_%013d_%03d".format(Locale.ROOT, start + i / 1000, i % 1000) }
    }
}
