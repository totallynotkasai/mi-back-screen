package com.backscreen.wallpaper

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Full-screen wallpaper shown on the rear display. KeeperService launches it straight onto
 * the rear; if HyperOS won't allow that, it's launched on the main display (transparent, so
 * it doesn't flash) and its task moved to the rear, where it's recreated and draws the image.
 *
 * Two layers: the gallery's images, which crossfade from one to the next, and the clock. With
 * no images it's black, with the clock if that's on.
 *
 * The rear lights and dims on Xiaomi's own timeout, as with Xiaomi's screen. If something
 * closes the wallpaper (Xiaomi taking the rear back), KeeperService puts it back.
 */
class RearWallpaperActivity : Activity() {

    private lateinit var images: FrameLayout
    private lateinit var clock: ClockLayer
    @Volatile private var shown: Uri? = null
    private var loads = 0

    private var onRear = false
    private var finishRequested = false
    private var rearState = Display.STATE_UNKNOWN

    // Decoding happens here so a big image or a slow folder doesn't hold up the screen.
    private val loader = Executors.newSingleThreadExecutor()
    private val nextImage = Runnable { showImage(advance = true) }
    private val redraw = Runnable {
        if (!isDestroyed) window.decorView.invalidate()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == display?.displayId) onRearStateChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        setShowWhenLocked(true)

        val display = display ?: return
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            val keeper = KeeperService.instance
            if (keeper == null) finishQuietly() else keeper.onWallpaperCreatedOnMain(taskId)
            return
        }
        onRear = true

        // Opaque on the rear, so the system doesn't need to draw Xiaomi's launcher behind us.
        setTranslucent(false)
        // Cover the whole panel, camera area included, so the app's preview matches exactly.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val camera = BackScreen.cameraInsets(display)
        images = FrameLayout(this)
        if (BackScreen.avoidCamera(this)) images.setPadding(camera.left, camera.top, camera.right, camera.bottom)
        // The clock always keeps clear of the camera.
        clock = ClockLayer(this).apply {
            setPadding(camera.left, camera.top, camera.right, camera.bottom)
            onTextChanged = { if (clock.visibility == View.VISIBLE) onShownChanged() }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(images)
            addView(clock)
        })
        clock.backdrop = images
        applyClock()

        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, BackScreen.mainHandler)
        onRearStateChanged()

        // The first image straight away, so there's no blank moment.
        try {
            val uri = Gallery.current(this, advance = true)
            if (uri == null) {
                BackScreen.log("No images; the back screen shows black")
            } else {
                setImage(uri, BackScreen.loadImage(this, uri, display, Gallery.scaling(this)), fade = false)
                BackScreen.log("Wallpaper showing on back screen")
            }
        } catch (e: Exception) {
            BackScreen.log("Couldn't load image: ${e.message}")
        }
        scheduleNextImage()
        ClockAlarm.update(this)
        KeeperService.instance?.onWallpaperCreatedOnRear(display.displayId)
    }

    override fun onStart() {
        super.onStart()
        if (onRear) {
            if (isCurrent()) visibleOnRear = true
            showImage(advance = true)
            clock.updateTime()
            // In case the display listener missed the rear waking.
            onRearStateChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        if (onRear) clock.updateTime()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (onRear) clock.updateTime()
    }

    override fun onStop() {
        // A wallpaper being replaced stops after its replacement has started; leave that one be.
        if (isCurrent()) visibleOnRear = false
        if (!isFinishing && onRear) KeeperService.instance?.onWallpaperHidden()
        super.onStop()
    }

    override fun onDestroy() {
        if (isCurrent()) {
            current = null
            visibleOnRear = false
        }
        BackScreen.mainHandler.removeCallbacks(nextImage)
        BackScreen.mainHandler.removeCallbacks(redraw)
        loader.shutdownNow()
        if (onRear) {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
            for (i in 0 until images.childCount) BackScreen.stop((images.getChildAt(i) as ImageView).drawable)
            ClockAlarm.update(this)
            // Closed by someone else: Xiaomi's back screen service taking the rear back.
            if (!finishRequested && !isChangingConfigurations) {
                BackScreen.log("Wallpaper was closed on the back screen")
                KeeperService.instance?.onWallpaperRemoved()
            }
        }
        super.onDestroy()
    }

    private fun isCurrent() = current?.get() === this

    private fun finishQuietly() {
        finishRequested = true
        finish()
    }

    // Xiaomi's back gesture strip on the rear's right edge sends BACK; stay put.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!onRear) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** The back screen lit up, dimmed or went off. */
    private fun onRearStateChanged() {
        val state = display?.state ?: return
        if (state == rearState) return
        val lit = state == Display.STATE_ON
        // Dimmed and suspended swap every few seconds as the light changes; not worth the log.
        if (lit != (rearState == Display.STATE_ON) || state == Display.STATE_OFF) {
            BackScreen.log("Back screen ${BackScreen.stateName(state)}")
        } else {
            BackScreen.trace("Back screen ${BackScreen.stateName(state)}")
        }
        val wasOff = rearState == Display.STATE_OFF
        val wasSuspended = rearState == Display.STATE_DOZE_SUSPEND
        rearState = state
        // It may have been asleep a while.
        clock.updateTime()
        // Anything drawn while it was suspended never reached it (see pushFrame): draw it again.
        if (wasSuspended && (state == Display.STATE_ON || state == Display.STATE_DOZE)) {
            window.decorView.invalidate()
        }
        // Nothing to see while it's off, so the clock alarm stops until it wakes.
        if (wasOff != (state == Display.STATE_OFF)) ClockAlarm.update(this)
    }

    private fun applyClock() {
        clock.visibility = if (BackScreen.showClock(this)) View.VISIBLE else View.GONE
        val settings = BackScreen.clockSettings(this)
        if (clock.settings != settings) clock.settings = settings
    }

    /**
     * Shows the gallery's current image, moving on to the next first if [advance] and it's time.
     * With [force], reloads it even if it's the one already showing. With no images, fades to
     * black.
     */
    private fun showImage(advance: Boolean, force: Boolean = false) {
        val display = display ?: return
        val load = ++loads
        loader.execute {
            try {
                val uri = Gallery.current(this, advance)
                val drawable = if (uri == null || (uri == shown && !force)) null
                else BackScreen.loadImage(this, uri, display, Gallery.scaling(this))
                runOnUiThread {
                    if (isDestroyed || load != loads) return@runOnUiThread
                    if (uri != null && drawable != null) setImage(uri, drawable, fade = true)
                    if (uri == null) clearImage()
                    scheduleNextImage()
                }
            } catch (e: Exception) {
                BackScreen.log("Couldn't load image: ${e.message}")
                runOnUiThread { if (!isDestroyed) scheduleNextImage() }
            }
        }
    }

    /**
     * Puts [drawable] on top, fading it in over the old image, which is then removed. Only fades
     * while the rear is lit: a dimmed one would only show a moment of the fade.
     */
    private fun setImage(uri: Uri, drawable: Drawable, fade: Boolean) {
        shown = uri
        val view = WallpaperView(this).apply {
            scaling = Gallery.scaling(this@RearWallpaperActivity)
            setImageDrawable(drawable)
        }
        BackScreen.start(drawable)
        val old = (0 until images.childCount).map { images.getChildAt(it) as ImageView }
        images.addView(view)
        val removeOld = {
            for (o in old) {
                BackScreen.stop(o.drawable)
                images.removeView(o)
            }
            // A new image under the clock: its automatic colours may need to change.
            clock.backdropChanged()
        }
        if (fade && old.isNotEmpty() && isLit()) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(FADE_MS).withEndAction(removeOld)
        } else {
            removeOld()
        }
        BackScreen.log("Showing image ${uri.lastPathSegment?.substringAfterLast('/')}")
        onShownChanged()
    }

    /** The images were removed: fade out whatever is showing (at once if dimmed), leaving black. */
    private fun clearImage() {
        if (shown == null && images.childCount == 0) return
        shown = null
        val old = (0 until images.childCount).map { images.getChildAt(it) as ImageView }
        for (o in old) {
            val remove = {
                BackScreen.stop(o.drawable)
                images.removeView(o)
                clock.backdropChanged()
            }
            if (isLit()) o.animate().alpha(0f).setDuration(FADE_MS).withEndAction(remove) else remove()
        }
        BackScreen.log("No images; the back screen shows black")
        onShownChanged()
    }

    private fun isLit() = display?.state == Display.STATE_ON

    private fun scheduleNextImage() {
        BackScreen.mainHandler.removeCallbacks(nextImage)
        val at = Gallery.nextChangeAt(this) ?: return
        // While the phone sleeps this runs late; onStart and the display turning on catch up.
        BackScreen.mainHandler.postDelayed(nextImage, max(MIN_DELAY_MS, at - System.currentTimeMillis()))
    }

    /** The minute changed while the phone may be asleep. */
    private fun onMinute() {
        BackScreen.trace("Minute alarm; back screen ${BackScreen.stateName(display?.state)}")
        clock.updateTime()
    }

    /**
     * What the wallpaper shows changed: a new image, or the clock showing a new time (the next
     * minute, or the time, time zone or 12/24-hour setting changed). If the rear is dimmed, get
     * the new frame onto it (see [pushFrame]).
     */
    private fun onShownChanged() {
        val state = display?.state
        if (state == Display.STATE_ON || state == Display.STATE_OFF) return
        pushFrame()
    }

    /**
     * Gets the current frame onto a dimmed rear. In DOZE_SUSPEND the display driver refuses new
     * frames, and one drawn then is used up without reaching the panel, so it keeps the old one.
     * While a draw wake lock is held, the system switches the display to DOZE, where frames get
     * through; that's how the system's own always-on screens update. So take one first, then
     * draw again once the rear is in DOZE. The lock isn't in the SDK, but any app with WAKE_LOCK
     * may take one.
     */
    private fun pushFrame() {
        try {
            getSystemService(PowerManager::class.java)
                .newWakeLock(DRAW_WAKE_LOCK, "BackScreen:clock-draw")
                .acquire(PUSH_HOLD_MS)
        } catch (e: RuntimeException) {
            BackScreen.log("Couldn't update the dimmed back screen: ${e.message}")
            return
        }
        BackScreen.mainHandler.removeCallbacks(redraw)
        BackScreen.mainHandler.postDelayed(redraw, REDRAW_DELAY_MS)
    }

    companion object {
        private const val FADE_MS = 800L
        private const val MIN_DELAY_MS = 1000L
        // The rear switches to DOZE about 10 ms after the lock is taken.
        private const val REDRAW_DELAY_MS = 50L
        private const val PUSH_HOLD_MS = 300L

        // PowerManager.DRAW_WAKE_LOCK, hidden from the SDK but open to any app with WAKE_LOCK.
        private const val DRAW_WAKE_LOCK = 0x80

        private var current: WeakReference<RearWallpaperActivity>? = null

        var visibleOnRear = false
            private set

        private fun showing() = current?.get()?.takeIf { !it.isFinishing && it.onRear }

        fun isOnRear() = showing() != null

        /** On the rear, and the rear is lit or dimmed rather than off, so the clock can be seen. */
        fun isClockVisible() = showing()?.let { it.rearState != Display.STATE_OFF } == true

        /** Moves the gallery on if it's time, and catches the clock up; the rear woke. */
        fun refreshImage() {
            val activity = showing() ?: return
            activity.showImage(advance = true)
            activity.clock.updateTime()
        }

        /** The clock alarm: see [onMinute]. */
        fun onMinuteAlarm() {
            showing()?.onMinute()
        }

        /** Loads the gallery's current image again. False if the wallpaper isn't up. */
        fun reload(): Boolean {
            val activity = showing() ?: return false
            activity.showImage(advance = false, force = true)
            KeeperService.instance?.showChanges()
            return true
        }

        /** The clock or gallery timing changed. */
        fun settingsChanged() {
            val activity = showing() ?: return
            activity.applyClock()
            activity.scheduleNextImage()
            ClockAlarm.update(activity)
            KeeperService.instance?.showChanges()
        }

        fun finishCurrent() {
            current?.get()?.finishQuietly()
            current = null
            visibleOnRear = false
        }
    }
}
