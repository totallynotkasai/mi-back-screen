package com.backscreen.wallpaper.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.backscreen.wallpaper.R
import kotlin.math.roundToInt

/** Where the power comes from. */
enum class PowerSource { WIRED, WIRELESS }

/** What the charging animation shows: the battery's level, 0 to 100, and how it's charging. */
data class ChargingStatus(
    val level: Int,
    val source: PowerSource = PowerSource.WIRED,
    val full: Boolean = false,
) {
    /** The line under the ring. */
    val label: Int
        get() = when {
            full -> R.string.charged
            source == PowerSource.WIRELESS -> R.string.charging_wireless
            else -> R.string.charging
        }

    companion object {
        /**
         * From the battery's own figures ([BatteryManager]'s extras). "Fully charged" goes by
         * the level alone: this phone reports 100% as still charging, and a charge limit can
         * report full below it.
         */
        fun of(level: Int, scale: Int, plugged: Int): ChargingStatus {
            val percent = if (scale > 0) (level * 100f / scale).roundToInt().coerceIn(0, 100) else 0
            return ChargingStatus(
                level = percent,
                source = if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) PowerSource.WIRELESS else PowerSource.WIRED,
                full = plugged != 0 && percent == 100,
            )
        }

        /** The battery now, from the system's last battery broadcast (no permission needed). */
        fun read(context: Context): ChargingStatus? {
            val intent = lastBroadcast(context) ?: return null
            return of(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
                intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            )
        }

        /** Whether it's plugged in now, wired or wireless: the Edge glow stays faint while it is. */
        fun isPlugged(context: Context) = (lastBroadcast(context)?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        private fun lastBroadcast(context: Context) =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
}
