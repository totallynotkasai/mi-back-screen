package com.backscreen.wallpaper.core

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import com.backscreen.wallpaper.rear.BackSensor
import com.backscreen.wallpaper.rear.RearHostActivity

/**
 * Stay fully lit while unlocked: when the back screen's window keeps it lit. Plain Kotlin, so
 * it's unit tested.
 */
object StayLitRule {

    /**
     * While the option is on, the wallpaper is up, and you're using the phone: unlocked with the
     * main screen on. Locking or the main screen going off lets Xiaomi's doze dim it as usual.
     */
    fun holds(option: Boolean, wallpaperUp: Boolean, unlocked: Boolean, mainOn: Boolean) =
        option && wallpaperUp && unlocked && mainOn

    /**
     * Whether to light the back screen as the hold starts: keeping it lit doesn't light a dimmed
     * one, and at unlock it's dimmed, since Xiaomi's doze started at the lock (first check 3).
     */
    fun lightsUp(held: Boolean, holds: Boolean, rearLit: Boolean) = holds && !held && !rearLit
}

/**
 * Light up when you pick it up. Lying on its back, the back screen goes off, and when it's
 * uncovered Xiaomi returns it to dimmed by itself; but not once one of Xiaomi's own switch-offs
 * has fired (90 s after its doze started, or 5 minutes in the dark): then it stays black until a
 * double tap (first check 4). So while the back screen is off and you're using the phone, the
 * back going from covered to clear lights it. Plain Kotlin, so it's unit tested.
 */
class PickUpRule {
    private var covered = false

    /**
     * A reading from the back sensor while [watches]. True when the back has just gone from
     * covered to clear: a clear back to begin with lights nothing, so it never lights the back
     * screen for nothing.
     */
    fun reading(covered: Boolean): Boolean {
        val pickedUp = this.covered && !covered
        this.covered = covered
        return pickedUp
    }

    /** Not watching any more: a cover seen before doesn't count next time. */
    fun reset() {
        covered = false
    }

    companion object {
        /**
         * Only while it can matter: the option is on, the wallpaper is up, you're using the phone,
         * and the back screen is off. Usually a few seconds, so the sensor is rarely read.
         */
        fun watches(option: Boolean, wallpaperUp: Boolean, unlocked: Boolean, mainOn: Boolean, rearOff: Boolean) =
            option && wallpaperUp && unlocked && mainOn && rearOff
    }
}

/**
 * The Back screen display card's two options that need the wallpaper ([DisplaySettings]): Stay
 * fully lit while unlocked ([StayLitRule]) and Light up when you pick it up ([PickUpRule]).
 * KeeperService runs it, and calls [update] whenever something they depend on may have changed:
 * either screen's state, unlocking, the wallpaper, a lend, or the options.
 *
 * [light] lights the back screen, saying why; [wouldCover] says whether HyperOS would cover it
 * now (the main screen on sideways); [allowWake] asks the keeper's [WakeThrottle].
 */
class DisplayKeeper(
    private val context: Context,
    private val light: (why: String) -> Unit,
    private val wouldCover: () -> Boolean,
    private val allowWake: () -> Boolean,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? by lazy { BackSensor.find(sensors) }
    private val pickUp = PickUpRule()
    private var watching = false
    private var holding = false

    // Lighting for the hold waits while the main screen is sideways, until then (elapsedRealtime).
    private var lightBy = 0L

    /** Works out both options again. Main thread. */
    fun update() {
        val main = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val rear = BackScreen.findRearDisplay(context)
        val mainOn = main?.state == Display.STATE_ON
        val unlocked = !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val wallpaperUp = RearState.snapshot(context).guardsWallpaper
        val rearLit = rear?.state == Display.STATE_ON

        val holds = StayLitRule.holds(DisplaySettings.stayLitUnlocked(context), wallpaperUp, unlocked, mainOn)
        if (holds != holding) {
            val lights = StayLitRule.lightsUp(holding, holds, rearLit)
            holding = holds
            RearHostActivity.keepLit(holds)
            BackScreen.log(if (holds) "Back screen kept lit while unlocked" else "Back screen no longer kept lit")
            if (lights) lightForHold()
        }
        if (lightBy != 0L) lightForHoldIfDue(rearLit)

        val watches = PickUpRule.watches(
            DisplaySettings.wakeOnPickUp(context), wallpaperUp, unlocked, mainOn, rear?.state == Display.STATE_OFF,
        )
        if (watches) watch() else stopWatching()
    }

    /** The keeper is stopping. */
    fun stop() {
        stopWatching()
        lightBy = 0L
        if (holding) {
            holding = false
            RearHostActivity.keepLit(false)
        }
    }

    /** At unlock, say: lights it now, or once the main screen is upright, for up to 30 s. */
    private fun lightForHold() {
        if (!wouldCover()) return light("Wake back screen to keep it lit")
        BackScreen.log("Main screen is on sideways; the back screen lights once it's upright")
        lightBy = SystemClock.elapsedRealtime() + UPRIGHT_WAIT_MS
    }

    private fun lightForHoldIfDue(rearLit: Boolean) {
        when {
            !holding || rearLit || SystemClock.elapsedRealtime() > lightBy -> lightBy = 0L
            !wouldCover() -> {
                lightBy = 0L
                light("Wake back screen to keep it lit")
            }
        }
    }

    private fun watch() {
        if (watching) return
        val sensor = sensor ?: return
        watching = true
        pickUp.reset()
        BackScreen.trace("Watching the back sensor for a pick-up")
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, BackScreen.mainHandler)
    }

    private fun stopWatching() {
        if (!watching) return
        watching = false
        sensors.unregisterListener(this)
        BackScreen.trace("Stopped watching the back sensor")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!watching || !pickUp.reading(BackSensor.isCovered(event))) return
        when {
            wouldCover() -> BackScreen.log("Picked up; not lit while the main screen is on sideways")
            !allowWake() -> BackScreen.log("Picked up; the back screen was lit moments ago")
            else -> light("Picked up: wake back screen")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private companion object {
        // As long as a sent app's light waits (KeeperService.lightForLend).
        const val UPRIGHT_WAIT_MS = 30_000L
    }
}
