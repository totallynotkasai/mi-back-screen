package com.backscreen.wallpaper.battery

import android.os.BatteryManager.BATTERY_PLUGGED_AC
import android.os.BatteryManager.BATTERY_PLUGGED_USB
import android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS
import com.backscreen.wallpaper.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ChargingStatusTest {

    @Test
    fun aCableIsCharging() {
        val usb = ChargingStatus.of(level = 64, scale = 100, plugged = BATTERY_PLUGGED_USB)
        assertEquals(ChargingStatus(64, PowerSource.WIRED), usb)
        assertEquals(R.string.charging, usb.label)
        assertEquals(PowerSource.WIRED, ChargingStatus.of(64, 100, BATTERY_PLUGGED_AC).source)
    }

    @Test
    fun aChargingPadIsWirelessCharging() {
        val pad = ChargingStatus.of(64, 100, BATTERY_PLUGGED_WIRELESS)
        assertEquals(PowerSource.WIRELESS, pad.source)
        assertEquals(R.string.charging_wireless, pad.label)
    }

    @Test
    fun onlyAHundredPercentIsFullyCharged() {
        assertEquals(R.string.charged, ChargingStatus.of(100, 100, BATTERY_PLUGGED_USB).label)
        assertEquals(R.string.charged, ChargingStatus.of(100, 100, BATTERY_PLUGGED_WIRELESS).label)
        // Held at a charge limit, it's still charging as far as the back screen says.
        assertEquals(R.string.charging, ChargingStatus.of(80, 100, BATTERY_PLUGGED_USB).label)
    }

    @Test
    fun unpluggedItStillSaysCharging() {
        // Play, with nothing plugged in: the level now, as if charging.
        val battery = ChargingStatus.of(100, 100, plugged = 0)
        assertEquals(100, battery.level)
        assertEquals(R.string.charging, battery.label)
    }

    @Test
    fun theLevelIsAPercentage() {
        assertEquals(50, ChargingStatus.of(level = 5, scale = 10, BATTERY_PLUGGED_USB).level)
        assertEquals(0, ChargingStatus.of(level = -1, scale = 100, BATTERY_PLUGGED_USB).level)
        assertEquals(0, ChargingStatus.of(level = 50, scale = 0, BATTERY_PLUGGED_USB).level)
    }
}
