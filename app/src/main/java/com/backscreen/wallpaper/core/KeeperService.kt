package com.backscreen.wallpaper.core

import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Surface
import com.backscreen.wallpaper.LaunchCommand
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.battery.BatterySettings
import com.backscreen.wallpaper.battery.ChargingMonitor
import com.backscreen.wallpaper.battery.ChargingStatus
import com.backscreen.wallpaper.camera.XiaomiCamera
import com.backscreen.wallpaper.mirror.AppLabels
import com.backscreen.wallpaper.mirror.MirrorSettings
import com.backscreen.wallpaper.mirror.QuickSwitch
import com.backscreen.wallpaper.mirror.QuickSwitchTile
import com.backscreen.wallpaper.notifications.AppWakeLimit
import com.backscreen.wallpaper.notifications.NotificationAccess
import com.backscreen.wallpaper.notifications.NotificationFilter
import com.backscreen.wallpaper.notifications.NotificationSettings
import com.backscreen.wallpaper.notifications.RearNotification
import com.backscreen.wallpaper.notifications.SampleNotification
import com.backscreen.wallpaper.rear.BackCover
import com.backscreen.wallpaper.rear.BackSensor
import com.backscreen.wallpaper.rear.RearHostActivity
import com.backscreen.wallpaper.wallpaper.ClockAlarm
import com.backscreen.wallpaper.wallpaper.FolderAccess
import com.backscreen.wallpaper.wallpaper.Gallery
import com.backscreen.wallpaper.wallpaper.WallpaperSettings
import rikka.shizuku.Shizuku
import java.util.UUID
import kotlin.math.max

/**
 * Looks after the back screen for every section: puts our host window there and keeps it there
 * while the wallpaper is on, puts it up for a moment over Xiaomi's screen (a pop-over) while
 * the wallpaper is off, and leaves the back screen alone while another app has it. Who has it
 * is [RearState]. Runs while any section needs it, and stops itself when none do.
 *
 * Putting the host up: it's launched straight onto the rear display (allowed while unlocked
 * because the manifest carries Xiaomi's `miui.rear.policy` opt-in). When locked, or if HyperOS
 * refuses that, it's launched on the main display instead and its task moved to the rear,
 * which HyperOS doesn't block. Each step needs shell access, which Shizuku provides (see
 * RearCommands).
 *
 * Staying there: each time the rear goes from lit to dimmed, Xiaomi's back screen app brings
 * its launcher back to the front and closes the app that was on top, but only if that launcher
 * has been created since Xiaomi's app last started (that's how its code reads, HyperOS 3.0.319).
 * So once the wallpaper is up, if Xiaomi's launcher is underneath, Xiaomi's app is restarted
 * once; it comes straight back without the launcher and leaves the wallpaper alone. If the
 * wallpaper is closed anyway, it's put back. (1.3 restarted Xiaomi's app every time the
 * wallpaper looked covered, which relit the rear every ~10 s; this is once per turn-on.)
 *
 * A pop-over goes over Xiaomi's launcher and Xiaomi's app is left as it is: when the pop-over
 * closes, Xiaomi's launcher is simply underneath. If the rear dims first, Xiaomi closes the
 * pop-over itself, which just ends it early.
 *
 * Charging: while Battery is on, plugging in plays the charging animation in the wallpaper, or
 * in a pop-over if the wallpaper is off and that's allowed (see [onPluggedIn]).
 *
 * Notifications: a new one goes in a banner in the wallpaper (see [onNotification]); with the
 * wallpaper off, Xiaomi's own back screen shows it.
 *
 * Everything that lights the back screen goes through [lightRear], and [WakeThrottle] decides
 * for the things that want to be seen, so they can't light it in a burst.
 *
 * Quick Switch ([QuickSwitch]): while an app is lent the back screen, a watchdog checks it's
 * still there ([LendWatchdog]), the app's notification says so with Bring back, and covering the
 * back can bring it back ([BackCover]). Whatever the lend changed in Xiaomi's settings
 * ([RearTweaks]) is put back when it ends, or when this starts with nothing lent; if that can't
 * be done yet (Shizuku has stopped), this keeps running, says so, and tries again when it can.
 *
 * Xiaomi Camera ([XiaomiCamera]): a swipe left on the wallpaper, or Open camera, opens it on the
 * back screen as another lend, watched the same way. It closes when the back screen dims or goes
 * off, when the back is covered, and from Close camera in its notification.
 *
 * Back screen display, on the Wallpaper tab: Stays lit for is Xiaomi's own timeout
 * ([XiaomiLitTime]), checked again when this starts or Shizuku connects. While the wallpaper is
 * up, [DisplayKeeper] keeps the back screen lit while you use the phone, and lights it again when
 * you pick the phone up after it went dark lying on its back.
 *
 * Restarts: after the phone or this app restarts, it carries on. If Shizuku isn't running yet
 * (after a phone restart it isn't until you start it), the wallpaper waits for it rather than
 * switching off (see [waitForShizuku]).
 */
class KeeperService : Service() {

    // Stopped first thing in onDestroy: shell work posted or queued after that is dropped.
    private val shellRunner = ShellRunner()
    private val destroyed get() = shellRunner.stopped
    private var lastApply = 0L

    // What the notification said when it was last posted; null until the service is in the foreground.
    private var posted: NotificationSays? = null

    // The latest start. Stopping with it can't race a start that's still on its way, which
    // would then find no service to call startForeground.
    private var lastStartId = 0

    // A put-back of Xiaomi's settings couldn't run, or didn't finish: the notification says that's
    // why this is still running (see puttingBack).
    private var putBackStalled = false

    // Set when a direct launch fails while unlocked: until then (elapsedRealtime), use the main
    // display route. Not for good: one slow launch shouldn't slow every later one.
    private var directLaunchBlockedUntil = 0L
    private var awaitingDirectLaunch = false

    // The host about to go up is lit up when it shows, if it does so before then (elapsedRealtime):
    // only for a launch that shows it now, never one much later.
    private var wakeWhenShownUntil = 0L

    // When Xiaomi's "back screen turned on" last came (elapsedRealtime), to take a burst as one.
    private var lastSubScreenOn = 0L

    /**
     * The token the host window last launched carries; it closes itself unless it matches (see
     * RearHostActivity). New for each launch, and cleared when nothing of ours should be up, so
     * no other app can put it up, and neither can a launch overtaken by switching the wallpaper off.
     */
    var hostToken: String? = null
        private set

    /** The wallpaper is on but couldn't go up, because Shizuku isn't ready (see [waitForShizuku]). */
    var isWaitingForShizuku = false
        private set

    private val charging = ChargingMonitor(
        this, ::onPluggedIn, ::onUnplugged,
        onHeldBack = { RearHostActivity.chargingChanged() },
        onSourceChanged = RearHostActivity::chargingSourceChanged,
    )
    private val wakes = WakeThrottle()
    private val appWakes = AppWakeLimit()

    /** Moving the app you're using to the back screen and back. */
    val quickSwitch = QuickSwitch(this)

    /** Xiaomi Camera on the back screen. */
    val camera = XiaomiCamera(this)
    private val watchdog = LendWatchdog(::shell, ::rearDisplayId, ::onLentAppGone)
    // Lazy: it finds the sensor, which needs the service's context.
    private val backCover by lazy { BackCover(this, ::onBackCovered) }

    /** Stay fully lit while unlocked, and Light up when you pick it up. */
    private val displayOptions by lazy {
        DisplayKeeper(this, ::lightRear, ::hyperOsWouldCover) { wakes.allow(SystemClock.elapsedRealtime(), isRearLit()) }
    }

    // After an app is sent, the back screen is lit for it once that won't bring up HyperOS's
    // cover: until then (elapsedRealtime), and not before Xiaomi's app has settled.
    private var lightForLendUntil = 0L
    private var lightForLendSettled = 0L
    private var lightForLendWaitLogged = false
    private val lightForLendAgain = Runnable { tryLightForLend() }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        shellRunner.runWaiting()
        BackScreen.mainHandler.post {
            applyIfWaiting()
            putBackTweaksIfIdle()
            checkLitTime()
        }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) BackScreen.mainHandler.post {
            applyIfWaiting()
            putBackTweaksIfIdle()
            checkLitTime()
        }
    }
    private val directLaunchTimeout = Runnable { onDirectLaunchFailed() }
    private val checkRear = Runnable { checkRearDisplay() }
    private val checkRearLate = Runnable { checkRearDisplay() }
    private val putBack = Runnable { checkRearDisplay() }
    private val wakeForChanges = Runnable { wakeRear() }
    private val popoverDone = Runnable { endPopover("done") }

    // The rear's state when it last changed, to tell it waking from its other changes.
    private var rearState = Display.STATE_UNKNOWN

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // The main screen turning off or upright may be what a sent app's light waits for.
            if (displayId == Display.DEFAULT_DISPLAY) {
                displayOptions.update()
                return tryLightForLend()
            }
            val rear = BackScreen.findRearDisplay(this@KeeperService) ?: return
            if (displayId != rear.displayId) return
            val woke = rear.state == Display.STATE_ON && rearState != Display.STATE_ON
            val changed = rear.state != rearState
            rearState = rear.state
            // Xiaomi Camera closes when the back screen dims or goes off.
            if (changed) camera.rearChanged(rear.state == Display.STATE_ON)
            if (changed) displayOptions.update()
            tryLightForLend()
            if (rear.state != Display.STATE_ON) return
            // The gallery doesn't move on while the phone sleeps; catch up now it can be seen.
            // Only on waking: the lit rear reports other changes several times a second, and
            // reading a big folder each time held up the pan.
            if (woke) RearHostActivity.refreshImage()
            scheduleRearCheck()
        }
    }

    // HyperOS's own "back screen turned on" broadcast. Can arrive when the display listener
    // doesn't fire (the doze layer reports the display as off). It's a protected broadcast, so
    // only the system and its own apps can send it (HyperOS 3.0.319); which one does isn't known,
    // so the receiver stays exported. All it does is trigger a check, at most once a second.
    private val subScreenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSubScreenOn < SUB_SCREEN_ON_GAP_MS) return
            lastSubScreenOn = now
            RearHostActivity.refreshImage()
            scheduleRearCheck()
        }
    }

    // Unlocking: the back screen may need keeping lit now.
    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = displayOptions.update()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, BackScreen.mainHandler)
        registerReceiver(subScreenOn, IntentFilter(ACTION_SUB_SCREEN_ON), RECEIVER_EXPORTED)
        // Only the system sends it, and it reaches receivers that aren't exported.
        registerReceiver(userPresent, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)
        getSystemService(NotificationManager::class.java).deleteNotificationChannel(OLD_CHANNEL_ID)
        BackScreen.logRearDisplay(this)
        charging.start()
        val lend = (RearState.owner(this) as? RearOwner.Lent)?.lend
        if (lend != null) {
            // Saved before this process last stopped: make sure it's still there before trusting it.
            BackScreen.log("Checking ${lend.packageName} is still on the back screen")
            watchdog.watch(lend, now = true)
            camera.resumed()
            updateCover()
        } else {
            putBackTweaksIfIdle()
        }
        checkLitTime()
        displayOptions.update()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Once in the foreground, a command needn't post it again, so a notification you swiped
        // away stays away.
        if (!updateNotification()) {
            // Android wouldn't let it into the foreground: restarted from the background after
            // the process was killed, say. Stop rather than crash; the next alarm, restart, app
            // open or tile tap starts it properly.
            BackScreen.log("Couldn't start in the foreground; stopping until the next start")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_RESTORE -> restore()
            ACTION_TOGGLE -> if (WallpaperSettings.isEnabled(this)) restore() else turnOn()
            ACTION_APPLY -> turnOn()
            ACTION_SCHEDULED_ON -> turnOn(byYou = false)
            ACTION_UPDATE -> {
                mirrorChanged()
                stopIfIdle()
            }
            ACTION_PLAY_CHARGING -> playChargingNow()
            ACTION_TRY_NOTIFICATION -> tryNotification()
            ACTION_QUICK_SWITCH -> quickSwitch.toggle()
            ACTION_BRING_BACK -> quickSwitch.bringBack("the notification")
            ACTION_MIRROR_CHANGED -> mirrorChanged()
            ACTION_OPEN_CAMERA -> {
                camera.open(intent.getStringExtra(EXTRA_WHY) ?: "the Camera tab")
                stopIfIdle()
            }
            ACTION_CLOSE_CAMERA -> {
                camera.close(intent.getStringExtra(EXTRA_WHY) ?: "the notification")
                stopIfIdle()
            }
            // ACTION_RESUME after the phone restarts or the app updates, or none: the system
            // restarted us after the process was killed.
            else -> {
                if (RearState.snapshot(this).guardsWallpaper) apply()
                stopIfIdle()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shellRunner.stop()
        instance = null
        hostToken = null
        BackScreen.mainHandler.removeCallbacks(checkRear)
        BackScreen.mainHandler.removeCallbacks(checkRearLate)
        BackScreen.mainHandler.removeCallbacks(putBack)
        BackScreen.mainHandler.removeCallbacks(wakeForChanges)
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        BackScreen.mainHandler.removeCallbacks(popoverDone)
        BackScreen.mainHandler.removeCallbacks(lightForLendAgain)
        watchdog.stop()
        backCover.stop()
        displayOptions.stop()
        camera.ended()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        unregisterReceiver(subScreenOn)
        unregisterReceiver(userPresent)
        charging.stop()
        super.onDestroy()
    }

    /** Stops the service once no section needs it, unless another start is on its way. */
    private fun stopIfIdle() {
        if (destroyed || RearState.keeperNeeded(this)) return
        stopSelfResult(lastStartId)
    }

    /**
     * Works with no images too: the back screen is black, with the clock if that's on. [byYou]:
     * from the app, tile or widget. If Shizuku isn't ready, only switching it on from off gives
     * up (the switch going back off says so); a schedule's turn-on, or an option changed while
     * it's on (more images, Avoid camera), waits for Shizuku instead.
     */
    private fun turnOn(byYou: Boolean = true) {
        val wasOn = WallpaperSettings.isEnabled(this)
        WallpaperSettings.setEnabled(this, true)
        RearState.wallpaperChanged(this, true)
        displayOptions.update()
        // Says so if its folder can't be opened.
        updateNotification()
        BackScreen.mainHandler.removeCallbacks(popoverDone)
        val owner = RearState.owner(this)
        if (owner is RearOwner.Lent) {
            // Not lit up for later, when the app leaves: that may be hours from now.
            wakeWhenShownUntil = 0L
            BackScreen.log("Wallpaper on; it shows when ${owner.lend.packageName} leaves the back screen")
            return
        }
        // You just asked for it, so light the back screen up to show it (a schedule doesn't).
        wakeWhenShownUntil = if (byYou) SystemClock.elapsedRealtime() + WAKE_WHEN_SHOWN_MS else 0L
        apply(orWait = !byYou || isWaitingForShizuku || wasOn)
    }

    /**
     * Puts the wallpaper up afresh. If Shizuku isn't ready it waits for it ([waitForShizuku]), or,
     * unless [orWait], switches the wallpaper off.
     */
    private fun apply(orWait: Boolean = true) {
        lastApply = SystemClock.elapsedRealtime()
        BackScreen.mainHandler.removeCallbacks(putBack)
        launchHost("Applying wallpaper...", if (orWait) ::waitForShizuku else ::fail)
    }

    /**
     * Shizuku isn't ready, for a turn-on nobody is watching: after the phone restarts, Shizuku
     * isn't running until you start it. Rather than switch the wallpaper off, wait, and put it up
     * as soon as Shizuku connects or allows this app ([applyIfWaiting]).
     */
    private fun waitForShizuku() {
        if (isWaitingForShizuku) return
        isWaitingForShizuku = true
        BackScreen.log("Waiting for Shizuku; the wallpaper goes up once it's running")
        updateNotification()
    }

    private fun applyIfWaiting() {
        if (!isWaitingForShizuku || !RearCommands.isReady()) return
        stopWaiting()
        BackScreen.log("Shizuku is ready")
        if (RearState.snapshot(this).guardsWallpaper) apply()
    }

    /** No longer waiting for Shizuku: it's ready, or the wallpaper went off. The notification stops saying so. */
    private fun stopWaiting() {
        if (!isWaitingForShizuku) return
        isWaitingForShizuku = false
        updateNotification()
    }

    /**
     * Launches the host, replacing it if it's up. It shows whatever [RearState] says. Each launch
     * has a new [hostToken], so one overtaken by a newer launch, or by switching off, closes itself.
     */
    private fun launchHost(what: String, onUnavailable: () -> Unit = ::fail) {
        if (destroyed) return
        RearHostActivity.finishCurrent()
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        BackScreen.log(what)
        val token = UUID.randomUUID().toString()
        hostToken = token
        val rear = BackScreen.findRearDisplay(this)?.displayId
        // HyperOS only honours our rear display opt-in while the phone is unlocked.
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val popover = RearState.owner(this) == RearOwner.Popover
        val unavailable: () -> Unit = if (popover) ({ endPopover("Shizuku isn't ready") }) else onUnavailable
        if (rear == null || locked || SystemClock.elapsedRealtime() < directLaunchBlockedUntil) {
            awaitingDirectLaunch = false
            shell(unavailable) {
                val result = RearCommands.launchHost(packageName, Display.DEFAULT_DISPLAY, token)
                BackScreen.log("Open on main screen, to move: $result")
            }
            return
        }
        awaitingDirectLaunch = true
        shell(unavailable) {
            val result = RearCommands.launchHost(packageName, rear, token)
            BackScreen.log("Open on back screen: $result")
            // Give it a moment to appear; if it doesn't, use the main display route.
            BackScreen.mainHandler.postDelayed(
                directLaunchTimeout, if (result == "ok") DIRECT_LAUNCH_TIMEOUT_MS else 0
            )
        }
    }

    private fun onDirectLaunchFailed() {
        if (!awaitingDirectLaunch) return
        awaitingDirectLaunch = false
        directLaunchBlockedUntil = SystemClock.elapsedRealtime() + DIRECT_LAUNCH_BLOCKED_MS
        BackScreen.log("Back screen refused a direct launch; going via the main screen for a while")
        launchHost("Opening via the main screen...")
    }

    /** Called by RearHostActivity when it has been created on the main display, by the launch with [token]. */
    fun onHostCreatedOnMain(taskId: Int, token: String) {
        // HyperOS may put a direct launch on the main display instead; moving it fixes that.
        awaitingDirectLaunch = false
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        val rear = BackScreen.findRearDisplay(this)?.displayId ?: return abandonHost("the back screen wasn't found", token)
        shell(onUnavailable = { abandonHost("Shizuku isn't ready", token) }) {
            val result = RearCommands.moveTaskToDisplay(taskId, rear)
            BackScreen.log("Move to back screen: $result")
            if (result != "ok") BackScreen.mainHandler.post { abandonHost("it couldn't be moved", token) }
        }
    }

    /**
     * The host the launch with [token] opened on the main screen, to be moved to the back screen,
     * can't be moved: close it, rather than leave a see-through window over the main screen. A
     * pop-over ends; the wallpaper is left to the usual checks, which put it back when they can.
     * Nothing if a newer launch has taken over, or the window reached the back screen after all
     * (a move that ran out of time may still have happened).
     */
    fun abandonHost(why: String, token: String) {
        if (token != hostToken || RearHostActivity.isOnRear()) return
        BackScreen.log("Back screen window not moved there ($why); closing it")
        hostToken = null
        wakeWhenShownUntil = 0L
        RearHostActivity.finishCurrent()
        endPopover(why)
    }

    /**
     * A host window closed itself on opening, its launch overtaken by a newer one (see
     * [hostToken]). The newer launch can be taken by that window as it went, so if nothing of
     * ours is up in a moment, it's put back.
     */
    fun onHostRefused() {
        if (hostToken != null) scheduleRearCheck()
    }

    /** Called by RearHostActivity when it is showing on the rear display. */
    fun onHostShownOnRear(displayId: Int) {
        val wake = SystemClock.elapsedRealtime() < wakeWhenShownUntil && !hyperOsWouldCover()
        awaitingDirectLaunch = false
        wakeWhenShownUntil = 0L
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        // Only for the wallpaper, which stays: a pop-over is gone in seconds.
        val guard = RearState.snapshot(this).guardsWallpaper
        if (wake) wakes.woke(SystemClock.elapsedRealtime())
        shell(onUnavailable = {}) {
            if (wake) BackScreen.log("Wake back screen: ${RearCommands.wakeDisplay(displayId)}")
            if (guard) removeXiaomiLauncher(displayId)
        }
        displayOptions.update()
    }

    /**
     * Restarts Xiaomi's back screen app if its launcher is under the wallpaper. Not while the
     * launcher is in front: Android would start it again straight away. The wallpaper is put
     * back on top first, and this runs again then. Shell thread.
     */
    private fun removeXiaomiLauncher(displayId: Int) {
        val tasks = RearCommands.taskList() ?: return
        val packages = TaskList.packagesOn(tasks, displayId)
        if (packages.firstOrNull() != packageName || RearCommands.XIAOMI_REAR_PACKAGE !in packages) return
        BackScreen.log("Restart Xiaomi back screen app, without its launcher: ${RearCommands.restartXiaomiRearApp()}")
    }

    private fun restore() {
        WallpaperSettings.setEnabled(this, false)
        RearState.wallpaperChanged(this, false)
        displayOptions.update()
        stopWaiting()
        // A folder it couldn't open no longer matters.
        updateNotification()
        awaitingDirectLaunch = false
        wakeWhenShownUntil = 0L
        // A launch still on its way closes itself when it arrives.
        hostToken = null
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        BackScreen.mainHandler.removeCallbacks(putBack)
        RearHostActivity.finishCurrent()
        ClockAlarm.update(this)
        if (RearState.owner(this) is RearOwner.Lent) {
            // Bringing Xiaomi's launcher up now would cover the app; it comes back when that leaves.
            stopIfIdle()
            return
        }
        restoreXiaomi()
    }

    /** Brings Xiaomi's launcher to the front of the back screen. */
    private fun restoreXiaomi() {
        val rear = BackScreen.rearIdOrLastKnown(this) ?: return stopIfIdle()
        shell(onUnavailable = { stopIfIdle() }) {
            // Usually already underneath; this makes sure it's there.
            BackScreen.log("Restore Xiaomi back screen: ${RearCommands.restoreXiaomiRearLauncher(rear)}")
            BackScreen.mainHandler.post { stopIfIdle() }
        }
    }

    /** Couldn't turn on: show "off" everywhere and stop. */
    private fun fail() {
        WallpaperSettings.setEnabled(this, false)
        RearState.wallpaperChanged(this, false)
        displayOptions.update()
        stopWaiting()
        updateNotification()
        wakeWhenShownUntil = 0L
        hostToken = null
        RearHostActivity.finishCurrent()
        ClockAlarm.update(this)
        stopIfIdle()
    }

    /**
     * Puts the host up over Xiaomi's back screen for [durationMs], for something that can't wait
     * for the wallpaper because it's off. False if it can't: with the wallpaper on, it goes in
     * the host instead (see [RearSnapshot.routeOverlay]); with an app lent the back screen, it's
     * dropped.
     */
    fun showPopover(durationMs: Long, wake: Boolean): Boolean {
        if (!RearState.startPopover(this)) return false
        wakeWhenShownUntil = if (wake) SystemClock.elapsedRealtime() + WAKE_WHEN_SHOWN_MS else 0L
        BackScreen.mainHandler.removeCallbacks(popoverDone)
        BackScreen.mainHandler.postDelayed(popoverDone, durationMs)
        launchHost("Pop-over on back screen...")
        return true
    }

    /** The host has shown what the pop-over was up for. */
    fun onPopoverFinished() = endPopover("done")

    /** The pop-over is over: close the host, and Xiaomi's launcher is there underneath. */
    private fun endPopover(why: String) {
        BackScreen.mainHandler.removeCallbacks(popoverDone)
        if (RearState.owner(this) != RearOwner.Popover) return
        RearState.endPopover(this)
        awaitingDirectLaunch = false
        wakeWhenShownUntil = 0L
        hostToken = null
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        RearHostActivity.finishCurrent()
        BackScreen.log("Pop-over ended: $why")
        stopIfIdle()
    }

    /**
     * Another app now has the back screen: nothing of ours covers it until [onLendEnded]. The
     * host stays underneath if it's up. False if an app has it already.
     */
    fun onLent(lend: Lend): Boolean {
        endPopover("${lend.packageName} took the back screen")
        if (!RearState.lend(this, lend)) return false
        // Anything still to put back is the lend's now, and is put back when it ends.
        putBackStalled = false
        BackScreen.mainHandler.removeCallbacks(putBack)
        BackScreen.mainHandler.removeCallbacks(checkRear)
        BackScreen.mainHandler.removeCallbacks(checkRearLate)
        BackScreen.log("${lend.packageName} has the back screen (${lend.reason.name.lowercase()})")
        ClockAlarm.update(this)
        watchdog.watch(lend)
        updateCover()
        lentChanged()
        return true
    }

    /**
     * [lend]'s app left the back screen: it goes to the wallpaper or Xiaomi's screen, whichever
     * is on now, and whatever the lend changed in Xiaomi's settings is put back. Nothing if
     * [lend] has ended already, or isn't the one there: callers pass the lend they acted on.
     */
    fun onLendEnded(lend: Lend) {
        if (!RearState.endLend(this, lend)) return
        BackScreen.log("Back screen returned")
        watchdog.stop()
        backCover.stop()
        camera.ended()
        lightForLendUntil = 0L
        BackScreen.mainHandler.removeCallbacks(lightForLendAgain)
        ClockAlarm.update(this)
        lentChanged()
        putBackTweaksIfIdle()
        if (RearState.snapshot(this).guardsWallpaper) {
            // Usually still underneath, and showing again; put back if not.
            scheduleRearCheck()
        } else {
            restoreXiaomi()
        }
    }

    /** The watchdog found the lent app gone: closed, moved back, or sent behind. */
    private fun onLentAppGone(lend: Lend) {
        BackScreen.log("${lend.packageName} left the back screen")
        onLendEnded(lend)
    }

    /** An app came or went: the notification and the tile say so, and the wallpaper's display options follow. */
    private fun lentChanged() {
        updateNotification()
        QuickSwitchTile.requestUpdate(this)
        displayOptions.update()
    }

    /** An option on the Back screen display card changed. */
    fun displaySettingsChanged() = displayOptions.update()

    /**
     * Makes Xiaomi's timeout match Stays lit for, in case something reset it, or after it changed
     * ([XiaomiLitTime.changed]); nothing while it's As set, once Xiaomi's own value is back.
     */
    fun checkLitTime() {
        if (DisplaySettings.litTime(this).ms == null && DisplaySettings.xiaomiLitTime(this) == null) return
        // If Shizuku isn't ready, it's tried again when it connects.
        shell(onUnavailable = ::putBackDidntRun) {
            XiaomiLitTime.apply(this)
            BackScreen.mainHandler.post(::afterPutBack)
        }
    }

    /** Puts back what an earlier lend changed in Xiaomi's settings, once nothing is lent. */
    private fun putBackTweaksIfIdle() {
        if (RearState.owner(this) is RearOwner.Lent || !RearTweaks.pending(this)) return
        val rear = rearDisplayId() ?: return
        // If Shizuku isn't ready, or a write fails, it's tried again when it connects, the keeper
        // starts or a lend ends.
        shell(onUnavailable = ::putBackDidntRun) {
            if (RearState.isLentAnyThread(this)) return@shell
            RearTweaks.putBackAll(this, rear)
            BackScreen.mainHandler.post(::afterPutBack)
        }
    }

    // While an app is lent, the settings it changed are its own, not a put-back that's stuck.
    private fun putBackDidntRun() {
        if (RearState.owner(this) is RearOwner.Lent) return
        putBackStalled = true
        updateNotification()
    }

    /** A put-back ran: the notification says if anything's still waiting, and this stops if nothing is. */
    private fun afterPutBack() {
        val lent = RearState.owner(this) is RearOwner.Lent
        putBackStalled = !lent && (RearTweaks.pending(this) || XiaomiLitTime.owed(this))
        updateNotification()
        stopIfIdle()
    }

    /** Xiaomi's settings are still changed, nothing is lent, and putting them back hasn't worked yet. */
    private fun puttingBack(lend: Lend?) =
        putBackStalled && lend == null && (RearTweaks.pending(this) || XiaomiLitTime.owed(this))

    /** The wallpaper is on, and its folder couldn't be opened when last listed. */
    private fun folderLost() = WallpaperSettings.isEnabled(this) && Gallery.access(this) != FolderAccess.OK

    /** The gallery found its folder can or can't be opened now, or one was chosen. */
    fun folderAccessChanged() {
        updateNotification()
    }

    /**
     * Mirror was switched on or off, or one of its options changed. Switching it off brings back
     * an app that's there; an option takes effect on it at once.
     */
    private fun mirrorChanged() {
        if (quickSwitch.lend != null) {
            if (MirrorSettings.isEnabled(this)) quickSwitch.settingsChanged() else quickSwitch.bringBack("Quick Switch was switched off")
        }
        updateCover()
        QuickSwitchTile.requestUpdate(this)
    }

    /**
     * Covering the back brings a Quick Switch app back, while that option is on, and always
     * closes Xiaomi Camera.
     */
    private fun updateCover() {
        val watch = (quickSwitch.lend != null && MirrorSettings.coverReturn(this)) || camera.lend != null
        if (watch) backCover.start() else backCover.stop()
    }

    private fun onBackCovered() {
        if (camera.lend != null) camera.close("the back was covered") else quickSwitch.bringBack("the back was covered")
    }

    /** Swipe left on the wallpaper: Xiaomi Camera, if that section is on. [why] is for the log. */
    fun openCamera(why: String) = camera.open(why)

    /**
     * The wallpaper is showing again while an app is still recorded as lent the back screen: it
     * has probably closed or gone behind, so look now rather than at the next check.
     */
    fun onHostShownWhileLent() = watchdog.lookNow()

    fun rearDisplayId() = BackScreen.findRearDisplay(this)?.displayId

    /**
     * An app was just sent to the back screen: light it up, once that won't bring up HyperOS's
     * "Press the Power button" cover. While the main screen is on and turned sideways, as for a
     * video, it waits for the main screen to turn upright (you turning the phone over) or off
     * (you pressing Power), for up to 30 s. [settleMs]: Xiaomi's app was just restarted, which
     * sends the back screen briefly to its dimmed state; wait for that first.
     */
    fun lightForLend(settleMs: Long) {
        val now = SystemClock.elapsedRealtime()
        lightForLendUntil = now + LEND_LIGHT_WINDOW_MS
        lightForLendSettled = now + settleMs
        lightForLendWaitLogged = false
        tryLightForLend()
    }

    private fun tryLightForLend() {
        if (lightForLendUntil == 0L) return
        BackScreen.mainHandler.removeCallbacks(lightForLendAgain)
        val now = SystemClock.elapsedRealtime()
        val lend = (RearState.owner(this) as? RearOwner.Lent)?.lend
        if (lend == null || now > lightForLendUntil) {
            lightForLendUntil = 0L
            return
        }
        if (now < lightForLendSettled) {
            BackScreen.mainHandler.postDelayed(lightForLendAgain, lightForLendSettled - now)
            return
        }
        if (hyperOsWouldCover()) {
            if (!lightForLendWaitLogged) BackScreen.log("Main screen is on sideways; the back screen lights once it's upright or off")
            lightForLendWaitLogged = true
            return
        }
        // Lit already, or in the moments Xiaomi dims it before suspending, when a wake key is
        // ignored: the next change looks again.
        if (isRearLit()) return
        val until = lightForLendUntil
        lightForLendUntil = 0L
        BackSensor.check(this) { covered ->
            if (!covered) return@check lightRear("Wake back screen for ${lend.packageName}")
            // Lying on its back, say: nobody's looking at it. Look again in a moment, while there's time.
            lightForLendUntil = until
            BackScreen.mainHandler.postDelayed(lightForLendAgain, COVERED_RETRY_MS)
        }
    }

    /**
     * A setting changed. The wallpaper takes it at once, but a dimmed back screen doesn't show
     * it until it's woken, so wake it. Waits a moment so a run of changes wakes it once.
     */
    fun showChanges() {
        BackScreen.mainHandler.removeCallbacks(wakeForChanges)
        BackScreen.mainHandler.postDelayed(wakeForChanges, SHOW_CHANGES_DELAY_MS)
    }

    private fun wakeRear() {
        if (RearState.snapshot(this).guardsWallpaper) lightRear("Show changes")
    }

    /**
     * Lights up the back screen, saying [why] in the log. Only here, and when the host is shown
     * (see [onHostShownOnRear]). Things that want to be seen ask [wakes] first; this is also used
     * when you asked for it, which [wakes] counts too.
     */
    private fun lightRear(why: String) {
        val rear = BackScreen.findRearDisplay(this)?.displayId ?: return
        if (hyperOsWouldCover()) return BackScreen.log("$why: not while the main screen is on sideways")
        wakes.woke(SystemClock.elapsedRealtime())
        shell(onUnavailable = {}) { BackScreen.log("$why: ${RearCommands.wakeDisplay(rear)}") }
    }

    fun isRearLit() = BackScreen.findRearDisplay(this)?.state == Display.STATE_ON

    /**
     * Whether HyperOS would cover the back screen if we lit it now. While the main screen is on
     * and turned sideways, HyperOS takes a wake key on the back screen for an accident and puts
     * its own "Press the Power button to use rear display" over it (DualScreenCoverManager,
     * HyperOS 3.0.319), so none of ours would be seen.
     */
    private fun hyperOsWouldCover(): Boolean {
        val main = mainDisplay() ?: return false
        val sideways = main.rotation == Surface.ROTATION_90 || main.rotation == Surface.ROTATION_270
        return main.state == Display.STATE_ON && sideways
    }

    private fun mainDisplay(): Display? = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    /** Keeps the CPU awake for [ms], for work that outlasts the broadcast or callback that started it. */
    private fun stayAwake(ms: Long, tag: String) {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).acquire(ms)
    }

    /** Where the charging animation goes now (see [RearSnapshot.routeOverlay]). */
    private fun chargingRoute() = RearState.snapshot(this).routeOverlay(BatterySettings.overXiaomi(this))

    /**
     * You plugged in, with Battery on ([ChargingMonitor]). The animation plays in the wallpaper,
     * or in a pop-over over Xiaomi's screen if that's allowed, and never over another app. If the
     * back screen is dark it's lit up for it, if that option is on; but not while the back is
     * covered (the phone lying on its back, say), nor just after another wake. A back screen
     * left dark would show none of it, so then nothing plays; but the Edge glow's faint glow still
     * goes up, for when the back screen is seen.
     */
    private fun onPluggedIn(status: ChargingStatus) {
        val owner = RearState.owner(this)
        if (owner is RearOwner.Lent) return BackScreen.log("Plugged in; ${owner.lend.packageName} has the back screen")
        if (chargingRoute() == OverlayRoute.DROP) return BackScreen.log("Plugged in; no charging animation while the wallpaper is off")
        // The broadcast's wake lock ends when it returns; stay awake for the sensor and the launch.
        stayAwake(SENSOR_AWAKE_MS, "BackScreen:charging")
        BackSensor.check(this) { covered ->
            val lit = isRearLit()
            val sideways = hyperOsWouldCover()
            val wake = BatterySettings.lightUp(this) && !covered && !sideways &&
                wakes.allow(SystemClock.elapsedRealtime(), lit)
            when {
                lit || wake -> return@check showCharging(status, wake)
                covered -> BackScreen.log("Plugged in; the back is covered, so no charging animation")
                sideways -> BackScreen.log("Plugged in; the main screen is on sideways, so no charging animation")
                else -> BackScreen.log("Plugged in; the back screen is dark, so no charging animation")
            }
            RearHostActivity.chargingChanged()
        }
    }

    private fun onUnplugged() {
        RearHostActivity.stopCharging()
    }

    /** Play, on the Battery tab: plays it now as a plug-in would, lighting the back screen since you asked. */
    private fun playChargingNow() {
        val status = ChargingStatus.read(this)
        if (status == null || !BatterySettings.isEnabled(this)) return stopIfIdle()
        showCharging(status, wake = !isRearLit())
    }

    /** Puts the animation where [chargingRoute] says; [wake] lights the back screen for it. */
    private fun showCharging(status: ChargingStatus, wake: Boolean) {
        when (chargingRoute()) {
            OverlayRoute.HOST -> {
                RearHostActivity.playCharging(status)
                if (wake) lightRear("Wake back screen for charging")
            }
            OverlayRoute.POPOVER -> {
                RearHostActivity.playCharging(status)
                showPopover(POPOVER_MAX_MS, wake)
            }
            OverlayRoute.DROP -> {}
        }
    }

    /**
     * A new notification that shows on the back screen, or an update to [previous]
     * ([RearNotificationListener]). It goes in a banner in the wallpaper, unless the rules in
     * [NotificationFilter] hold it back. With the wallpaper off, Xiaomi's own back screen shows
     * it; with the list pulled down, the list shows it; with another app there, it's dropped.
     *
     * If the back screen is dark it's lit up for it, if that option is on, but not while you're
     * using the phone (its own screen shows it), nor while the back is covered, and each app at
     * most once a minute ([AppWakeLimit]). A banner shows on a dimmed back screen too.
     */
    fun onNotification(n: RearNotification, previous: RearNotification?) {
        val owner = RearState.owner(this)
        if (owner is RearOwner.Lent) return BackScreen.log("Notification; ${owner.lend.packageName} has the back screen")
        if (owner != RearOwner.Host(HostMode.WALLPAPER)) return
        NotificationFilter.heldBack(n, previous)?.let { return BackScreen.log("Notification held back: ${it.why}") }
        if (mainDisplay()?.state == Display.STATE_ON) {
            if (NotificationAccess.isLocked(this)) return showNotification(n, wake = false)
            // Not from the app you're using, if it's open on the main screen.
            return shell(onUnavailable = { showNotification(n, wake = false) }) {
                val open = RearCommands.taskList()?.let { TaskList.packagesOn(it, Display.DEFAULT_DISPLAY).firstOrNull() }
                BackScreen.trace("Open on the main screen: $open")
                BackScreen.mainHandler.post {
                    val held = NotificationFilter.heldBack(n, previous, open)
                    if (held != null) BackScreen.log("Notification held back: ${held.why}") else showNotification(n, wake = false)
                }
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (isRearLit() || !NotificationSettings.lightUp(this) || !appWakes.allows(n.packageName, now)) {
            return showNotification(n, wake = false)
        }
        // The callback's over once this returns; stay awake for the sensor and the wake.
        stayAwake(SENSOR_AWAKE_MS, "BackScreen:notification")
        BackSensor.check(this) { covered ->
            val at = SystemClock.elapsedRealtime()
            val wake = !covered && wakes.allow(at, isRearLit())
            if (wake) appWakes.woke(n.packageName, at)
            if (covered) BackScreen.log("Notification; the back is covered, so it isn't lit up")
            showNotification(n, wake)
        }
    }

    /** Puts [n] in the banner, lighting the back screen for it if [wake]. */
    private fun showNotification(n: RearNotification, wake: Boolean) {
        BackScreen.trace("Banner for ${n.packageName}${if (wake) ", lighting the back screen" else ""}")
        if (!RearHostActivity.showNotification(n, wake)) return
        if (wake) lightRear("Wake back screen for a notification") else BackScreen.log("Notification banner")
    }

    /** Try it, on the Notifications tab: the sample in a banner, lighting the back screen since you asked. */
    private fun tryNotification() {
        if (!RearState.snapshot(this).guardsWallpaper) return stopIfIdle()
        val wake = !isRearLit()
        if (RearHostActivity.showNotification(SampleNotification.make(this), wake) && wake) {
            lightRear("Wake back screen for the sample notification")
        }
    }

    /** Called when the host stops being visible on the rear: check it's still there. */
    fun onHostHidden() {
        BackScreen.mainHandler.removeCallbacks(checkRear)
        BackScreen.mainHandler.postDelayed(checkRear, HIDDEN_CHECK_DELAY_MS)
    }

    /**
     * Something other than us closed the host: Xiaomi's back screen taking the rear back. Put
     * the wallpaper back once Xiaomi's screen is up, so ours lands on top of it. A pop-over just
     * ends.
     */
    fun onHostRemoved() {
        when (RearState.owner(this)) {
            is RearOwner.Host -> {
                // Not too often, in case something keeps closing it.
                val wait = max(PUT_BACK_DELAY_MS, lastApply + REAPPLY_MIN_INTERVAL_MS - SystemClock.elapsedRealtime())
                BackScreen.mainHandler.removeCallbacks(putBack)
                BackScreen.mainHandler.postDelayed(putBack, wait)
            }
            RearOwner.Popover -> endPopover("closed on the back screen")
            else -> {}
        }
    }

    private fun scheduleRearCheck() {
        BackScreen.mainHandler.removeCallbacks(checkRear)
        BackScreen.mainHandler.removeCallbacks(checkRearLate)
        BackScreen.mainHandler.postDelayed(checkRear, CHECK_DELAY_MS)
        // Backup: Xiaomi's screen can arrive after the first check.
        BackScreen.mainHandler.postDelayed(checkRearLate, LATE_CHECK_DELAY_MS)
    }

    /** Makes sure the back screen is showing our wallpaper, or will be once it's lit. */
    private fun checkRearDisplay() {
        if (!RearState.snapshot(this).guardsWallpaper || RearHostActivity.visibleOnRear) return
        // It goes up as soon as Shizuku is ready ([applyIfWaiting]). Trying meanwhile would only
        // wait for it again and give up, every few seconds for as long as it's stopped.
        if (isWaitingForShizuku) return
        val rear = BackScreen.findRearDisplay(this)
        if (RearHostActivity.isOnRear()) {
            // Hidden only because the rear is dimmed or off: still there when it lights up.
            if (rear?.state != Display.STATE_ON) return
            return checkCover(rear.displayId)
        }
        BackScreen.log("Wallpaper isn't on the back screen; putting it back")
        putBackSoon()
    }

    /**
     * Something covered the wallpaper. Xiaomi Camera can open there again by itself: its page on
     * the main screen opens it each time you come back to it, after the camera had closed when
     * you left it. Covering a running camera with the wallpaper would hide it, so it becomes the
     * camera's lend, as if opened here. Anything else is covered over.
     */
    private fun checkCover(rear: Int) {
        shell(onUnavailable = {}) {
            val front = RearCommands.taskList()?.let { TaskList.tasksOn(it, rear).firstOrNull() }
            BackScreen.mainHandler.post {
                if (!RearState.snapshot(this).guardsWallpaper || RearHostActivity.visibleOnRear) return@post
                if (front?.packageName == RearCommands.CAMERA_PACKAGE) return@post camera.adopt()
                BackScreen.log("Something covered the wallpaper; bringing it back")
                putBackSoon()
            }
        }
    }

    /** Puts the wallpaper up again, but not too often, in case something keeps closing it. */
    private fun putBackSoon() {
        val wait = lastApply + REAPPLY_MIN_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (wait > 0) {
            BackScreen.mainHandler.removeCallbacks(putBack)
            BackScreen.mainHandler.postDelayed(putBack, wait)
            return
        }
        apply()
    }

    /**
     * Runs [block] with Shizuku on the shell thread ([ShellRunner]). If Shizuku isn't ready,
     * [onUnavailable] runs instead, on the main thread; by default that switches the wallpaper
     * off. Once the service has stopped, neither runs.
     */
    fun shell(onUnavailable: () -> Unit = ::fail, block: () -> Unit) = shellRunner.run(onUnavailable, block)

    /**
     * The service's one notification. Android requires it, and shows it silently rather than
     * minimised; you can switch it off for good with Hide. While an app or Xiaomi Camera is on the
     * back screen it says which instead, with Bring back or Close camera, on a quiet channel of its
     * own that Hide doesn't touch.
     */
    private fun buildNotification(): Notification {
        if (camera.lend != null) {
            return buildLentNotification(
                R.drawable.ic_camera, getString(R.string.camera_lent_title), getString(R.string.camera_lent_text),
                getString(R.string.close_camera), ACTION_CLOSE_CAMERA, REQUEST_CLOSE_CAMERA,
            )
        }
        val lend = quickSwitch.lend ?: return buildKeeperNotification()
        return buildLentNotification(
            R.drawable.ic_swap, getString(R.string.lent_title, AppLabels.get(this, lend.packageName)), getString(R.string.lent_text),
            getString(R.string.bring_back), ACTION_BRING_BACK, REQUEST_BRING_BACK,
        )
    }

    /** What's on the back screen, with one action that ends it; tapping it does the same. */
    private fun buildLentNotification(
        icon: Int, title: String, text: String, actionLabel: String, action: String, request: Int,
    ): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_LENT_ID, getString(R.string.lent_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val intent = PendingIntent.getForegroundService(
            this, request, Intent(this, KeeperService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_LENT_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, icon), actionLabel, intent).build())
            .setOngoing(true)
            .build()
    }

    private fun buildKeeperNotification(): Notification {
        // Creating it again renames it, for anyone who had 2.0's first builds.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(KEEPER_CHANNEL_ID, getString(R.string.keeper_channel), NotificationManager.IMPORTANCE_MIN)
        )
        val folderLost = folderLost()
        val text = getString(
            when {
                isWaitingForShizuku -> R.string.keeper_waiting
                puttingBack(lend = null) -> R.string.keeper_putting_back
                folderLost -> R.string.keeper_folder_lost
                else -> R.string.keeper_text
            }
        )
        val hide = PendingIntent.getActivity(
            this, REQUEST_HIDE, hideIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, KEEPER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_image)
            .setContentTitle(getString(R.string.keeper_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_image), getString(R.string.hide), hide).build())
            .apply { if (folderLost) setContentIntent(showWallpaperIntent()) }
            .setOngoing(true)
            .build()
    }

    /** Opens the app at the Wallpaper tab, where the folder can be chosen again. */
    private fun showWallpaperIntent(): PendingIntent = PendingIntent.getActivity(
        this, REQUEST_SHOW_WALLPAPER,
        LaunchCommand.intent(this, ACTION_SHOW_WALLPAPER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Shows whether it's waiting for Shizuku or to put Xiaomi's settings back, or which app is on
     * the back screen. Posted only when the service isn't in the foreground yet, or what it says
     * has changed. Through the service, which needs no notification permission. False if Android
     * wouldn't let it into the foreground.
     */
    private fun updateNotification(): Boolean {
        if (destroyed) return false
        val lend = (RearState.owner(this) as? RearOwner.Lent)?.lend
        val says = NotificationSays(lend, isWaitingForShizuku && lend == null, puttingBack(lend), lend == null && folderLost())
        if (says == posted) return true
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            BackScreen.log("Android didn't allow the keeper into the foreground: ${e.message}")
            return false
        }
        posted = says
        return true
    }

    /**
     * What the notification says: which app is on the back screen, or whether it's waiting for
     * Shizuku or a put-back, or can't open the wallpaper's folder.
     */
    private data class NotificationSays(val lend: Lend?, val waiting: Boolean, val puttingBack: Boolean, val folderLost: Boolean)

    companion object {
        const val ACTION_APPLY = "com.backscreen.wallpaper.APPLY"
        const val ACTION_RESTORE = "com.backscreen.wallpaper.RESTORE"
        const val ACTION_TOGGLE = "com.backscreen.wallpaper.TOGGLE"
        const val ACTION_SCHEDULED_ON = "com.backscreen.wallpaper.SCHEDULED_ON"
        const val ACTION_RESUME = "com.backscreen.wallpaper.RESUME"

        /** A section was switched on or off: start, or stop if nothing needs this any more. */
        const val ACTION_UPDATE = "com.backscreen.wallpaper.UPDATE"

        /** The Battery tab's Play. */
        const val ACTION_PLAY_CHARGING = "com.backscreen.wallpaper.PLAY_CHARGING"

        /** The Notifications tab's Try on back screen. */
        const val ACTION_TRY_NOTIFICATION = "com.backscreen.wallpaper.TRY_NOTIFICATION"

        /** The Quick Switch tile, when the service wasn't running to ask directly. */
        const val ACTION_QUICK_SWITCH = "com.backscreen.wallpaper.QUICK_SWITCH"

        /** Bring back, in the notification while an app is on the back screen. */
        const val ACTION_BRING_BACK = "com.backscreen.wallpaper.BRING_BACK"

        /** A Mirror option changed. */
        const val ACTION_MIRROR_CHANGED = "com.backscreen.wallpaper.MIRROR_CHANGED"

        /** Open camera on back screen, on the Camera tab. */
        const val ACTION_OPEN_CAMERA = "com.backscreen.wallpaper.OPEN_CAMERA"

        /** Close camera, in the notification or on the Camera tab. */
        const val ACTION_CLOSE_CAMERA = "com.backscreen.wallpaper.CLOSE_CAMERA"

        /** With the camera's actions: where it was asked from, for the log. */
        const val EXTRA_WHY = "why"

        /** Opens the app at the Wallpaper tab: the notification, when the folder needs choosing again. */
        const val ACTION_SHOW_WALLPAPER = "com.backscreen.wallpaper.SHOW_WALLPAPER"

        private const val ACTION_SUB_SCREEN_ON = "miui.intent.action.SUB_SCREEN_ON"

        /** The "Mi Back Screen is on" notification's channel, which Hide opens. */
        const val KEEPER_CHANNEL_ID = "keeper"
        private const val CHANNEL_LENT_ID = "lent"

        // 1.x's keeper channel, unused since 2.0.
        private const val OLD_CHANNEL_ID = "backscreen"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_BRING_BACK = 1
        private const val REQUEST_CLOSE_CAMERA = 2
        private const val REQUEST_HIDE = 3
        private const val REQUEST_SHOW_WALLPAPER = 4

        // How long a sent app's light waits for the main screen to turn upright or off.
        private const val LEND_LIGHT_WINDOW_MS = 30_000L
        private const val COVERED_RETRY_MS = 1000L
        private const val CHECK_DELAY_MS = 600L
        private const val LATE_CHECK_DELAY_MS = 1500L
        private const val HIDDEN_CHECK_DELAY_MS = 300L
        private const val PUT_BACK_DELAY_MS = 700L
        private const val REAPPLY_MIN_INTERVAL_MS = 5000L
        private const val DIRECT_LAUNCH_TIMEOUT_MS = 1500L
        private const val DIRECT_LAUNCH_BLOCKED_MS = 30 * 60_000L
        private const val WAKE_WHEN_SHOWN_MS = 10_000L
        private const val SUB_SCREEN_ON_GAP_MS = 1000L
        private const val SHOW_CHANGES_DELAY_MS = 400L
        private const val SENSOR_AWAKE_MS = 2000L

        // A pop-over closes when its animation ends; this is in case it never starts.
        private const val POPOVER_MAX_MS = 9000L

        @Volatile
        var instance: KeeperService? = null
            private set

        /** [why] goes with the camera's actions, for the log. */
        fun start(context: Context, action: String, why: String? = null) {
            val intent = Intent(context, KeeperService::class.java).setAction(action)
            why?.let { intent.putExtra(EXTRA_WHY, it) }
            context.startForegroundService(intent)
        }

        /**
         * The Wallpaper switch, from the app. Saved here as well as by the service, so the switch
         * doesn't flick back while it starts.
         */
        fun setWallpaper(context: Context, on: Boolean) {
            WallpaperSettings.setEnabled(context, on)
            start(context, if (on) ACTION_APPLY else ACTION_RESTORE)
        }

        /**
         * Android's page for just the "Mi Back Screen is on" notification, where you can switch it
         * off for good: the notification's Hide, and Setup help. The app can't do it itself.
         */
        fun hideIntent(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, KEEPER_CHANNEL_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Whether the "Mi Back Screen is on" notification shows: not if you switched it, or all of the app's, off. */
        fun isNotificationShown(context: Context): Boolean {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = nm.getNotificationChannel(KEEPER_CHANNEL_ID)
            return nm.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
