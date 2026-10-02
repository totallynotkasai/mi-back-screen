package com.backscreen.wallpaper.core

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
import com.backscreen.wallpaper.wallpaper.Scaling
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared helpers: finding the rear display, loading images and a small log. Each section keeps
 * its own settings (WallpaperSettings, BatterySettings and so on).
 */
object BackScreen {
    private const val TAG = "BackScreen"
    private const val MAX_LOG_LINES = 40

    private val log = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    val mainHandler = Handler(Looper.getMainLooper())

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
