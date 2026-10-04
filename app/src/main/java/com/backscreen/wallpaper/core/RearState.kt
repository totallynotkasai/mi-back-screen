package com.backscreen.wallpaper.core

import android.content.Context
import android.provider.Settings
import androidx.annotation.MainThread
import com.backscreen.wallpaper.battery.BatterySettings
import com.backscreen.wallpaper.mirror.MirrorSettings
import com.backscreen.wallpaper.wallpaper.WallpaperSettings

/** Why another app has the back screen. */
enum class LendReason { QUICK_SWITCH, CAMERA }

/** Another app on the back screen: moved there by Quick Switch, or Xiaomi Camera opened there. */
data class Lend(val reason: LendReason, val taskId: Int, val packageName: String)

/** What the host shows while it owns the back screen. */
enum class HostMode {
    /** The images, or black with none, and the clock. */
    WALLPAPER,

    /** Recent notifications, pulled down over the wallpaper. */
    SHADE,
}

/** Who's on the back screen. */
sealed interface RearOwner {
    /** The wallpaper is off: Xiaomi's own back screen. */
    data object Xiaomi : RearOwner

    /** The wallpaper is on: our host window. */
    data class Host(val mode: HostMode) : RearOwner

    /** The wallpaper is off, and the host is up over Xiaomi's screen for a few seconds. */
    data object Popover : RearOwner

    /** Another app is there, and nothing of ours may cover it. */
    data class Lent(val lend: Lend) : RearOwner
}

/** Where something shown on the back screen for a moment, such as charging, goes. */
enum class OverlayRoute {
    /** Into the host that's already up, so it shows at once. */
    HOST,

    /** Over Xiaomi's screen, by putting the host up for a few seconds. */
    POPOVER,

    /** Nowhere: another app has the back screen, or pop-overs are off. */
    DROP,
}

/**
 * Everything that decides who owns the back screen. The Wallpaper switch says who normally
 * does; a lent app, a pop-over or the notification shade change that for a while. A transition
 * that isn't allowed returns null. Plain Kotlin, so the rules are unit tested; [RearState]
 * holds the live one.
 */
data class RearSnapshot(
    val wallpaperOn: Boolean,
    val lend: Lend? = null,
    val popover: Boolean = false,
    val shadeOpen: Boolean = false,
) {
    val owner: RearOwner
        get() = when {
            lend != null -> RearOwner.Lent(lend)
            wallpaperOn -> RearOwner.Host(if (shadeOpen) HostMode.SHADE else HostMode.WALLPAPER)
            popover -> RearOwner.Popover
            else -> RearOwner.Xiaomi
        }

    /** Whether the keeper keeps the wallpaper up: on top of Xiaomi's screen, and put back if closed. */
    val guardsWallpaper get() = owner is RearOwner.Host

    /**
     * Whether KeeperService has anything to do. [batteryOn]: its plug-in receiver needs it
     * running. [mirrorOn]: the Quick Switch tile asks it directly, which is quicker and works
     * where the tile may not start it.
     */
    fun keeperNeeded(batteryOn: Boolean, mirrorOn: Boolean = false) =
        wallpaperOn || batteryOn || mirrorOn || lend != null || popover

    /** Where a charging animation goes; [popoverAllowed] if it may go over Xiaomi's screen. */
    fun routeOverlay(popoverAllowed: Boolean) = when (owner) {
        is RearOwner.Lent -> OverlayRoute.DROP
        is RearOwner.Host, RearOwner.Popover -> OverlayRoute.HOST
        RearOwner.Xiaomi -> if (popoverAllowed) OverlayRoute.POPOVER else OverlayRoute.DROP
    }

    /** The Wallpaper switch changed. Turning it on ends a pop-over; turning it off closes the shade. */
    fun withWallpaper(on: Boolean) = copy(wallpaperOn = on, popover = popover && !on, shadeOpen = shadeOpen && on)

    /** Only over Xiaomi's screen: with the wallpaper on, it goes in the host instead. */
    fun startPopover() = if (owner == RearOwner.Xiaomi) copy(popover = true) else null

    fun endPopover() = copy(popover = false)

    /** Only one app at a time. It goes over a pop-over or the shade, which end. */
    fun lent(lend: Lend) = if (this.lend == null) copy(lend = lend, popover = false, shadeOpen = false) else null

    /**
     * The lent app left. The back screen goes to whichever the Wallpaper switch says now, so a
     * schedule that fired meanwhile counts.
     */
    fun returned() = copy(lend = null)

    fun openShade() = if (owner == RearOwner.Host(HostMode.WALLPAPER)) copy(shadeOpen = true) else null

    fun closeShade() = copy(shadeOpen = false)
}

/**
 * Who's on the back screen now. KeeperService changes it; the host, tile and app read it. The
 * Wallpaper switch is read from its setting each time. A lent app is saved, so restarting this
 * app doesn't "repair" the back screen by covering it; a pop-over and the shade don't outlive
 * the process.
 *
 * A lend is saved with the phone's boot count and forgotten after a restart, since no app's
 * task survives one. Not on BOOT_COMPLETED: on Android 15 and later that also arrives when this
 * app comes back from a force stop, with the lent app still on the back screen (Phase 6).
 */
@MainThread
object RearState {
    private const val PREFS = "rear_state"
    private const val KEY_REASON = "lend_reason"
    private const val KEY_TASK = "lend_task"
    private const val KEY_PACKAGE = "lend_package"
    private const val KEY_BOOT = "lend_boot"

    private var popover = false
    private var shadeOpen = false
    private var lend: Lend? = null
    private var loaded = false

    fun snapshot(context: Context) =
        RearSnapshot(WallpaperSettings.isEnabled(context), loadLend(context), popover, shadeOpen)

    fun owner(context: Context) = snapshot(context).owner

    /** Also while Xiaomi's settings are still changed from an earlier lend, until they're put back. */
    fun keeperNeeded(context: Context) =
        snapshot(context).keeperNeeded(BatterySettings.isEnabled(context), MirrorSettings.isEnabled(context)) ||
            RearTweaks.pending(context)

    fun wallpaperChanged(context: Context, on: Boolean) = apply(context, snapshot(context).withWallpaper(on))

    fun startPopover(context: Context) = apply(context, snapshot(context).startPopover())

    fun endPopover(context: Context) = apply(context, snapshot(context).endPopover())

    fun lend(context: Context, lend: Lend) = apply(context, snapshot(context).lent(lend))

    fun endLend(context: Context) = apply(context, snapshot(context).returned())

    fun openShade(context: Context) = apply(context, snapshot(context).openShade())

    fun closeShade(context: Context) = apply(context, snapshot(context).closeShade())

    /** Takes on [next], or returns false if the change isn't allowed. */
    private fun apply(context: Context, next: RearSnapshot?): Boolean {
        next ?: return false
        popover = next.popover
        shadeOpen = next.shadeOpen
        if (next.lend != loadLend(context)) saveLend(context, next.lend)
        return true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadLend(context: Context): Lend? {
        if (loaded) return lend
        val p = prefs(context)
        val reason = LendReason.entries.firstOrNull { it.name == p.getString(KEY_REASON, null) }
        val pkg = p.getString(KEY_PACKAGE, null)
        val sameBoot = p.getInt(KEY_BOOT, UNKNOWN_BOOT) == bootCount(context)
        lend = if (reason != null && pkg != null && sameBoot) Lend(reason, p.getInt(KEY_TASK, -1), pkg) else null
        loaded = true
        return lend
    }

    private fun saveLend(context: Context, value: Lend?) {
        lend = value
        loaded = true
        prefs(context).edit().apply {
            if (value == null) {
                clear()
            } else {
                putString(KEY_REASON, value.reason.name)
                putInt(KEY_TASK, value.taskId)
                putString(KEY_PACKAGE, value.packageName)
                putInt(KEY_BOOT, bootCount(context))
            }
        }.apply()
    }

    /** How many times the phone has started; any app may read it. */
    private fun bootCount(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UNKNOWN_BOOT)

    private const val UNKNOWN_BOOT = -1
}
