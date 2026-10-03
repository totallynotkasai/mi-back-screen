package com.backscreen.wallpaper.core

/**
 * Decides whether something that wants to be seen, a charging animation now and notifications
 * later, may light up the back screen. A wake key lights it at full brightness for about 16 s,
 * so a burst of them would keep it lit: there's no wake while it's lit already (waking a lit
 * back screen doesn't keep it on any longer), or within [minGapMs] of the last one. Times are
 * from [android.os.SystemClock.elapsedRealtime]. Plain Kotlin, so it's unit tested.
 */
class WakeThrottle(private val minGapMs: Long = MIN_GAP_MS) {
    private var last: Long? = null

    /** Whether to wake it now; if so, it counts as woken. */
    fun allow(now: Long, lit: Boolean): Boolean {
        if (lit) return false
        val last = last
        if (last != null && now - last < minGapMs) return false
        this.last = now
        return true
    }

    /** It was woken anyway, because you asked for it (a setting changed, or Play). */
    fun woke(now: Long) {
        last = now
    }

    companion object {
        const val MIN_GAP_MS = 10_000L
    }
}
