package com.backscreen.wallpaper

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared helpers: finding the rear display, loading images, settings and a small log. */
object BackScreen {
    private const val TAG = "BackScreen"
    private const val MAX_LOG_LINES = 40
    private const val PREFS = "backscreen"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AVOID_CAMERA = "avoid_camera"
    private const val KEY_CLOCK = "clock"
    private const val KEY_CLOCK_STYLE = "clock_style"
    private const val KEY_CLOCK_X = "clock_x"
    private const val KEY_CLOCK_Y = "clock_y"
    private const val KEY_CLOCK_COLOR = "clock_color"
    private const val KEY_CLOCK_BG_COLOR = "clock_bg_color"
    private const val KEY_CLOCK_BG_OPACITY = "clock_bg_opacity"

    private val log = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    val mainHandler = Handler(Looper.getMainLooper())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        ToggleWidget.updateAll(context)
        ToggleTile.requestUpdate(context)
    }

    /** Fit the image beside the rear camera instead of letting the camera cover part of it. */
    fun avoidCamera(context: Context) = prefs(context).getBoolean(KEY_AVOID_CAMERA, false)

    fun setAvoidCamera(context: Context, avoid: Boolean) {
        prefs(context).edit().putBoolean(KEY_AVOID_CAMERA, avoid).apply()
    }

    /** Show the time and date over the wallpaper. */
    fun showClock(context: Context) = prefs(context).getBoolean(KEY_CLOCK, false)

    fun setShowClock(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLOCK, show).apply()
    }

    fun clockStyle(context: Context): ClockStyle {
        val name = prefs(context).getString(KEY_CLOCK_STYLE, null)
        return ClockStyle.entries.firstOrNull { it.name == name } ?: ClockStyle.CLASSIC
    }

    fun setClockStyle(context: Context, style: ClockStyle) {
        prefs(context).edit().putString(KEY_CLOCK_STYLE, style.name).apply()
    }

    /** Everything about how the clock looks and where it sits. */
    fun clockSettings(context: Context): ClockSettings {
        val p = prefs(context)
        val style = clockStyle(context)
        return ClockSettings(
            style = style,
            // Until it's been moved, each style has its own place.
            x = p.getFloat(KEY_CLOCK_X, style.x),
            y = p.getFloat(KEY_CLOCK_Y, style.y),
            color = if (p.contains(KEY_CLOCK_COLOR)) p.getInt(KEY_CLOCK_COLOR, 0) else null,
            bgColor = if (p.contains(KEY_CLOCK_BG_COLOR)) p.getInt(KEY_CLOCK_BG_COLOR, 0) else null,
            bgOpacity = p.getInt(KEY_CLOCK_BG_OPACITY, 0),
        )
    }

    fun setClockPosition(context: Context, x: Float, y: Float) {
        prefs(context).edit().putFloat(KEY_CLOCK_X, x).putFloat(KEY_CLOCK_Y, y).apply()
    }

    /** The clock's text colour; null picks one that stands out from the image. */
    fun setClockColor(context: Context, color: Int?) = putColor(context, KEY_CLOCK_COLOR, color)

    /** The colour behind the clock; null picks one that sets off the text. */
    fun setClockBgColor(context: Context, color: Int?) = putColor(context, KEY_CLOCK_BG_COLOR, color)

    /** How solid the colour behind the clock is, 0 (none) to 100. */
    fun setClockBgOpacity(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_CLOCK_BG_OPACITY, percent).apply()
    }

    private fun putColor(context: Context, key: String, color: Int?) {
        prefs(context).edit().apply { if (color == null) remove(key) else putInt(key, color) }.apply()
    }

    /** How far the camera reaches into each edge of [display], in its pixels. */
    fun cameraInsets(display: Display): Insets =
        display.cutout?.let { Insets.of(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom) }
            ?: Insets.NONE

    fun findRearDisplay(context: Context): Display? {
        val dm = context.getSystemService(DisplayManager::class.java)
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
            ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    /**
     * Decodes [uri] sized for [display] and [scaling]. Works off the main thread; [start] it
     * once shown.
     */
    @Suppress("DEPRECATION")
    fun loadImage(context: Context, uri: Uri, display: Display, scaling: Scaling): Drawable {
        val m = DisplayMetrics().also { display.getRealMetrics(it) }
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            // In memory the clock can read, to pick a colour that stands out from the image.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            if (scaling == Scaling.NONE) {
                // Shown at its own size, centred: keep just the middle that fits on the screen.
                val left = max(0, (w - m.widthPixels) / 2)
                val top = max(0, (h - m.heightPixels) / 2)
                decoder.crop = Rect(left, top, left + min(w, m.widthPixels), top + min(h, m.heightPixels))
                return@decodeDrawable
            }
            // Downscale large images to just what's shown; saves memory and battery.
            val sx = m.widthPixels.toFloat() / w
            val sy = m.heightPixels.toFloat() / h
            val scale = if (scaling == Scaling.FIT) min(sx, sy) else max(sx, sy)
            if (scale < 1f) {
                decoder.setTargetSize(
                    max(1, (info.size.width * scale).roundToInt()),
                    max(1, (info.size.height * scale).roundToInt())
                )
            }
        }
    }

    /** Sets a GIF playing, on a loop. */
    fun start(drawable: Drawable?) {
        if (drawable !is AnimatedImageDrawable) return
        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        drawable.start()
    }

    fun stop(drawable: Drawable?) {
        (drawable as? AnimatedImageDrawable)?.stop()
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        synchronized(log) {
            log.addLast("${time.format(Date())}  $msg")
            while (log.size > MAX_LOG_LINES) log.removeFirst()
        }
    }

    /** Only to logcat, for things too frequent for the app's own short log. */
    fun trace(msg: String) {
        Log.d(TAG, msg)
    }

    fun logText(): String = synchronized(log) { log.reversed().joinToString("\n") }

    /** The back screen's power state, for the log. */
    fun stateName(state: Int?) = when (state) {
        Display.STATE_ON -> "on"
        Display.STATE_OFF -> "off"
        Display.STATE_DOZE -> "dim"
        Display.STATE_DOZE_SUSPEND -> "dim (suspended)"
        Display.STATE_ON_SUSPEND -> "on (suspended)"
        else -> "unknown ($state)"
    }
}
