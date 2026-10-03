package com.backscreen.wallpaper.core

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
 * in a pop-over if the wallpaper is off and that's allowed (see [onPluggedIn]). Everything that
 * lights the back screen goes through [lightRear], and [WakeThrottle] decides for the things
 * that want to be seen, so they can't light it in a burst.
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

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        runWaiting()
        BackScreen.mainHandler.post { applyIfWaiting() }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) BackScreen.mainHandler.post { applyIfWaiting() }
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
            val rear = BackScreen.findRearDisplay(this@KeeperService) ?: return
            if (displayId != rear.displayId) return
            val woke = rear.state == Display.STATE_ON && rearState != Display.STATE_ON
            rearState = rear.state
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        when (intent?.action) {
            ACTION_RESTORE -> restore()
            ACTION_TOGGLE -> if (WallpaperSettings.isEnabled(this)) restore() else turnOn()
            ACTION_APPLY -> turnOn()
            ACTION_SCHEDULED_ON -> turnOn(byYou = false)
            ACTION_UPDATE -> stopIfIdle()
            ACTION_PLAY_CHARGING -> playChargingNow()
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
        return true
    }

    /** The lent app left the back screen: it goes to the wallpaper or Xiaomi's screen, whichever is on now. */
    fun onLendEnded() {
        if (!RearState.endLend(this)) return
        BackScreen.log("Back screen returned")
        ClockAlarm.update(this)
        if (RearState.snapshot(this).guardsWallpaper) {
            // Usually still underneath, and showing again; put back if not.
            scheduleRearCheck()
        } else {
            restoreXiaomi()
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

    private fun isRearLit() = BackScreen.findRearDisplay(this)?.state == Display.STATE_ON

    /**
     * Whether HyperOS would cover the back screen if we lit it now. While the main screen is on
     * and turned sideways, HyperOS takes a wake key on the back screen for an accident and puts
     * its own "Press the Power button to use rear display" over it (DualScreenCoverManager,
     * HyperOS 3.0.319), so none of ours would be seen.
     */
    private fun hyperOsWouldCover(): Boolean {
        val main = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val sideways = main.rotation == Surface.ROTATION_90 || main.rotation == Surface.ROTATION_270
        return main.state == Display.STATE_ON && sideways
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
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BackScreen:charging")
            .acquire(PLUG_IN_AWAKE_MS)
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
            BackScreen.log("Something covered the wallpaper; bringing it back")
        } else {
            BackScreen.log("Wallpaper isn't on the back screen; putting it back")
        }
        val wait = lastApply + REAPPLY_MIN_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (wait > 0) {
            BackScreen.mainHandler.removeCallbacks(putBack)
            BackScreen.mainHandler.postDelayed(putBack, wait)
            return
        }
        apply()
    }

    /** Runs [block] with Shizuku, waiting briefly for its connection if needed. */
    private fun shell(onUnavailable: () -> Unit = ::fail, block: () -> Unit) {
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

    private fun buildNotification(): Notification {
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

    /** Shows whether it's waiting for Shizuku. Through the service, which needs no notification permission. */
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

        private const val ACTION_SUB_SCREEN_ON = "miui.intent.action.SUB_SCREEN_ON"
        private const val CHANNEL_ID = "keeper"
        private const val NOTIFICATION_ID = 1
        private const val CHECK_DELAY_MS = 600L
        private const val LATE_CHECK_DELAY_MS = 1500L
        private const val HIDDEN_CHECK_DELAY_MS = 300L
        private const val PUT_BACK_DELAY_MS = 700L
        private const val REAPPLY_MIN_INTERVAL_MS = 5000L
        private const val SHIZUKU_WAIT_MS = 4000L
        private const val DIRECT_LAUNCH_TIMEOUT_MS = 1500L
        private const val SHOW_CHANGES_DELAY_MS = 400L
        private const val PLUG_IN_AWAKE_MS = 2000L

        // A pop-over closes when its animation ends; this is in case it never starts.
        private const val POPOVER_MAX_MS = 9000L

        @Volatile
        var instance: KeeperService? = null
            private set

        fun start(context: Context, action: String) {
            context.startForegroundService(Intent(context, KeeperService::class.java).setAction(action))
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
