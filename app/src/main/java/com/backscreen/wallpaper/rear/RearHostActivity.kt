package com.backscreen.wallpaper.rear

import android.app.Activity
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.backscreen.wallpaper.camera.CameraSettings
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.RearOwner
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.notifications.NotificationSettings
import com.backscreen.wallpaper.wallpaper.ClockAlarm
import com.backscreen.wallpaper.wallpaper.ClockLayer
import com.backscreen.wallpaper.wallpaper.WallpaperLayer
import com.backscreen.wallpaper.wallpaper.WallpaperSettings
import java.lang.ref.WeakReference

/**
 * Our one window on the back screen. Each feature is a layer inside it, so whatever it shows
 * appears at once, and never has to get past HyperOS's rules for starting apps there:
 *
 * - the wallpaper: the gallery's images ([WallpaperLayer]), panning if that's on, or black with none;
 * - the clock ([ClockLayer]).
 *
 * [RearGestures] reads swipes on it.
 *
 * KeeperService launches it straight onto the rear; if HyperOS won't allow that, it's launched
 * on the main display (transparent, so it doesn't flash) and its task moved to the rear, where
 * it's recreated. It shows what [RearState] says: the wallpaper, or, with the wallpaper off, a
 * pop-over over Xiaomi's screen, which is black with just what it's up for.
 *
 * The rear lights and dims on Xiaomi's own timeout, as with Xiaomi's screen. If something
 * closes the wallpaper (Xiaomi taking the rear back), KeeperService puts it back.
 */
class RearHostActivity : Activity() {

    private lateinit var gestures: RearGestures
    private var wallpaper: WallpaperLayer? = null
    private var clock: ClockLayer? = null

    private var onRear = false
    private var popover = false
    private var started = false
    private var finishRequested = false
    private var rearState = Display.STATE_UNKNOWN

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
            if (keeper == null) finishQuietly() else keeper.onHostCreatedOnMain(taskId)
            return
        }
        onRear = true
        popover = RearState.owner(this) == RearOwner.Popover

        // Opaque on the rear, so the system doesn't need to draw Xiaomi's launcher behind us.
        setTranslucent(false)
        // Cover the whole panel, camera area included, so the app's preview matches exactly.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        if (!popover) addWallpaper(root, display)
        gestures = RearGestures(this, ::onSwipe)

        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, BackScreen.mainHandler)
        onRearStateChanged()
        wallpaper?.showFirst()
        ClockAlarm.update(this)
        KeeperService.instance?.onHostShownOnRear(display.displayId)
    }

    /** The images, and the clock over them. */
    private fun addWallpaper(root: FrameLayout, display: Display) {
        val camera = BackScreen.cameraInsets(display)
        val clock = ClockLayer(this).apply {
            // The clock always keeps clear of the camera.
            setPadding(camera.left, camera.top, camera.right, camera.bottom)
            onTextChanged = { if (visibility == View.VISIBLE) onShownChanged() }
        }
        val images = WallpaperLayer(this, display).apply {
            if (WallpaperSettings.avoidCamera(context)) setPadding(camera.left, camera.top, camera.right, camera.bottom)
            isLit = { this@RearHostActivity.isLit() }
            onShownChanged = { this@RearHostActivity.onShownChanged() }
            onBackdropChanged = clock::backdropChanged
            onBackdropMoved = clock::backdropMoved
        }
        root.addView(images)
        root.addView(clock)
        clock.backdrop = images
        wallpaper = images
        this.clock = clock
        applyClock()
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (onRear) {
            if (isCurrent()) visibleOnRear = true
            wallpaper?.showImage(advance = true)
            clock?.updateTime()
            // In case the display listener missed the rear waking.
            onRearStateChanged()
            updateGestures()
            updateMotion()
        }
    }

    override fun onResume() {
        super.onResume()
        if (onRear) clock?.updateTime()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (onRear) clock?.updateTime()
    }

    override fun onStop() {
        started = false
        // A host being replaced stops after its replacement has started; leave that one be.
        if (isCurrent()) visibleOnRear = false
        if (onRear) {
            updateGestures()
            updateMotion()
            if (!isFinishing) KeeperService.instance?.onHostHidden()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isCurrent()) {
            current = null
            visibleOnRear = false
        }
        BackScreen.mainHandler.removeCallbacks(redraw)
        if (onRear) {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
            wallpaper?.release()
            gestures.release()
            ClockAlarm.update(this)
            // Closed by someone else: Xiaomi's back screen service taking the rear back.
            if (!finishRequested && !isChangingConfigurations) {
                BackScreen.log(if (popover) "Pop-over was closed on the back screen" else "Wallpaper was closed on the back screen")
                KeeperService.instance?.onHostRemoved()
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (onRear) gestures.onTouchEvent(event, window.decorView.width, window.decorView.height)
        return super.dispatchTouchEvent(event)
    }

    /** Swipes are read only while a section uses them, and touches only arrive while the rear is lit. */
    private fun updateGestures() {
        gestures.enabled = !popover && (CameraSettings.isEnabled(this) || NotificationSettings.isEnabled(this))
        gestures.setLit(started && isLit())
    }

    /** Images pan and GIFs play only while the rear is lit: a dimmed one shows none of it. */
    private fun updateMotion() {
        wallpaper?.moving = started && isLit()
    }

    private fun onSwipe(swipe: Swipe) {
        BackScreen.log(if (swipe == Swipe.LEFT) "Swiped left on the back screen" else "Swiped down on the back screen")
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
        updateGestures()
        updateMotion()
        // It may have been asleep a while.
        clock?.updateTime()
        // Anything drawn while it was suspended never reached it (see pushFrame): draw it again.
        if (wasSuspended && (state == Display.STATE_ON || state == Display.STATE_DOZE)) {
            window.decorView.invalidate()
        }
        // Nothing to see while it's off, so the clock alarm stops until it wakes.
        if (wasOff != (state == Display.STATE_OFF)) ClockAlarm.update(this)
    }

    private fun applyClock() {
        val clock = clock ?: return
        clock.visibility = if (WallpaperSettings.showClock(this)) View.VISIBLE else View.GONE
        val settings = WallpaperSettings.clockSettings(this)
        if (clock.settings != settings) clock.settings = settings
    }

    private fun isLit() = display?.state == Display.STATE_ON

    /** The minute changed while the phone may be asleep. */
    private fun onMinute() {
        BackScreen.trace("Minute alarm; back screen ${BackScreen.stateName(display?.state)}")
        clock?.updateTime()
    }

    /**
     * What the host shows changed: a new image, or the clock showing a new time (the next
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
        // The rear switches to DOZE about 10 ms after the lock is taken.
        private const val REDRAW_DELAY_MS = 50L
        private const val PUSH_HOLD_MS = 300L

        // PowerManager.DRAW_WAKE_LOCK, hidden from the SDK but open to any app with WAKE_LOCK.
        private const val DRAW_WAKE_LOCK = 0x80

        private var current: WeakReference<RearHostActivity>? = null

        var visibleOnRear = false
            private set

        private fun showing() = current?.get()?.takeIf { !it.isFinishing && it.onRear }

        /** The host showing the wallpaper, rather than a pop-over. */
        private fun showingWallpaper() = showing()?.takeIf { !it.popover }

        fun isOnRear() = showing() != null

        /** The wallpaper is on the rear, and the rear is lit or dimmed rather than off, so the clock can be seen. */
        fun isClockVisible() = showingWallpaper()?.let { it.rearState != Display.STATE_OFF } == true

        /** Moves the gallery on if it's time, and catches the clock up; the rear woke. */
        fun refreshImage() {
            val activity = showingWallpaper() ?: return
            activity.wallpaper?.showImage(advance = true)
            activity.clock?.updateTime()
        }

        /** The clock alarm: see [onMinute]. */
        fun onMinuteAlarm() {
            showingWallpaper()?.onMinute()
        }

        /** Loads the gallery's current image again. False if the wallpaper isn't up. */
        fun reload(): Boolean {
            val activity = showingWallpaper() ?: return false
            activity.wallpaper?.showImage(advance = false, force = true)
            KeeperService.instance?.showChanges()
            return true
        }

        /** The clock or gallery timing changed, the pan speed changed, or a section that uses swipes was switched. */
        fun settingsChanged() {
            val activity = showing() ?: return
            activity.updateGestures()
            if (activity.popover) return
            activity.applyClock()
            activity.wallpaper?.panChanged()
            activity.wallpaper?.scheduleNextImage()
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
