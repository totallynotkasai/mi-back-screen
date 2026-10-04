package com.backscreen.wallpaper.mirror

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.Display
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.Lend
import com.backscreen.wallpaper.core.LendReason
import com.backscreen.wallpaper.core.RearCommands
import com.backscreen.wallpaper.core.RearOwner
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.core.RearTweaks
import com.backscreen.wallpaper.core.TaskList
import com.backscreen.wallpaper.core.TweakKind
import com.backscreen.wallpaper.core.TweakRules
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Quick Switch: moves the app you're using to the back screen, and brings it back. KeeperService
 * runs it, since that owns the back screen; the tile, the notification and the Mirror tab ask.
 *
 * Sending: find the app in front on the main screen, record the lend (so nothing of ours covers
 * it), move its task, set how long the back screen stays lit (and its size and way up, if
 * chosen) through [RearTweaks], restart Xiaomi's back screen app once if its launcher is
 * underneath, close the shade, and light the back screen once that won't bring up HyperOS's
 * cover ([KeeperService.lightForLend]).
 *
 * Bringing back: put Xiaomi's settings back, then move the task to the main screen. If the app
 * closes or leaves by itself, the keeper's watchdog notices and ends the lend.
 */
class QuickSwitch(private val keeper: KeeperService) {

    /** What the tile says for a moment after a tap that couldn't do anything, or null. */
    val notice: Int?
        get() = noticeText.takeIf { SystemClock.elapsedRealtime() < noticeUntil }

    private var noticeText: Int? = null
    private var noticeUntil = 0L

    /** The app Quick Switch has on the back screen, if any. */
    val lend: Lend?
        get() = (RearState.owner(keeper) as? RearOwner.Lent)?.lend?.takeIf { it.reason == LendReason.QUICK_SWITCH }

    /** The tile: send the app in front, or bring back the one that's there. */
    fun toggle() {
        if (lend != null) bringBack("the tile") else send()
    }

    /** Sends the app in front on the main screen to the back screen. */
    fun send() {
        if (!MirrorSettings.isEnabled(keeper)) return say(R.string.qs_off)
        if (RearState.owner(keeper) is RearOwner.Lent) return say(R.string.qs_busy)
        val rear = keeper.rearDisplayId() ?: return say(R.string.qs_no_back_screen)
        keeper.shell(onUnavailable = { say(R.string.need_shizuku) }) {
            val app = RearCommands.taskList()?.let { TaskList.appToSend(it, keeper.packageName) }
            if (app == null) {
                BackScreen.log("Quick Switch: no app open to send")
                BackScreen.mainHandler.post { say(R.string.qs_no_app) }
                return@shell
            }
            BackScreen.log("Quick Switch: close the shade: ${RearCommands.collapseShade()}")
            AppLabels.load(keeper, app.packageName)
            BackScreen.mainHandler.post {
                // Recorded first, so the keeper doesn't put the wallpaper back over it as it arrives.
                if (!keeper.onLent(Lend(LendReason.QUICK_SWITCH, app.id, app.packageName))) return@post
                keeper.shell(onUnavailable = { keeper.onLendEnded() }) { move(app, rear) }
            }
        }
    }

    /** Shell thread. */
    private fun move(app: TaskList.Task, rear: Int) {
        val result = RearCommands.moveTaskToDisplay(app.id, rear)
        BackScreen.log("Quick Switch: ${app.packageName} to the back screen: $result")
        if (result != "ok") {
            BackScreen.mainHandler.post {
                keeper.onLendEnded()
                say(R.string.qs_failed)
            }
            return
        }
        applyTweaks(rear)
        val restarted = restartXiaomiIfUnder(app, rear)
        BackScreen.mainHandler.post { keeper.lightForLend(settleMs = if (restarted) XIAOMI_SETTLE_MS else 0L) }
    }

    /**
     * With the wallpaper off, Xiaomi's launcher is under the app, and once it exists Xiaomi
     * removes whatever is on top each time the back screen dims (plan §12.2). Restarting Xiaomi's
     * app once, with the app in front, brings it back without its launcher, as the wallpaper
     * does. Not while the launcher is in front: Android would start it again at once. Shell thread.
     */
    private fun restartXiaomiIfUnder(app: TaskList.Task, rear: Int): Boolean {
        val packages = RearCommands.taskList()?.let { TaskList.packagesOn(it, rear) } ?: return false
        if (packages.firstOrNull() != app.packageName || RearCommands.XIAOMI_REAR_PACKAGE !in packages) return false
        BackScreen.log("Restart Xiaomi back screen app, without its launcher: ${RearCommands.restartXiaomiRearApp()}")
        return true
    }

    /**
     * Sets Xiaomi's timeout to how long you chose to keep the back screen lit, and its size and
     * way up if you chose those, or puts each back if not. Shell thread.
     */
    private fun applyTweaks(rear: Int) {
        val keepLit = MirrorSettings.keepLit(keeper).ms
        if (keepLit == null) {
            RearTweaks.putBack(keeper, TweakKind.TIMEOUT, rear)
        } else {
            RearTweaks.set(keeper, TweakKind.TIMEOUT, rear) { _, _ -> keepLit.toString() }
        }
        val size = MirrorSettings.size(keeper)
        if (size == AppSize.DEFAULT) {
            RearTweaks.putBack(keeper, TweakKind.DENSITY, rear)
        } else {
            RearTweaks.set(keeper, TweakKind.DENSITY, rear) { original, raw ->
                // Smaller than Xiaomi's own density (260), or the panel's if there were none.
                val base = original?.toIntOrNull() ?: TweakRules.physicalDensity(raw) ?: return@set null
                (base * size.scale).roundToInt().toString()
            }
        }
        when (MirrorSettings.orientation(keeper)) {
            RearOrientation.LANDSCAPE -> RearTweaks.putBack(keeper, TweakKind.ROTATION, rear)
            RearOrientation.PORTRAIT ->
                RearTweaks.set(keeper, TweakKind.ROTATION, rear) { _, _ -> "lock $PORTRAIT_ROTATION|enabled" }
        }
    }

    /** A Mirror option changed: an app that's there takes it at once. */
    fun settingsChanged() {
        if (lend == null) return
        val rear = keeper.rearDisplayId() ?: return
        keeper.shell(onUnavailable = {}) { applyTweaks(rear) }
    }

    /** Brings the app back to the main screen, putting Xiaomi's settings back first. [why] is for the log. */
    fun bringBack(why: String) {
        val lend = lend ?: return
        // The back screen is display 1 on the 17 Pro Max, if it can't be found just now.
        val rear = keeper.rearDisplayId() ?: 1
        BackScreen.log("Quick Switch: bringing ${lend.packageName} back ($why)")
        keeper.shell(onUnavailable = { say(R.string.need_shizuku) }) {
            // First, so the app leaves at the size and way up it came, and the wallpaper
            // underneath shows again as it was.
            RearTweaks.putBackAll(keeper, rear)
            val result = RearCommands.moveTaskToDisplay(lend.taskId, Display.DEFAULT_DISPLAY)
            BackScreen.log("Quick Switch: ${lend.packageName} back to the main screen: $result")
            // From the tile or the notification, the shade would otherwise stay over the app.
            RearCommands.collapseShade()
            BackScreen.mainHandler.post { keeper.onLendEnded() }
        }
    }

    /** Shows [text] on the tile for a moment. */
    private fun say(text: Int) {
        noticeText = text
        noticeUntil = SystemClock.elapsedRealtime() + NOTICE_MS
        QuickSwitchTile.requestUpdate(keeper)
        BackScreen.mainHandler.postDelayed({ QuickSwitchTile.requestUpdate(keeper) }, NOTICE_MS + 50)
    }

    companion object {
        private const val NOTICE_MS = 3000L

        // Xiaomi's app is back within 0.5 s of a restart, and the back screen settles a moment later.
        private const val XIAOMI_SETTLE_MS = 1500L

        /**
         * Portrait is the back screen turned so the camera is at the top: rotation 3 (270°) from
         * its natural landscape, where the camera is on the left.
         */
        const val PORTRAIT_ROTATION = 3
    }
}

/**
 * App names, for "YouTube is on the back screen". The manifest's `<queries>` lets the app see
 * apps that have a launcher icon, which every app you can send does.
 */
object AppLabels {
    private val labels = ConcurrentHashMap<String, String>()

    /** Looks up [packageName]'s name if it isn't known yet. Not on the main thread. */
    fun load(context: Context, packageName: String) {
        if (labels.containsKey(packageName)) return
        val pm = context.packageManager
        try {
            labels[packageName] = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            BackScreen.trace("No name for $packageName")
        }
    }

    /** [packageName]'s name, looked up now if it hasn't been, or the package name if there's none. */
    fun get(context: Context, packageName: String): String {
        labels[packageName]?.let { return it }
        load(context, packageName)
        return labels[packageName] ?: packageName
    }
}
