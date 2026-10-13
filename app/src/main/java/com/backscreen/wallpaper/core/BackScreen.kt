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
import android.util.Size
import android.view.Display
import android.view.RoundedCorner
import android.view.Surface
import com.backscreen.wallpaper.wallpaper.DecodeSize
import com.backscreen.wallpaper.wallpaper.Scaling
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared helpers: finding the rear display, loading images and a small log. Each section keeps
 * its own settings (WallpaperSettings, BatterySettings and so on).
 */
object BackScreen {
    private const val TAG = "BackScreen"
    private const val MAX_LOG_LINES = 40
    private const val REAR_PREFS = "back_screen"
    private const val KEY_REAR_ID = "rear_display_id"

    private val log = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    val mainHandler = Handler(Looper.getMainLooper())

    /** How far the camera reaches into each edge of [display], in its pixels, as it's turned now. */
    fun cameraInsets(display: Display): Insets =
        display.cutout?.let { Insets.of(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom) }
            ?: Insets.NONE

    // The wallpaper always shows on the back screen in its natural landscape (976 x 596, camera
    // on the left), but an app lent the back screen may turn it to portrait (Quick Switch's
    // Orientation). Previews, decoding and the pan's maths go by the natural shape.

    /** [display]'s size in its natural orientation, however it's turned just now. */
    @Suppress("DEPRECATION")
    fun naturalSize(display: Display): Size {
        val m = DisplayMetrics().also { display.getRealMetrics(it) }
        return if (isTurned(display)) Size(m.heightPixels, m.widthPixels) else Size(m.widthPixels, m.heightPixels)
    }

    /** [cameraInsets] in [display]'s natural orientation. */
    fun naturalCameraInsets(display: Display): Insets {
        val i = cameraInsets(display)
        return when (display.rotation) {
            Surface.ROTATION_90 -> Insets.of(i.bottom, i.left, i.top, i.right)
            Surface.ROTATION_180 -> Insets.of(i.right, i.bottom, i.left, i.top)
            Surface.ROTATION_270 -> Insets.of(i.top, i.right, i.bottom, i.left)
            else -> i
        }
    }

    /** Where the camera covers [display], in its natural orientation. */
    fun naturalCameraRects(display: Display): List<Rect> {
        val rects = display.cutout?.boundingRects.orEmpty()
        val natural = naturalSize(display)
        return rects.map { r ->
            when (display.rotation) {
                // Turned a quarter: the natural x runs back up the turned y, and so on.
                Surface.ROTATION_90 -> Rect(natural.width - r.bottom, r.left, natural.width - r.top, r.right)
                Surface.ROTATION_180 -> Rect(natural.width - r.right, natural.height - r.bottom, natural.width - r.left, natural.height - r.top)
                Surface.ROTATION_270 -> Rect(r.top, natural.height - r.right, r.bottom, natural.height - r.left)
                else -> r
            }
        }
    }

    /**
     * How rounded [display]'s corners are, as a share of its natural height: 101 of 596 on the
     * back screen, the same at every corner (measured on the phone). Null if it doesn't say.
     */
    fun cornerShare(display: Display): Float? {
        val radius = display.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: return null
        return radius.toFloat() / naturalSize(display).height
    }

    private fun isTurned(display: Display) =
        display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270

    /** The back screen, as [RearDisplayPick] chooses it, or null if there's none. Remembered for next time. */
    @Suppress("DEPRECATION")
    fun findRearDisplay(context: Context): Display? {
        val displays = context.getSystemService(DisplayManager::class.java).displays
        val candidates = displays.map { d ->
            val m = DisplayMetrics().also { d.getRealMetrics(it) }
            DisplayCandidate(d.displayId, m.widthPixels, m.heightPixels, d.flags and Display.FLAG_PRIVATE != 0)
        }
        val remembered = rememberedRearId(context)
        val id = RearDisplayPick.choose(candidates, remembered) ?: return null
        if (id != remembered) rearPrefs(context).edit().putInt(KEY_REAR_ID, id).apply()
        return displays.firstOrNull { it.displayId == id }
    }

    /**
     * The back screen's id: as found now, or else as last found. For putting things back on it
     * when it can't be found just now. Null if it has never been found.
     */
    fun rearIdOrLastKnown(context: Context): Int? = findRearDisplay(context)?.displayId ?: rememberedRearId(context)

    /** Says which display is the back screen, once each time the keeper starts. */
    fun logRearDisplay(context: Context) {
        val rear = findRearDisplay(context) ?: return log("No back screen found")
        val size = naturalSize(rear)
        log("The back screen is display ${rear.displayId} (${size.width} x ${size.height})")
    }

    private fun rememberedRearId(context: Context) = rearPrefs(context).getInt(KEY_REAR_ID, -1).takeIf { it >= 0 }

    private fun rearPrefs(context: Context) = context.getSharedPreferences(REAR_PREFS, Context.MODE_PRIVATE)

    /**
     * Decodes [uri] sized for [display] in its natural orientation and [scaling], never bigger
     * than [DecodeSize] allows. Works off the main thread; [start] it once shown. Callers catch
     * [Throwable]: an image can still be too big for the memory left.
     */
    fun loadImage(context: Context, uri: Uri, display: Display, scaling: Scaling): Drawable {
        val screen = naturalSize(display)
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            // In memory the clock can read, to pick a colour that stands out from the image.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val size = DecodeSize.of(w, h, screen.width, screen.height, scaling)
            when {
                size.crop -> decoder.crop = Rect(size.left, size.top, size.left + size.width, size.top + size.height)
                size.width != w || size.height != h -> decoder.setTargetSize(size.width, size.height)
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
