package com.backscreen.wallpaper.mirror

/**
 * Bring back when the phone locks: an app Quick Switch sent comes back to the main screen, behind
 * the lock screen, once the locked phone's back screen has been dark a few seconds. Not at the
 * lock itself: sending an app and pressing Power is how Quick Switch is used, and you go on using
 * the app on the back screen until that times out. Plain Kotlin, so it's unit tested.
 */
object LockReturn {
    /** How long the back screen stays dark first. */
    const val DARK_MS = 5_000L

    /**
     * Whether to bring the app back now. [on]: the option, with Quick Switch on; [locked]: the
     * phone counts as locked ([com.backscreen.wallpaper.core.PhoneLock.isLocked]); [darkForMs]: how
     * long the back screen has been dimmed or off since it was last lit, counting only since the
     * phone locked and the app was sent; [lendAgeMs]: how long ago it was sent; [settleMs]: how
     * long Xiaomi's back screen can take to settle after a send, dimming for a moment on the way.
     */
    fun shouldBringBack(on: Boolean, locked: Boolean, rearLit: Boolean, darkForMs: Long, lendAgeMs: Long, settleMs: Long) =
        on && locked && !rearLit && darkForMs >= DARK_MS && lendAgeMs >= settleMs

    /** How long until [shouldBringBack] could be true, if nothing else changes. */
    fun waitMs(darkForMs: Long, lendAgeMs: Long, settleMs: Long) = maxOf(DARK_MS - darkForMs, settleMs - lendAgeMs, 0L)
}
