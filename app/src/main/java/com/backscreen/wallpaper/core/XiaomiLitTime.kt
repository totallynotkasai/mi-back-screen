package com.backscreen.wallpaper.core

import android.content.Context
import kotlin.concurrent.thread

/**
 * Stays lit for, on the Wallpaper tab: Xiaomi's own back screen timeout (`subscreen_display_time`),
 * set through Shizuku with the command Quick Switch already uses. After it, Xiaomi lowers the
 * brightness for 6 s more, then dims (measured on the phone).
 *
 * Xiaomi's own value is saved the first time it's changed, and As set puts it back (kept, and the
 * keeper kept running, until that write works). While a lend
 * has the timeout raised (Quick Switch's Back screen stays lit), a change here leaves it raised and
 * becomes what the lend puts back; so Quick Switch's As set means what this says. Uninstalling the
 * app leaves Xiaomi's setting as it is, which the Wallpaper help mentions.
 *
 * Runs the shell, so never on the main thread.
 */
object XiaomiLitTime {
    // Xiaomi's timeout is the same for every display, so TweakKind.TIMEOUT doesn't use the one it's given.
    private const val ANY_DISPLAY = -1

    /**
     * Makes Xiaomi's timeout match the setting: after a change, and when the keeper starts or
     * Shizuku connects, in case something reset it. Does nothing while As set, once Xiaomi's own
     * value is back.
     */
    fun apply(context: Context) {
        val chosen = DisplaySettings.litTime(context).ms
        val own = DisplaySettings.xiaomiLitTime(context)
        if (chosen == null && own == null) return
        val kind = TweakKind.TIMEOUT
        val current = kind.read(ANY_DISPLAY) ?: return BackScreen.log("Stays lit for: couldn't read Xiaomi's timeout; trying later")
        val saved = RearTweaks.saved(context, kind)
        val xiaomi = TweakRules.xiaomiOwn(own, saved, current.value)
        // Saved before Xiaomi's value is first changed, and forgotten once it's back.
        if (chosen != null && own == null) DisplaySettings.saveXiaomiLitTime(context, Saved(xiaomi))
        val base = chosen?.toString() ?: xiaomi
        val done = when (val change = TweakRules.baseChanged(saved, current.value, base)) {
            is BaseChange.Write -> {
                val result = kind.write(ANY_DISPLAY, base)
                BackScreen.log("Stays lit for: ${base ?: "unset"} (was ${current.value ?: "unset"}): $result")
                result == "ok"
            }
            is BaseChange.PutBackLater -> {
                RearTweaks.replace(context, kind, change.tweak)
                BackScreen.log("Stays lit for: ${base ?: "unset"} once the app on the back screen leaves")
                true
            }
            BaseChange.None -> true
        }
        // Xiaomi's own value is forgotten only once it's back (or a lend will put it back), so a
        // failed write is tried again: the keeper runs until it's done (see owed).
        if (chosen == null && done) DisplaySettings.saveXiaomiLitTime(context, null)
    }

    /** As set is chosen but Xiaomi's own value isn't back yet. Any thread. */
    fun owed(context: Context) = DisplaySettings.litTime(context).ms == null && DisplaySettings.xiaomiLitTime(context) != null

    /** Xiaomi's own timeout in ms, for As set's chip; null if it can't be read. Shell thread. */
    fun xiaomiMs(context: Context): Int? {
        DisplaySettings.xiaomiLitTime(context)?.let { return it.value?.toIntOrNull() }
        val current = TweakKind.TIMEOUT.read(ANY_DISPLAY) ?: return null
        return TweakRules.xiaomiOwn(null, RearTweaks.saved(context, TweakKind.TIMEOUT), current.value)?.toIntOrNull()
    }

    /**
     * Stays lit for changed: apply it on the keeper's shell thread if it's running, so it's in
     * turn with a lend. Otherwise here; and if Xiaomi's own value couldn't be put back, the keeper
     * starts to try again.
     */
    fun changed(context: Context) {
        val app = context.applicationContext
        KeeperService.instance?.let { return it.checkLitTime() }
        thread {
            apply(app)
            if (!owed(app)) return@thread
            try {
                KeeperService.start(app, KeeperService.ACTION_UPDATE)
            } catch (e: IllegalStateException) {
                BackScreen.log("Stays lit for: couldn't start the keeper to try again: ${e.message}")
            }
        }
    }
}
