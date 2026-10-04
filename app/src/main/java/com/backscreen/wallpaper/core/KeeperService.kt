package com.backscreen.wallpaper.core

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
import android.view.Display
import android.view.Surface
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
import com.backscreen.wallpaper.wallpaper.WallpaperSettings
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
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
 * ([RearTweaks]) is put back when it ends, or when this starts with nothing lent.
 *
 * Xiaomi Camera ([XiaomiCamera]): a swipe left on the wallpaper, or Open camera, opens it on the
 * back screen as another lend, watched the same way. It closes when the back screen dims or goes
 * off, when the back is covered, and from Close camera in its notification.
 *
 * Restarts: after the phone or this app restarts, it carries on. If Shizuku isn't running yet
 * (after a phone restart it isn't until you start it), the wallpaper waits for it rather than
 * switching off (see [waitForShizuku]).
 */
class KeeperService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private var lastApply = 0L

    // Set if a direct launch fails while unlocked; from then on use the main display route.
    private var directLaunchBlocked = false
    private var awaitingDirectLaunch = false
    private var wakeWhenShown = false

    // Work waiting for Shizuku's connection, which arrives shortly after our process starts.
    private val waitingForShizuku = mutableListOf<() -> Unit>()

    /** The wallpaper is on but couldn't go up, because Shizuku isn't ready (see [waitForShizuku]). */
    var isWaitingForShizuku = false
        private set

    private val charging = ChargingMonitor(this, ::onPluggedIn, ::onUnplugged)
    private val wakes = WakeThrottle()
    private val appWakes = AppWakeLimit()

    /** Moving the app you're using to the back screen and back. */
    val quickSwitch = QuickSwitch(this)

    /** Xiaomi Camera on the back screen. */
    val camera = XiaomiCamera(this)
    private val watchdog = LendWatchdog(::shell, ::rearDisplayId, ::onLentAppGone)
    // Lazy: it finds the sensor, which needs the service's context.
    private val backCover by lazy { BackCover(this, ::onBackCovered) }

    // After an app is sent, the back screen is lit for it once that won't bring up HyperOS's
    // cover: until then (elapsedRealtime), and not before Xiaomi's app has settled.
    private var lightForLendUntil = 0L
    private var lightForLendSettled = 0L
    private var lightForLendWaitLogged = false
    private val lightForLendAgain = Runnable { tryLightForLend() }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        runWaiting()
        BackScreen.mainHandler.post {
            applyIfWaiting()
            putBackTweaksIfIdle()
        }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) BackScreen.mainHandler.post {
            applyIfWaiting()
            putBackTweaksIfIdle()
        }
    }
    private val shizukuTimeout = Runnable { runWaiting() }
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
            if (displayId == Display.DEFAULT_DISPLAY) return tryLightForLend()
            val rear = BackScreen.findRearDisplay(this@KeeperService) ?: return
            if (displayId != rear.displayId) return
            val woke = rear.state == Display.STATE_ON && rearState != Display.STATE_ON
            val changed = rear.state != rearState
            rearState = rear.state
            // Xiaomi Camera closes when the back screen dims or goes off.
            if (changed) camera.rearChanged(rear.state == Display.STATE_ON)
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
    // doesn't fire (the doze layer reports the display as off); anyone can send it, but all
    // it does is trigger a check.
    private val subScreenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            RearHostActivity.refreshImage()
            scheduleRearCheck()
        }
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
        instance = null
        BackScreen.mainHandler.removeCallbacks(checkRear)
        BackScreen.mainHandler.removeCallbacks(checkRearLate)
        BackScreen.mainHandler.removeCallbacks(putBack)
        BackScreen.mainHandler.removeCallbacks(wakeForChanges)
        BackScreen.mainHandler.removeCallbacks(shizukuTimeout)
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        BackScreen.mainHandler.removeCallbacks(popoverDone)
        BackScreen.mainHandler.removeCallbacks(lightForLendAgain)
        watchdog.stop()
        backCover.stop()
        camera.ended()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        unregisterReceiver(subScreenOn)
        charging.stop()
        executor.shutdown()
        super.onDestroy()
    }

    /** Stops the service once no section needs it. */
    private fun stopIfIdle() {
        if (!RearState.keeperNeeded(this)) stopSelf()
    }

    /**
     * Works with no images too: the back screen is black, with the clock if that's on. [byYou]:
     * from the app, tile or widget, where the switch going back off says Shizuku isn't ready.
     * A schedule's turn-on waits for Shizuku instead.
     */
    private fun turnOn(byYou: Boolean = true) {
        WallpaperSettings.setEnabled(this, true)
        RearState.wallpaperChanged(this, true)
        BackScreen.mainHandler.removeCallbacks(popoverDone)
        // You just asked for it, so light the back screen up to show it (a schedule doesn't).
        wakeWhenShown = byYou
        val owner = RearState.owner(this)
        if (owner is RearOwner.Lent) {
            BackScreen.log("Wallpaper on; it shows when ${owner.lend.packageName} leaves the back screen")
            return
        }
        apply(orWait = !byYou)
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
        isWaitingForShizuku = false
        updateNotification()
        BackScreen.log("Shizuku is ready")
        if (RearState.snapshot(this).guardsWallpaper) apply()
    }

    /** Launches the host, replacing it if it's up. It shows whatever [RearState] says. */
    private fun launchHost(what: String, onUnavailable: () -> Unit = ::fail) {
        RearHostActivity.finishCurrent()
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        BackScreen.log(what)
        val rear = BackScreen.findRearDisplay(this)?.displayId
        // HyperOS only honours our rear display opt-in while the phone is unlocked.
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val popover = RearState.owner(this) == RearOwner.Popover
        val unavailable: () -> Unit = if (popover) ({ endPopover("Shizuku isn't ready") }) else onUnavailable
        if (rear == null || locked || directLaunchBlocked) {
            awaitingDirectLaunch = false
            shell(unavailable) {
                BackScreen.log("Open on main screen, to move: ${RearCommands.launchHost(Display.DEFAULT_DISPLAY)}")
            }
            return
        }
        awaitingDirectLaunch = true
        shell(unavailable) {
            val result = RearCommands.launchHost(rear)
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
        directLaunchBlocked = true
        BackScreen.log("Back screen refused a direct launch; going via the main screen")
        launchHost("Opening via the main screen...")
    }

    /** Called by RearHostActivity when it has been created on the main display. */
    fun onHostCreatedOnMain(taskId: Int) {
        // HyperOS may put a direct launch on the main display instead; moving it fixes that.
        awaitingDirectLaunch = false
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        val rear = BackScreen.findRearDisplay(this)?.displayId
            ?: return BackScreen.log("Back screen not found")
        shell { BackScreen.log("Move to back screen: ${RearCommands.moveTaskToDisplay(taskId, rear)}") }
    }

    /** Called by RearHostActivity when it is showing on the rear display. */
    fun onHostShownOnRear(displayId: Int) {
        val wake = wakeWhenShown && !hyperOsWouldCover()
        awaitingDirectLaunch = false
        wakeWhenShown = false
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        // Only for the wallpaper, which stays: a pop-over is gone in seconds.
        val guard = RearState.snapshot(this).guardsWallpaper
        if (wake) wakes.woke(SystemClock.elapsedRealtime())
        shell(onUnavailable = {}) {
            if (wake) BackScreen.log("Wake back screen: ${RearCommands.wakeDisplay(displayId)}")
            if (guard) removeXiaomiLauncher(displayId)
        }
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
        if (isWaitingForShizuku) {
            isWaitingForShizuku = false
            updateNotification()
        }
        awaitingDirectLaunch = false
        wakeWhenShown = false
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
        val rear = BackScreen.findRearDisplay(this)?.displayId ?: 1
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
        wakeWhenShown = wake
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
        wakeWhenShown = false
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
     * The lent app left the back screen: it goes to the wallpaper or Xiaomi's screen, whichever
     * is on now, and whatever the lend changed in Xiaomi's settings is put back.
     */
    fun onLendEnded() {
        if (!RearState.endLend(this)) return
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
        onLendEnded()
    }

    /** An app came or went: the notification and the tile say so. */
    private fun lentChanged() {
        updateNotification()
        QuickSwitchTile.requestUpdate(this)
    }

    /** Puts back what an earlier lend changed in Xiaomi's settings, once nothing is lent. */
    private fun putBackTweaksIfIdle() {
        if (RearState.owner(this) is RearOwner.Lent || !RearTweaks.pending(this)) return
        val rear = rearDisplayId() ?: return
        // If Shizuku isn't ready, it's tried again when it connects.
        shell(onUnavailable = {}) {
            if (RearState.owner(this) is RearOwner.Lent) return@shell
            RearTweaks.putBackAll(this, rear)
            BackScreen.mainHandler.post { stopIfIdle() }
        }
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
     * left dark would show none of it, so then nothing plays.
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
                lit || wake -> showCharging(status, wake)
                covered -> BackScreen.log("Plugged in; the back is covered, so no charging animation")
                sideways -> BackScreen.log("Plugged in; the main screen is on sideways, so no charging animation")
                else -> BackScreen.log("Plugged in; the back screen is dark, so no charging animation")
            }
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
     * you left it (Phase 7). Covering a running camera with the wallpaper would hide it, so it
     * becomes the camera's lend, as if opened here. Anything else is covered over.
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
     * Runs [block] with Shizuku on the shell thread, waiting briefly for its connection if needed.
     * If Shizuku isn't ready, [onUnavailable] runs instead, on the main thread; by default that
     * switches the wallpaper off.
     */
    fun shell(onUnavailable: () -> Unit = ::fail, block: () -> Unit) {
        val task = {
            if (RearCommands.isReady()) {
                executor.execute(block)
            } else {
                BackScreen.log("Shizuku isn't running or this app isn't allowed")
                onUnavailable()
            }
        }
        if (Shizuku.pingBinder()) {
            task()
        } else {
            waitingForShizuku += task
            BackScreen.mainHandler.removeCallbacks(shizukuTimeout)
            BackScreen.mainHandler.postDelayed(shizukuTimeout, SHIZUKU_WAIT_MS)
        }
    }

    private fun runWaiting() {
        BackScreen.mainHandler.post {
            BackScreen.mainHandler.removeCallbacks(shizukuTimeout)
            val tasks = waitingForShizuku.toList()
            waitingForShizuku.clear()
            tasks.forEach { it() }
        }
    }

    /**
     * The service's one notification. Minimised, unless an app or Xiaomi Camera is on the back
     * screen: then it says which, with Bring back or Close camera, on a quiet channel of its own
     * that isn't tucked away.
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

    /** What's on the back screen, with one action that ends it; tapping it does the same (Phase 6). */
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.keeper_channel), NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_image)
            .setContentTitle(getString(R.string.keeper_title))
            .setContentText(if (isWaitingForShizuku) getString(R.string.keeper_waiting) else null)
            .setOngoing(true)
            .build()
    }

    /**
     * Shows whether it's waiting for Shizuku, or which app is on the back screen. Through the
     * service, which needs no notification permission.
     */
    private fun updateNotification() {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

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

        private const val ACTION_SUB_SCREEN_ON = "miui.intent.action.SUB_SCREEN_ON"
        private const val CHANNEL_ID = "keeper"
        private const val CHANNEL_LENT_ID = "lent"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_BRING_BACK = 1
        private const val REQUEST_CLOSE_CAMERA = 2

        // How long a sent app's light waits for the main screen to turn upright or off.
        private const val LEND_LIGHT_WINDOW_MS = 30_000L
        private const val COVERED_RETRY_MS = 1000L
        private const val CHECK_DELAY_MS = 600L
        private const val LATE_CHECK_DELAY_MS = 1500L
        private const val HIDDEN_CHECK_DELAY_MS = 300L
        private const val PUT_BACK_DELAY_MS = 700L
        private const val REAPPLY_MIN_INTERVAL_MS = 5000L
        private const val SHIZUKU_WAIT_MS = 4000L
        private const val DIRECT_LAUNCH_TIMEOUT_MS = 1500L
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
    }
}
