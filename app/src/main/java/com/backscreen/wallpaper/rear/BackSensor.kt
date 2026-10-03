package com.backscreen.wallpaper.rear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.backscreen.wallpaper.core.BackScreen

/**
 * The proximity sensor beside the back screen ("tcs3760_sec_back Proximity Back Sensor" on the
 * 17 Pro Max). It needs no permission, and reports while the phone is locked.
 */
object BackSensor {
    // A reading usually arrives within a few ms of asking.
    private const val CHECK_TIMEOUT_MS = 400L

    fun find(sensors: SensorManager): Sensor? = sensors.getSensorList(Sensor.TYPE_ALL).firstOrNull {
        it.name.contains("Proximity", ignoreCase = true) && it.name.contains("Back", ignoreCase = true)
    }

    /** It reads its full range when clear, and less (0 on this phone) when covered. */
    fun isCovered(event: SensorEvent) = event.values[0] < event.sensor.maximumRange

    /**
     * Reads it once: [result] says whether the back is covered, by a hand or by a table the
     * phone lies on. False if there's no such sensor or it doesn't answer in time. Main thread.
     */
    fun check(context: Context, result: (Boolean) -> Unit) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = find(sensors) ?: return result(false)
        val asked = SystemClock.elapsedRealtime()
        var answered = false
        val listener = object : SensorEventListener {
            val timeout = Runnable { answer(false, "no answer") }

            fun answer(covered: Boolean, how: String) {
                if (answered) return
                answered = true
                sensors.unregisterListener(this)
                BackScreen.mainHandler.removeCallbacks(timeout)
                BackScreen.trace("Back sensor: $how in ${SystemClock.elapsedRealtime() - asked} ms")
                result(covered)
            }

            override fun onSensorChanged(event: SensorEvent) {
                val covered = isCovered(event)
                answer(covered, if (covered) "covered" else "clear")
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, BackScreen.mainHandler)
        BackScreen.mainHandler.postDelayed(listener.timeout, CHECK_TIMEOUT_MS)
    }
}
