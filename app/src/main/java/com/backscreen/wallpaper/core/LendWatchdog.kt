package com.backscreen.wallpaper.core

import android.os.SystemClock

/**
 * The rule for when a lend is over: the lent app is no longer in front on the back screen. It
 * was closed, moved back by something else, or sent behind (Xiaomi's back strip sends BACK,
 * and an app's first screen goes behind on BACK rather than closing). Plain Kotlin, so it's unit
 * tested.
 *
 * One miss isn't enough: `am stack list` once left out the back screen's task altogether (plan
 * §12.6). Two in a row are.
 */
class LendMisses(private val limit: Int = MISSES_TO_END) {
    private var misses = 0

    /**
     * One look at the task list: [inFront] if the lent app is in front on the back screen, or
     * null if the list couldn't be read, which counts for nothing. True once the lend is over.
     * [wallpaperShowing]: the wallpaper came back in front on the back screen just now, which is
     * the second sign, so one miss is enough.
     */
    fun check(inFront: Boolean?, wallpaperShowing: Boolean = false): Boolean {
        when (inFront) {
            null -> return false
            true -> misses = 0
            false -> misses++
        }
        return misses >= limit || (inFront == false && wallpaperShowing)
    }

    fun reset() {
        misses = 0
    }

    companion object {
        const val MISSES_TO_END = 2

        /**
         * Whether [lend]'s app is in front on [rearDisplay] in [output] (`am stack list`): its
         * task, or another task of the same app, which it may open. Null if there's no output.
         */
        fun inFront(output: String?, rearDisplay: Int, lend: Lend): Boolean? {
            output ?: return null
            val front = TaskList.tasksOn(output, rearDisplay).firstOrNull() ?: return false
            return front.id == lend.taskId || front.packageName == lend.packageName
        }
    }
}

/**
 * Looks every 3 s, only while an app is lent the back screen, whether it's still there
 * ([LendMisses]), and calls [onGone] when it isn't. The task list is read with [shell], off the
 * main thread; everything else is on the main thread.
 */
class LendWatchdog(
    private val shell: (onUnavailable: () -> Unit, block: () -> Unit) -> Unit,
    private val rearDisplay: () -> Int?,
    private val onGone: (Lend) -> Unit,
) {
    private val misses = LendMisses()
    private var watching: Lend? = null
    private var checking = false
    // The next look was asked for because the wallpaper is showing again (see lookNow).
    private var wallpaperShowing = false
    private val tick = Runnable { check() }

    /** Starts watching [lend], with a first look straight away if [now]: a lend saved before a restart. */
    fun watch(lend: Lend, now: Boolean = false) {
        if (watching == lend) return
        watching = lend
        misses.reset()
        BackScreen.mainHandler.removeCallbacks(tick)
        BackScreen.mainHandler.postDelayed(tick, if (now) 0 else INTERVAL_MS)
    }

    fun stop() {
        watching = null
        BackScreen.mainHandler.removeCallbacks(tick)
    }

    /**
     * The wallpaper came back in front on the back screen while an app is lent: look now rather
     * than at the next tick, and if the app isn't in front, that's enough to end the lend.
     */
    fun lookNow() {
        if (watching == null || checking) return
        wallpaperShowing = true
        BackScreen.mainHandler.removeCallbacks(tick)
        BackScreen.mainHandler.post(tick)
    }

    private fun check() {
        val lend = watching ?: return
        if (checking) return schedule()
        val rear = rearDisplay() ?: return schedule()
        checking = true
        val showing = wallpaperShowing
        wallpaperShowing = false
        val started = SystemClock.elapsedRealtime()
        val unavailable = {
            checking = false
            schedule()
        }
        shell(unavailable) {
            val inFront = LendMisses.inFront(RearCommands.taskList(), rear, lend)
            BackScreen.mainHandler.post {
                checking = false
                if (watching != lend) return@post
                val seen = when (inFront) {
                    true -> "in front"
                    false -> "not in front"
                    null -> "list unread"
                }
                BackScreen.trace("Lend check: ${lend.packageName} $seen (${SystemClock.elapsedRealtime() - started} ms)")
                if (misses.check(inFront, wallpaperShowing = showing)) {
                    stop()
                    onGone(lend)
                } else {
                    schedule()
                }
            }
        }
    }

    private fun schedule() {
        BackScreen.mainHandler.removeCallbacks(tick)
        if (watching != null) BackScreen.mainHandler.postDelayed(tick, INTERVAL_MS)
    }

    private companion object {
        const val INTERVAL_MS = 3000L
    }
}
