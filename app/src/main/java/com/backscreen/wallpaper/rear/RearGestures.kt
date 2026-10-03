package com.backscreen.wallpaper.rear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import com.backscreen.wallpaper.core.BackScreen
import kotlin.math.abs

/** A swipe on the back screen that means something. */
enum class Swipe { LEFT, DOWN }

/**
 * Which swipe a touch was, from where it started and ended. Plain Kotlin, so it's unit tested.
 *
 * Measured on the 17 Pro Max (976 x 596): deliberate swipes left cross 34-61% of the width,
 * and swipes down from the top 44-81% of the height, in well under a second. Xiaomi's own back
 * gesture runs down the right edge (from x 914) and its Home gesture along the bottom 90 px;
 * touches that start there go to Xiaomi, and swipes starting there don't count here either.
 * A natural swipe left often starts just short of the edge (x 907).
 */
object Swipes {
    const val LEFT_TRAVEL = 0.30f
    const val DOWN_TRAVEL = 0.25f
    const val MAX_DURATION_MS = 1000L
    const val LEFT_START_MAX = 914f / 976
    const val HOME_STRIP = 90f / 596

    /** The swipe from ([x0], [y0]) to ([x1], [y1]) in [durationMs] on a [width] x [height] screen, if any. */
    fun classify(x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long, width: Int, height: Int): Swipe? {
        if (width <= 0 || height <= 0 || durationMs > MAX_DURATION_MS) return null
        if (y0 > height * (1 - HOME_STRIP)) return null
        val dx = x1 - x0
        val dy = y1 - y0
        // Mostly in one direction: at least twice as far that way as across.
        return when {
            -dx >= width * LEFT_TRAVEL && -dx >= 2 * abs(dy) && x0 <= width * LEFT_START_MAX -> Swipe.LEFT
            dy >= height * DOWN_TRAVEL && dy >= 2 * abs(dx) && y0 <= height / 2f -> Swipe.DOWN
            else -> null
        }
    }
}

/**
 * Reads swipes on the back screen and passes on the ones that count (see [Swipes]). The back
 * screen sits under your fingers, so on top of that:
 *
 * - one finger only;
 * - nothing while the back proximity sensor is covered, by a palm or a table.
 *
 * HyperOS already ignores the grip of a hand holding the phone, and this touchscreen doesn't
 * report how big a touch is, so there's no palm check by size. Touches only arrive while the
 * back screen is lit, so the sensor is only read then.
 */
class RearGestures(context: Context, private val onSwipe: (Swipe) -> Unit) : SensorEventListener {

    /** Off unless a section uses swipes; then the sensor isn't read at all. */
    var enabled = false
        set(value) {
            field = value
            updateSensor()
        }

    private var lit = false
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val proximity: Sensor? = BackSensor.find(sensors)
    private var listening = false
    private var covered = false

    private var tracking = false
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L

    /** Whether the back screen is lit, which is the only time touches reach it. */
    fun setLit(lit: Boolean) {
        this.lit = lit
        updateSensor()
    }

    /** Every touch on the back screen, which is [width] x [height]. Never consumes it. */
    fun onTouchEvent(event: MotionEvent, width: Int, height: Int) {
        if (!enabled) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = !covered
                startX = event.x
                startY = event.y
                startTime = event.eventTime
            }
            // A second finger: not a swipe.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
            MotionEvent.ACTION_UP -> {
                if (!tracking || covered) return
                tracking = false
                val swipe = Swipes.classify(startX, startY, event.x, event.y, event.eventTime - startTime, width, height)
                    ?: return
                onSwipe(swipe)
            }
        }
    }

    /** Stops reading the sensor, for good. */
    fun release() {
        enabled = false
    }

    private fun updateSensor() {
        val want = enabled && lit && proximity != null
        if (want == listening) return
        listening = want
        if (want) {
            sensors.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            sensors.unregisterListener(this)
            covered = false
            tracking = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowCovered = BackSensor.isCovered(event)
        if (nowCovered == covered) return
        covered = nowCovered
        if (nowCovered) tracking = false
        BackScreen.trace(if (nowCovered) "Back sensor covered: ignoring swipes" else "Back sensor clear")
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
