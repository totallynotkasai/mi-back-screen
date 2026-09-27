package com.backscreen.wallpaper

import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Insets
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.widget.ImageView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Shared helpers: finding the rear display, loading the wallpaper, settings and a small log. */
object BackScreen {
    private const val TAG = "BackScreen"
    private const val MAX_LOG_LINES = 40
    private const val PREFS = "backscreen"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AVOID_CAMERA = "avoid_camera"

    private val log = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    val mainHandler = Handler(Looper.getMainLooper())

    fun wallpaperFile(context: Context) = File(context.filesDir, "wallpaper")

    fun isEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
        ToggleWidget.updateAll(context)
        ToggleTile.requestUpdate(context)
    }

    /** Fit the image beside the rear camera instead of letting the camera cover part of it. */
    fun avoidCamera(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AVOID_CAMERA, false)

    fun setAvoidCamera(context: Context, avoid: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AVOID_CAMERA, avoid).apply()
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

    @Suppress("DEPRECATION")
    fun loadWallpaper(file: File, display: Display): Drawable {
        val m = DisplayMetrics().also { display.getRealMetrics(it) }
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
            // Downscale large images to just cover the screen; saves memory and battery.
            val scale = max(
                m.widthPixels.toFloat() / info.size.width,
                m.heightPixels.toFloat() / info.size.height
            )
            if (scale < 1f) {
                decoder.setTargetSize(
                    max(1, (info.size.width * scale).roundToInt()),
                    max(1, (info.size.height * scale).roundToInt())
                )
            }
        }
        if (drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }
        return drawable
    }

    fun makeView(context: Context, drawable: Drawable) = ImageView(context).apply {
        setBackgroundColor(Color.BLACK)
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageDrawable(drawable)
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        synchronized(log) {
            log.addLast("${time.format(Date())}  $msg")
            while (log.size > MAX_LOG_LINES) log.removeFirst()
        }
    }

    fun logText(): String = synchronized(log) { log.reversed().joinToString("\n") }
}
