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
 *
 * A wireless charger may be reported as one only a moment after the plug-in, so where the power
 * comes from is read again shortly after, and [onSourceChanged] hears if it has changed.
 */
class ChargingMonitor(
    private val context: Context,
    private val onPlugged: (ChargingStatus) -> Unit,
    private val onUnplugged: () -> Unit,
    private val onHeldBack: () -> Unit,
    private val onSourceChanged: (ChargingStatus) -> Unit,
) {
    private val gate = ChargingGate()

    // What the last plug-in said, until it's been read again.
    private var plugged: ChargingStatus? = null
    private val readAgain = Runnable { checkSource() }

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
                    val status = ChargingStatus.read(context) ?: return
                    plugged = status
                    BackScreen.mainHandler.removeCallbacks(readAgain)
                    BackScreen.mainHandler.postDelayed(readAgain, READ_AGAIN_MS)
                    onPlugged(status)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    plugged = null
                    BackScreen.mainHandler.removeCallbacks(readAgain)
                    onUnplugged()
                }
            }
        }
    }

    /** Where the power comes from, read again a moment after the plug-in. */
    private fun checkSource() {
        val first = plugged ?: return
        plugged = null
        if (!ChargingStatus.isPlugged(context)) return
        val now = ChargingStatus.read(context) ?: return
        if (now.source == first.source) return
        BackScreen.log("Charging: ${now.source.name.lowercase()}, known only after plugging in")
        onSourceChanged(now)
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
        BackScreen.mainHandler.removeCallbacks(readAgain)
        context.unregisterReceiver(receiver)
    }

    private companion object {
        const val READ_AGAIN_MS = 700L
    }
}
