package com.backscreen.wallpaper.rear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.backscreen.wallpaper.core.BackScreen

/**
 * When covering the back counts: covered for [holdMs] without a break. A cover already there
 * when it starts doesn't count until the back has been clear once, so sending an app to the
 * back screen with the phone lying on its back doesn't bring it straight back. Times are from
 * [SystemClock.elapsedRealtime]. Plain Kotlin, so it's unit tested.
 */
class CoverTimer(private val holdMs: Long = HOLD_MS) {
    private var armed = false
    private var coveredSince: Long? = null

    /**
     * A reading from the sensor at [now]. Returns when to look again ([isDue]) if this starts a
     * cover that may count, or null.
     */
    fun reading(covered: Boolean, now: Long): Long? {
        if (!covered) {
            armed = true
            coveredSince = null
            return null
        }
        if (!armed || coveredSince != null) return null
        coveredSince = now
        return now + holdMs
    }

    /** Whether the back has been covered for long enough by [now]. */
    fun isDue(now: Long): Boolean {
        val since = coveredSince ?: return false
        return armed && now - since >= holdMs
    }

    companion object {
        const val HOLD_MS = 1500L
    }
}

/**
 * Watches the back proximity sensor ([BackSensor]) and calls [onCovered] when the back has been
 * covered for 1.5 s ([CoverTimer]): a hand over the camera, or the phone laid on its back. Only
 * runs between [start] and [stop]; the sensor needs no permission.
 */
class BackCover(context: Context, private val onCovered: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = BackSensor.find(sensors)
    private var timer = CoverTimer()
    private var running = false
    private val due = Runnable { fireIfDue() }

    val isRunning get() = running

    fun start() {
        if (running || sensor == null) return
        running = true
        timer = CoverTimer()
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, BackScreen.mainHandler)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors.unregisterListener(this)
        BackScreen.mainHandler.removeCallbacks(due)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val covered = BackSensor.isCovered(event)
        val now = SystemClock.elapsedRealtime()
        BackScreen.trace(if (covered) "Back covered" else "Back clear")
        val lookAt = timer.reading(covered, now)
        // A repeat of the same reading leaves the cover's timing as it was.
        if (!covered) BackScreen.mainHandler.removeCallbacks(due)
        if (lookAt != null) BackScreen.mainHandler.postDelayed(due, lookAt - now)
    }

    private fun fireIfDue() {
        if (!running || !timer.isDue(SystemClock.elapsedRealtime())) return
        stop()
        onCovered()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
