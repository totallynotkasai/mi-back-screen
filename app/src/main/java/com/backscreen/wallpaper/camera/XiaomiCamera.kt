package com.backscreen.wallpaper.camera

import android.os.SystemClock
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.Lend
import com.backscreen.wallpaper.core.LendMisses
import com.backscreen.wallpaper.core.LendReason
import com.backscreen.wallpaper.core.RearCommands
import com.backscreen.wallpaper.core.RearOwner
import com.backscreen.wallpaper.core.RearState

/**
 * When Xiaomi Camera should close because the back screen isn't lit: it has dimmed or gone off
 * since it was lit with the camera up, or it hasn't lit up within [lightWithinMs] of opening.
 * A camera on a dark back screen can't be used, and shouldn't be left running. Times are from
 * [SystemClock.elapsedRealtime]. Plain Kotlin, so it's unit tested.
 */
class CameraDim(private val lightWithinMs: Long = LIGHT_WITHIN_MS) {
    private var openedAt = 0L
    private var seenLit = false

    /** The camera was just opened at [now], with the back screen [lit] or not. */
    fun opened(now: Long, lit: Boolean) {
        openedAt = now
        seenLit = lit
    }

    /** The back screen is [lit] or not at [now]. Null if the camera may stay, or why it should close. */
    fun closeReason(lit: Boolean, now: Long): String? = when {
        lit -> {
            seenLit = true
            null
        }
        seenLit -> "the back screen dimmed"
        now - openedAt >= lightWithinMs -> "the back screen didn't light up"
        else -> null
    }

    companion object {
        // Xiaomi Camera lights the back screen itself as it opens, within about a second.
        const val LIGHT_WITHIN_MS = 5000L
    }
}

/**
 * Xiaomi Camera on the back screen: swipe left on the wallpaper, or Open camera on the Camera
 * tab. KeeperService runs it, since that owns the back screen.
 *
 * Opening: record the lend first (so the keeper doesn't put the wallpaper back over the camera
 * as it arrives), then open Xiaomi Camera there the way Xiaomi's own back screen does (route 1),
 * or with the standard camera intent if that doesn't work (route 2). Xiaomi Camera lights the
 * back screen itself, and shows its own page on the main screen until it closes.
 *
 * Closing: BACK, so Xiaomi Camera closes itself and finishes saving the last photo or video;
 * if it's still there after 3 s, it's stopped. A dimmed back screen takes no keys, so there it's
 * stopped after a second for the last photo to save. It's never moved to the main screen, where it
 * would pop up. It closes whenever the back screen dims or goes off ([CameraDim]), when the back
 * is covered (the keeper's BackCover), from Close camera, and from Xiaomi's back strip, which
 * BACK reaches without us. If it closes by itself (Xiaomi Camera does after a while unused),
 * the keeper's watchdog notices.
 */
class XiaomiCamera(private val keeper: KeeperService) {

    /** Xiaomi Camera's lend of the back screen, if it's there. */
    val lend: Lend?
        get() = (RearState.owner(keeper) as? RearOwner.Lent)?.lend?.takeIf { it.reason == LendReason.CAMERA }

    /** Which way it last opened: 1 (Xiaomi's own command) or 2 (the standard intent), for Diagnostics. */
    var route: Int? = null
        private set

    private val dim = CameraDim()
    private var closing = false
    private val checkLit = Runnable { rearChanged(keeper.isRearLit()) }

    /** Opens it on the back screen. [why] is for the log. */
    fun open(why: String) {
        if (lend != null) return BackScreen.log("Xiaomi Camera is on the back screen already")
        val rear = keeper.rearDisplayId() ?: return BackScreen.log("Camera: no back screen")
        val lend = Lend(LendReason.CAMERA, UNKNOWN_TASK, RearCommands.CAMERA_PACKAGE)
        if (!keeper.onLent(lend)) return BackScreen.log("Camera: another app has the back screen")
        BackScreen.log("Opening Xiaomi Camera on the back screen ($why)")
        closing = false
        watchLight()
        val started = SystemClock.elapsedRealtime()
        keeper.shell(onUnavailable = { keeper.onLendEnded() }) {
            val route = when {
                opened(1, RearCommands.openXiaomiCamera(rear), rear, lend) -> 1
                opened(2, RearCommands.openCameraByIntent(rear), rear, lend) -> 2
                else -> null
            }
            val ms = SystemClock.elapsedRealtime() - started
            BackScreen.mainHandler.post {
                if (route == null) {
                    BackScreen.log("Xiaomi Camera didn't open on the back screen")
                    if (this.lend == lend) keeper.onLendEnded()
                    return@post
                }
                this.route = route
                BackScreen.log("Xiaomi Camera is on the back screen (route $route, $ms ms)")
            }
        }
    }

    /** A lend saved before this process last stopped: the camera may still be up. */
    fun resumed() {
        if (lend != null) watchLight()
    }

    /**
     * Xiaomi Camera came up on the back screen by itself, over the wallpaper: its page on the
     * main screen opens it again when you come back to that page. It's treated as opened here,
     * so the wallpaper doesn't hide a running camera, and it closes the same ways.
     */
    fun adopt() {
        if (lend != null) return
        if (!keeper.onLent(Lend(LendReason.CAMERA, UNKNOWN_TASK, RearCommands.CAMERA_PACKAGE))) return
        BackScreen.log("Xiaomi Camera opened on the back screen by itself; it has the back screen")
        closing = false
        watchLight()
    }

    /** Shell thread: whether [result] opened it, in front on the back screen within a moment. */
    private fun opened(route: Int, result: String, rear: Int, lend: Lend): Boolean {
        BackScreen.log("Open Xiaomi Camera (route $route): $result")
        return result == "ok" && waitUntil(rear, lend, inFront = true, OPEN_WAIT_MS)
    }

    /**
     * Closes it, letting it finish saving. [why] is for the log. On a lit back screen that's
     * BACK, as Xiaomi's back strip sends; a dimmed one takes no keys (checked in Phase 7), so it's
     * stopped after a moment for the last photo to save.
     */
    fun close(why: String) {
        val lend = lend ?: return
        if (closing) return
        closing = true
        BackScreen.mainHandler.removeCallbacks(checkLit)
        // The back screen is display 1 on the 17 Pro Max, if it can't be found just now.
        val rear = keeper.rearDisplayId() ?: 1
        val lit = keeper.isRearLit()
        BackScreen.log("Closing Xiaomi Camera ($why)")
        keeper.shell(onUnavailable = { closing = false }) {
            val gone = if (lit) {
                BackScreen.log("Camera: BACK on the back screen: ${RearCommands.pressBack(rear)}")
                waitUntil(rear, lend, inFront = false, CLOSE_WAIT_MS)
            } else {
                waitUntil(rear, lend, inFront = false, SAVE_WAIT_MS)
            }
            if (!gone) {
                BackScreen.log("Xiaomi Camera is still there; stopping it: ${RearCommands.stopXiaomiCamera()}")
            }
            BackScreen.mainHandler.post {
                closing = false
                if (this.lend == lend) keeper.onLendEnded()
            }
        }
    }

    /** The back screen lit up, dimmed or went off. */
    fun rearChanged(lit: Boolean) {
        if (lend == null || closing) return
        dim.closeReason(lit, SystemClock.elapsedRealtime())?.let(::close)
    }

    /** From now, until the camera closes: it must light up soon, and closes when it dims. */
    private fun watchLight() {
        dim.opened(SystemClock.elapsedRealtime(), keeper.isRearLit())
        BackScreen.mainHandler.removeCallbacks(checkLit)
        BackScreen.mainHandler.postDelayed(checkLit, CameraDim.LIGHT_WITHIN_MS)
    }

    /** The lend ended: nothing more to watch. */
    fun ended() {
        BackScreen.mainHandler.removeCallbacks(checkLit)
    }

    /**
     * Shell thread: waits up to [ms] for the camera to be [inFront] on [rear], or not, looking at
     * the task list every [POLL_MS]. True once it is.
     */
    private fun waitUntil(rear: Int, lend: Lend, inFront: Boolean, ms: Long): Boolean {
        val until = SystemClock.elapsedRealtime() + ms
        while (true) {
            if (LendMisses.inFront(RearCommands.taskList(), rear, lend) == inFront) return true
            if (SystemClock.elapsedRealtime() >= until) return false
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        // Its task isn't known until it's there; the watchdog goes by the package anyway.
        const val UNKNOWN_TASK = -1
        const val OPEN_WAIT_MS = 1500L
        const val CLOSE_WAIT_MS = 3000L

        // A photo taken just before the back screen dimmed is saved within about a second.
        const val SAVE_WAIT_MS = 1000L
        const val POLL_MS = 150L
    }
}
