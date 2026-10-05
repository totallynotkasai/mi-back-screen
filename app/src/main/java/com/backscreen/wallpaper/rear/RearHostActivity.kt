package com.backscreen.wallpaper.rear

import android.app.Activity
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.backscreen.wallpaper.battery.BatterySettings
import com.backscreen.wallpaper.battery.ChargingLayer
import com.backscreen.wallpaper.battery.ChargingStatus
import com.backscreen.wallpaper.battery.ChargingStyle
import com.backscreen.wallpaper.camera.CameraSettings
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.HostMode
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.RearOwner
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.notifications.NotificationAccess
import com.backscreen.wallpaper.notifications.NotificationFormatter
import com.backscreen.wallpaper.notifications.NotificationLayer
import com.backscreen.wallpaper.notifications.NotificationSettings
import com.backscreen.wallpaper.notifications.RearNotification
import com.backscreen.wallpaper.notifications.RearNotificationListener
import com.backscreen.wallpaper.notifications.ShownNotification
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
 * - the clock ([ClockLayer]);
 * - the charging animation ([ChargingLayer]), for a few seconds after you plug in;
 * - notifications ([NotificationLayer]): a banner when one arrives, and the list of the ones you
 *   haven't cleared, which you pull down from the top.
 *
 * [RearGestures] reads swipes on it: down for the notification list, left for Xiaomi Camera,
 * which opens over it as another app lent the back screen.
 *
 * KeeperService launches it straight onto the rear; if HyperOS won't allow that, it's launched
 * on the main display (transparent, so it doesn't flash) and its task moved to the rear, where
 * it's recreated. It shows what [RearState] says: the wallpaper, or, with the wallpaper off, a
 * pop-over over Xiaomi's screen, which is black with just the charging animation, and closes
 * when that's done.
 *
 * The rear lights and dims on Xiaomi's own timeout, as with Xiaomi's screen. If something
 * closes the wallpaper (Xiaomi taking the rear back), KeeperService puts it back.
 */
class RearHostActivity : Activity() {

    private lateinit var gestures: RearGestures
    private var wallpaper: WallpaperLayer? = null
    private var clock: ClockLayer? = null
    private var charging: ChargingLayer? = null
    private var notifications: NotificationLayer? = null

    private var onRear = false
    private var popover = false
    private var started = false
    private var startedAt = 0L
    private var finishRequested = false
    private var rearState = Display.STATE_UNKNOWN

    // A charging animation waiting for the back screen to light up: a dimmed one shows none of it.
    private var chargingDue: ChargingStatus? = null

    // A banner waiting for the back screen to light up, as it was just woken for it.
    private var notificationDue: ShownNotification? = null

    // A pull down on the notification list is under way, so touches still go to the gestures.
    private var pulling = false

    // How much of the charging animation and the notification list shows; the clock fades out by as much.
    private var chargingShown = 0f
    private var shadeShown = 0f

    private val redraw = Runnable {
        if (!isDestroyed) window.decorView.invalidate()
    }
    private val chargingGiveUp = Runnable { giveUpCharging() }
    private val notificationGiveUp = Runnable { showDueNotificationStill() }

    private val swipes = object : RearGestures.Listener {
        override fun onSwipe(swipe: Swipe) {
            BackScreen.log(if (swipe == Swipe.LEFT) "Swiped left on the back screen" else "Swiped down on the back screen")
            when (swipe) {
                Swipe.LEFT -> openCamera("swipe left")
                Swipe.DOWN -> if (gestures.pullDown && startShade()) notifications?.openShade()
            }
        }

        override fun onPull(dy: Float) {
            if (!pulling && !startShade()) return
            pulling = true
            notifications?.pull(dy)
        }

        override fun onPullEnd(open: Boolean) {
            if (!pulling) return
            pulling = false
            notifications?.endPull(open)
            if (open) BackScreen.log("Notification list pulled down")
        }
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
        if (!popover) applyKeepLit()
        if (!popover) addWallpaper(root, display)
        addCharging(root, display)
        if (!popover) addNotifications(root, display)
        gestures = RearGestures(this, swipes)

        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, BackScreen.mainHandler)
        onRearStateChanged()
        wallpaper?.showFirst()
        ClockAlarm.update(this)
        KeeperService.instance?.onHostShownOnRear(display.displayId)
        // What it was put up for, or a plug-in while the wallpaper was being put back.
        val due = takePendingCharging()
        when {
            due != null -> playCharging(due)
            popover -> KeeperService.instance?.onPopoverFinished()
            // Charging already: the faint Edge glow is simply there.
            else -> refreshFaint()
        }
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

    /**
     * The charging animation, over everything, keeping clear of the camera like the clock; the
     * Edge glow follows the screen's rounded edge. In the wallpaper the glow stays faint while
     * charging, and Minimal goes below the clock; in a pop-over the glow fades and Minimal is
     * centred.
     */
    private fun addCharging(root: FrameLayout, display: Display) {
        val camera = BackScreen.cameraInsets(display)
        charging = ChargingLayer(this).apply {
            setPadding(camera.left, camera.top, camera.right, camera.bottom)
            BackScreen.cornerShare(display)?.let { cornerShare = it }
            stayFaint = !popover
            centred = popover
            clock = this@RearHostActivity.clock
            setAutoAccent(wallpaper?.colour)
            onDone = ::onChargingDone
            onShown = { shown ->
                chargingShown = shown
                fadeClock()
            }
        }
        wallpaper?.onColourChanged = { charging?.setAutoAccent(it) }
        root.addView(charging)
    }

    /** Notifications, over everything, keeping clear of the camera like the clock. */
    private fun addNotifications(root: FrameLayout, display: Display) {
        val camera = BackScreen.cameraInsets(display)
        notifications = NotificationLayer(this).apply {
            setPadding(camera.left, camera.top, camera.right, camera.bottom)
            isLit = { this@RearHostActivity.isLit() }
            onShownChanged = { this@RearHostActivity.onShownChanged() }
            onShadeShown = { shown ->
                shadeShown = shown
                fadeClock()
            }
            onShadeClosed = {
                pulling = false
                if (RearState.owner(this@RearHostActivity) == RearOwner.Host(HostMode.SHADE)) BackScreen.log("Notification list closed")
                RearState.closeShade(this@RearHostActivity)
            }
        }
        root.addView(notifications)
    }

    /** The clock fades out while the charging animation or the notification list shows over it. */
    private fun fadeClock() {
        clock?.alpha = (1f - chargingShown) * (1f - shadeShown)
    }

    override fun onStart() {
        super.onStart()
        started = true
        startedAt = SystemClock.uptimeMillis()
        if (onRear) {
            if (isCurrent()) visibleOnRear = true
            wallpaper?.showImage(advance = true)
            clock?.updateTime()
            // In case the display listener missed the rear waking.
            onRearStateChanged()
            updateGestures()
            updateMotion()
            playDueCharging()
            // Plugged or unplugged while another app was in front.
            refreshFaint()
            showDueNotification()
            pushWhenDrawn()
            if (RearState.owner(this) is RearOwner.Lent) KeeperService.instance?.onHostShownWhileLent()
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
            // Covered, or the back screen went off: the list doesn't stay down behind it.
            notifications?.closeShade(animate = false)
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
        BackScreen.mainHandler.removeCallbacks(chargingGiveUp)
        BackScreen.mainHandler.removeCallbacks(notificationGiveUp)
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

    // Xiaomi's back gesture strip on the rear's right edge sends BACK: it closes the notification
    // list if that's down, and otherwise the host stays put. A swipe left that starts on the strip
    // reaches us only as BACK, so that opens the camera, as a swipe left does (decided in Phase 7).
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            !onRear -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
            notifications?.isShadeOpen == true -> notifications?.closeShade(animate = true)
            !started || !isLit() -> {}
            EdgeSwipe.opensCamera(SystemClock.uptimeMillis() - startedAt) -> {
                BackScreen.log("Swiped left from Xiaomi's back strip")
                openCamera("swipe left from the back strip")
            }
            else -> BackScreen.log("BACK just after the wallpaper came back; not opening the camera")
        }
    }

    /** A swipe left: Xiaomi Camera, while that section is on. */
    private fun openCamera(why: String) {
        if (popover || !CameraSettings.isEnabled(this)) return
        KeeperService.instance?.openCamera(why)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!onRear) return super.dispatchTouchEvent(event)
        // While the notification list is down, it takes every touch: to go back up, or stay.
        val layer = notifications
        if (layer != null && layer.isShadeOpen && !pulling) return layer.onShadeTouch(event)
        gestures.onTouchEvent(event, window.decorView.width, window.decorView.height)
        return super.dispatchTouchEvent(event)
    }

    /**
     * Swipes are read only while a section uses them, and touches only arrive while the rear is
     * lit. A pull down brings the notification list while that's on and notifications are read.
     */
    private fun updateGestures() {
        val shade = !popover && NotificationSettings.isEnabled(this) && NotificationSettings.swipeDown(this) &&
            RearNotificationListener.isListening
        gestures.enabled = !popover && (CameraSettings.isEnabled(this) || shade)
        gestures.pullDown = shade
        gestures.setLit(started && isLit())
        if (!shade) notifications?.closeShade(animate = false)
    }

    /** Images pan and GIFs play only while the rear is lit: a dimmed one shows none of it. */
    private fun updateMotion() {
        wallpaper?.moving = started && isLit()
    }

    /** The notification list starts coming down, if it may. */
    private fun startShade(): Boolean {
        if (!RearState.openShade(this)) return false
        refreshShade()
        return true
    }

    /** Fills the notification list with the ones in the phone's shade, as the style and privacy allow. */
    private fun refreshShade() {
        val layer = notifications ?: return
        val style = NotificationSettings.style(this)
        val locked = NotificationAccess.isLocked(this)
        val lockScreen = NotificationAccess.lockScreen(this)
        matchNotificationColors()
        layer.setShadeItems(
            RearNotificationListener.recent().mapNotNull { NotificationFormatter.format(it, style, locked, lockScreen) }
        )
    }

    private fun format(n: RearNotification) = NotificationFormatter.format(
        n, NotificationSettings.style(this), NotificationAccess.isLocked(this), NotificationAccess.lockScreen(this)
    )

    /**
     * Shows [n] in the banner. While the back screen is lit it slides in; while it's dimmed it's
     * just there, pushed to the panel; while it's off there's nothing to see. [woken]: the back
     * screen was just woken for it, so it waits for it to light up, a few seconds at most.
     * False if no banner goes up: the list is down and shows it already, or there's nothing to see.
     */
    private fun showNotification(n: ShownNotification, woken: Boolean): Boolean {
        val layer = notifications ?: return false
        if (layer.isShadeOpen) return false
        BackScreen.mainHandler.removeCallbacks(notificationGiveUp)
        notificationDue = null
        when {
            started && isLit() -> showBanner(n)
            woken -> {
                notificationDue = n
                BackScreen.mainHandler.postDelayed(notificationGiveUp, LIGHT_UP_WAIT_MS)
            }
            !started || display?.state == Display.STATE_OFF -> {
                BackScreen.log("The back screen is off; no banner")
                return false
            }
            else -> showBanner(n)
        }
        return true
    }

    private fun showBanner(n: ShownNotification) {
        matchNotificationColors()
        notifications?.showBanner(n, NotificationFormatter.bannerMs(n))
    }

    private fun showDueNotification() {
        val n = notificationDue ?: return
        if (!started || !isLit()) return
        notificationDue = null
        BackScreen.mainHandler.removeCallbacks(notificationGiveUp)
        showBanner(n)
    }

    /** The back screen didn't light up for it: shown dimmed, if it's on at all. */
    private fun showDueNotificationStill() {
        val n = notificationDue ?: return
        notificationDue = null
        if (started && display?.state != Display.STATE_OFF) showBanner(n) else BackScreen.log("Back screen didn't light up; no banner")
    }

    /** The clock's colours, so it matches the wallpaper; light on dark without one. */
    private fun matchNotificationColors() {
        val clock = clock?.takeIf { it.visibility == View.VISIBLE }
        if (clock != null) notifications?.setColors(clock.textColor, clock.autoBgColor) else notifications?.setColors(Color.WHITE, Color.BLACK)
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
        val wasLit = rearState == Display.STATE_ON
        rearState = state
        updateGestures()
        updateMotion()
        if (lit) {
            playDueCharging()
            showDueNotification()
        } else {
            if (charging?.isPlaying == true) {
                // A dimmed back screen would keep a half-faded frame of it; show the wallpaper.
                charging?.cancel()
                onShownChanged()
            }
            // No half-slid banner or half-open list either.
            if (wasLit) notifications?.dimmed()
        }
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

    /**
     * Stay fully lit while unlocked: the window keeps the back screen on while it's in front
     * (first check 3). Only the wallpaper's, never a pop-over's.
     */
    private fun applyKeepLit() {
        if (keepLitWanted) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Plays the charging animation, once the back screen is lit. If it doesn't light up within
     * moments, it's dropped: a dimmed back screen shows none of it.
     */
    private fun playCharging(status: ChargingStatus) {
        BackScreen.mainHandler.removeCallbacks(chargingGiveUp)
        chargingDue = status
        if (started && isLit()) {
            playDueCharging()
        } else {
            BackScreen.mainHandler.postDelayed(chargingGiveUp, LIGHT_UP_WAIT_MS)
        }
    }

    private fun playDueCharging() {
        val status = chargingDue ?: return
        val layer = charging ?: return
        if (!started || !isLit()) return
        chargingDue = null
        BackScreen.mainHandler.removeCallbacks(chargingGiveUp)
        matchCharging(layer)
        layer.play(status)
        BackScreen.log("Charging animation on back screen (${BatterySettings.style(this).name.lowercase()}, ${status.level}%)")
    }

    /**
     * The chosen style and colour, and the clock's colours for the text, so it matches the
     * wallpaper; light on a dark veil without one.
     */
    private fun matchCharging(layer: ChargingLayer) {
        layer.style = BatterySettings.style(this)
        layer.accent = BatterySettings.color(this)
        val clock = clock?.takeIf { it.visibility == View.VISIBLE }
        if (clock != null) layer.setColors(clock.textColor, clock.autoBgColor) else layer.setColors(Color.WHITE, Color.BLACK)
    }

    private fun giveUpCharging() {
        chargingDue ?: return
        chargingDue = null
        BackScreen.log("Back screen didn't light up; no charging animation")
        onChargingDone()
        refreshFaint()
    }

    /** You unplugged: one waiting is dropped, and one playing, or the faint glow, fades away. */
    private fun stopCharging() {
        if (chargingDue != null) {
            BackScreen.mainHandler.removeCallbacks(chargingGiveUp)
            chargingDue = null
            onChargingDone()
        }
        val layer = charging ?: return
        if (!layer.isPlaying && !layer.isFaint) return
        if (layer.isPlaying) BackScreen.log("Unplugged; charging animation stopped") else BackScreen.log("Unplugged; edge glow off")
        if (started && isLit()) {
            layer.stop()
        } else {
            // Dimmed: no fade would reach the panel, so it goes at once, pushed there.
            layer.clear()
            onShownChanged()
        }
    }

    /**
     * The faint Edge glow, while charging with that style in the wallpaper: shown if it should be
     * and isn't, gone if it shouldn't. A dimmed back screen gets the change like any other.
     */
    private fun refreshFaint() {
        val layer = charging ?: return
        if (popover || layer.isPlaying || chargingDue != null) return
        val wanted = BatterySettings.isEnabled(this) && BatterySettings.style(this) == ChargingStyle.EDGE_GLOW &&
            ChargingStatus.isPlugged(this)
        if (wanted == layer.isFaint) return
        if (wanted) {
            matchCharging(layer)
            layer.showFaint()
            BackScreen.log("Charging; edge glow on")
        } else {
            layer.clear()
            BackScreen.log("Edge glow off")
        }
        onShownChanged()
    }

    /** A pop-over has done what it was up for. */
    private fun onChargingDone() {
        if (popover) KeeperService.instance?.onPopoverFinished()
    }

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
     * Back in front on a dimmed back screen, after another app such as Xiaomi Camera closed: once
     * the wallpaper has drawn its first frame again, push it to the panel. Pushed straight away,
     * the panel got the wallpaper as it was before the camera opened, minutes old (Phase 7).
     */
    private fun pushWhenDrawn() {
        val state = display?.state
        if (state == Display.STATE_ON || state == Display.STATE_OFF) return
        window.decorView.viewTreeObserver.registerFrameCommitCallback { onShownChanged() }
        window.decorView.invalidate()
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

        // A wake key lights the back screen within a moment; a pop-over can take 1.5 s to go up.
        private const val LIGHT_UP_WAIT_MS = 3000L
        private const val PENDING_MS = 5000L

        private var current: WeakReference<RearHostActivity>? = null

        // A charging animation for a host that isn't up yet: a pop-over going up for it, or
        // the wallpaper being put back.
        private var pendingCharging: ChargingStatus? = null
        private var pendingSince = 0L

        var visibleOnRear = false
            private set

        // Stay fully lit while unlocked, as DisplayKeeper last said, for this host and the next.
        private var keepLitWanted = false

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

        /** Stay fully lit while unlocked started or ended ([com.backscreen.wallpaper.core.DisplayKeeper]). */
        fun keepLit(on: Boolean) {
            keepLitWanted = on
            showingWallpaper()?.applyKeepLit()
        }

        /** The clock alarm: see [onMinute]. */
        fun onMinuteAlarm() {
            showingWallpaper()?.onMinute()
        }

        /**
         * Plays the charging animation in the host that's up, or in the next one if it goes up
         * within moments. It waits for the back screen to be lit (see [playCharging]).
         */
        fun playCharging(status: ChargingStatus) {
            val host = showing()
            if (host == null) {
                pendingCharging = status
                pendingSince = SystemClock.elapsedRealtime()
            } else {
                pendingCharging = null
                host.playCharging(status)
            }
        }

        /** You unplugged. */
        fun stopCharging() {
            pendingCharging = null
            showing()?.stopCharging()
        }

        /**
         * Plugged in with nothing played (the back covered, say), or the Battery switch, style or
         * colour changed: the faint Edge glow follows, and the next animation takes the change.
         */
        fun chargingChanged() {
            val host = showingWallpaper() ?: return
            host.charging?.takeIf { it.isFaint }?.let(host::matchCharging)
            host.refreshFaint()
        }

        private fun takePendingCharging(): ChargingStatus? {
            val status = pendingCharging ?: return null
            pendingCharging = null
            return status.takeIf { SystemClock.elapsedRealtime() - pendingSince < PENDING_MS }
        }

        /**
         * A new notification for the banner, as the style and privacy allow. [woken]: the back
         * screen was just woken for it. False if no banner goes up: the wallpaper isn't up,
         * nothing of it may show, or the back screen is off.
         */
        fun showNotification(n: RearNotification, woken: Boolean): Boolean {
            val host = showingWallpaper() ?: return false
            val shown = host.format(n) ?: return false
            return host.showNotification(shown, woken)
        }

        /** A notification was cleared on the phone: off the banner and the list too. */
        fun notificationRemoved(key: String) {
            val host = showingWallpaper() ?: return
            if (host.notificationDue?.key == key) host.notificationDue = null
            host.notifications?.removeFromBanner(key)
            if (host.notifications?.isShadeOpen == true) host.refreshShade()
        }

        /**
         * The notifications in the phone's shade changed, reading them started or stopped, or a
         * notification setting changed. Nothing new to see, so unlike [settingsChanged] the back
         * screen isn't woken for it.
         */
        fun notificationsChanged() {
            val host = showingWallpaper() ?: return
            host.updateGestures()
            if (host.notifications?.isShadeOpen == true) host.refreshShade()
        }

        /**
         * Swipe left for Xiaomi Camera was switched. Like [notificationsChanged], there's nothing
         * new to see, so the back screen isn't woken for it.
         */
        fun swipesChanged() {
            showingWallpaper()?.updateGestures()
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
