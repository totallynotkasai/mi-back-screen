package com.backscreen.wallpaper.rear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import android.view.ViewConfiguration
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
    const val UP_TRAVEL = 0.15f
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

    /**
     * Whether a touch that started at ([x0], [y0]) and is now at ([x1], [y1]) has become a pull
     * down, which then follows the finger: from the top half, clear of Xiaomi's Home strip,
     * [slop] or more down and mostly downward.
     */
    fun startsPull(x0: Float, y0: Float, x1: Float, y1: Float, slop: Float, height: Int): Boolean {
        val dy = y1 - y0
        return height > 0 && y0 <= height / 2f && dy >= slop && dy >= 2 * abs(x1 - x0)
    }

    /**
     * Whether a pull down that moved ([dx], [dy]) opens what it pulls when let go: as far as a
     * swipe down, and mostly downward. There's no time limit, since you watch it come.
     */
    fun pullOpens(dx: Float, dy: Float, height: Int) = height > 0 && dy >= height * DOWN_TRAVEL && dy >= 2 * abs(dx)

    /** Whether a push up that moved ([dx], [dy]) closes what was pulled down: 15% of the height, mostly upward. */
    fun pushCloses(dx: Float, dy: Float, height: Int) = height > 0 && -dy >= height * UP_TRAVEL && -dy >= 2 * abs(dx)
}

/**
 * A swipe left that starts on Xiaomi's back strip (from x 914) never reaches the host as a
 * swipe: Xiaomi's strip takes it and sends BACK. In Phase 7, half the natural swipes left that
 * opened the camera started there (5 of 10), so BACK on the wallpaper counts as a swipe left too
 * (decided then). Plain Kotlin, so it's unit tested.
 */
object EdgeSwipe {
    /**
     * Not just after the wallpaper came back in front: a swipe on the strip that closed Xiaomi
     * Camera can send a second BACK, which would open it again.
     */
    const val GRACE_MS = 2000L

    /** Whether BACK on the wallpaper, [shownForMs] after it came back in front, opens the camera. */
    fun opensCamera(shownForMs: Long) = shownForMs >= GRACE_MS
}

/**
 * Reads swipes on the back screen and passes on the ones that count (see [Swipes]). The back
 * screen sits under your fingers, so on top of that:
 *
 * - one finger only;
 * - nothing while the back proximity sensor is covered, by a palm or a table.
 *
 * With [pullDown] on, a swipe down follows the finger instead ([Listener.onPull]), as the
 * notification list comes down with it.
 *
 * HyperOS already ignores the grip of a hand holding the phone, and this touchscreen doesn't
 * report how big a touch is, so there's no palm check by size. Touches only arrive while the
 * back screen is lit, so the sensor is only read then.
 */
class RearGestures(context: Context, private val listener: Listener) : SensorEventListener {

    interface Listener {
        fun onSwipe(swipe: Swipe)

        /** A pull down from the top half, [dy] px so far. Only while [pullDown] is on. */
        fun onPull(dy: Float)

        /** The pull ended: [open] if it went far enough to open what it pulls. */
        fun onPullEnd(open: Boolean)
    }

    /** Off unless a section uses swipes; then the sensor isn't read at all. */
    var enabled = false
        set(value) {
            field = value
            updateSensor()
        }

    /** A swipe down follows the finger, as a pull (see [Listener.onPull]). */
    var pullDown = false

    private var lit = false
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val proximity: Sensor? = BackSensor.find(sensors)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var listening = false
    private var covered = false

    private var tracking = false
    private var pulling = false
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
                pulling = false
                startX = event.x
                startY = event.y
                startTime = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking || !pullDown) return
                if (!pulling) {
                    if (startY > height * (1 - Swipes.HOME_STRIP)) return
                    pulling = Swipes.startsPull(startX, startY, event.x, event.y, slop, height)
                }
                if (pulling) listener.onPull((event.y - startY).coerceAtLeast(0f))
            }
            // A second finger: not a swipe.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> stopTracking()
            MotionEvent.ACTION_UP -> {
                if (!tracking || covered) return stopTracking()
                tracking = false
                if (pulling) {
                    pulling = false
                    listener.onPullEnd(Swipes.pullOpens(event.x - startX, event.y - startY, height))
                    return
                }
                val ms = event.eventTime - startTime
                val swipe = Swipes.classify(startX, startY, event.x, event.y, ms, width, height)
                // For tuning: where each touch that moved went, and whether it counted.
                if (abs(event.x - startX) > slop || abs(event.y - startY) > slop) {
                    BackScreen.trace(
                        "${swipe?.name?.lowercase() ?: "Not a swipe"}: (${startX.toInt()}, ${startY.toInt()}) " +
                            "to (${event.x.toInt()}, ${event.y.toInt()}) in $ms ms"
                    )
                }
                listener.onSwipe(swipe ?: return)
            }
        }
    }

    /** Stops reading the sensor, for good. */
    fun release() {
        enabled = false
    }

    /** The touch no longer counts; a pull under way goes back. */
    private fun stopTracking() {
        tracking = false
        if (pulling) {
            pulling = false
            listener.onPullEnd(false)
        }
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
            stopTracking()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowCovered = BackSensor.isCovered(event)
        if (nowCovered == covered) return
        covered = nowCovered
        if (nowCovered) stopTracking()
        BackScreen.trace(if (nowCovered) "Back sensor covered: ignoring swipes" else "Back sensor clear")
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
