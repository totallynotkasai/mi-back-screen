package com.backscreen.wallpaper.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.backscreen.wallpaper.core.BackScreen

/**
 * Whether a plug-in plays the charging animation. Plugging in and out quickly, or a loose
 * cable, mustn't stack animations or start one every second: a plug-in plays only if the last
 * one played at least [minGapMs] ago, longer than an animation lasts. Times are from
 * [SystemClock.elapsedRealtime]. Plain Kotlin, so it's unit tested.
 */
class ChargingGate(private val minGapMs: Long = MIN_GAP_MS) {
    private var last: Long? = null

    /** You plugged in at [now]: whether to play. */
    fun onPlugged(now: Long): Boolean {
        val last = last
        if (last != null && now - last < minGapMs) return false
        this.last = now
        return true
    }

    companion object {
        const val MIN_GAP_MS = 5000L
    }
}

/**
 * Hears you plug in and unplug, while KeeperService runs: since Android 8, only a receiver
 * registered by a running app gets these. A plug-in is passed on with the battery's level and
 * where the power comes from, unless [ChargingGate] holds it back; then [onHeldBack] hears of it,
 * since it's charging all the same (the Edge glow's faint glow).
 */
class ChargingMonitor(
    private val context: Context,
    private val onPlugged: (ChargingStatus) -> Unit,
    private val onUnplugged: () -> Unit,
    private val onHeldBack: () -> Unit,
) {
    private val gate = ChargingGate()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    if (!BatterySettings.isEnabled(context)) return
                    if (!gate.onPlugged(SystemClock.elapsedRealtime())) {
                        BackScreen.log("Plugged in again within moments; no animation")
                        onHeldBack()
                        return
                    }
                    onPlugged(ChargingStatus.read(context) ?: return)
                }
                Intent.ACTION_POWER_DISCONNECTED -> onUnplugged()
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // Only the system sends these, and it reaches receivers that aren't exported.
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        context.unregisterReceiver(receiver)
    }
}
