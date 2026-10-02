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
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import com.backscreen.wallpaper.R
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

    private val binderReceived = Shizuku.OnBinderReceivedListener { runWaiting() }
    private val shizukuTimeout = Runnable { runWaiting() }
    private val directLaunchTimeout = Runnable { onDirectLaunchFailed() }
    private val checkRear = Runnable { checkRearDisplay() }
    private val checkRearLate = Runnable { checkRearDisplay() }
    private val putBack = Runnable { checkRearDisplay() }
    private val wakeForChanges = Runnable { wakeRear() }
    private val popoverDone = Runnable { endPopover("done") }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val rear = BackScreen.findRearDisplay(this@KeeperService) ?: return
            if (displayId != rear.displayId || rear.state != Display.STATE_ON) return
            // The gallery doesn't move on while the phone sleeps; catch up now it can be seen.
            RearHostActivity.refreshImage()
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
        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, BackScreen.mainHandler)
        registerReceiver(subScreenOn, IntentFilter(ACTION_SUB_SCREEN_ON), RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        when (intent?.action) {
            ACTION_RESTORE -> restore()
            ACTION_TOGGLE -> if (WallpaperSettings.isEnabled(this)) restore() else turnOn()
            ACTION_APPLY -> turnOn()
            ACTION_SCHEDULED_ON -> turnOn(wake = false)
            // ACTION_RESUME after an app update, or none: the system restarted us after the
            // process was killed.
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
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        unregisterReceiver(subScreenOn)
        executor.shutdown()
        super.onDestroy()
    }

    /** Stops the service once no section needs it. */
    private fun stopIfIdle() {
        if (!RearState.keeperNeeded(this)) stopSelf()
    }

    /** Works with no images too: the back screen is black, with the clock if that's on. */
    private fun turnOn(wake: Boolean = true) {
        WallpaperSettings.setEnabled(this, true)
        RearState.wallpaperChanged(this, true)
        BackScreen.mainHandler.removeCallbacks(popoverDone)
        // You just asked for it, so light the back screen up to show it (a schedule doesn't).
        wakeWhenShown = wake
        val owner = RearState.owner(this)
        if (owner is RearOwner.Lent) {
            BackScreen.log("Wallpaper on; it shows when ${owner.lend.packageName} leaves the back screen")
            return
        }
        apply()
    }

    /** Puts the wallpaper up afresh. */
    private fun apply() {
        lastApply = SystemClock.elapsedRealtime()
        BackScreen.mainHandler.removeCallbacks(putBack)
        launchHost("Applying wallpaper...")
    }

    /** Launches the host, replacing it if it's up. It shows whatever [RearState] says. */
    private fun launchHost(what: String) {
        RearHostActivity.finishCurrent()
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        BackScreen.log(what)
        val rear = BackScreen.findRearDisplay(this)?.displayId
        // HyperOS only honours our rear display opt-in while the phone is unlocked.
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val popover = RearState.owner(this) == RearOwner.Popover
        val unavailable: () -> Unit = if (popover) ({ endPopover("Shizuku isn't ready") }) else ::fail
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
        val wake = wakeWhenShown
        awaitingDirectLaunch = false
        wakeWhenShown = false
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        // Only for the wallpaper, which stays: a pop-over is gone in seconds.
        val guard = RearState.snapshot(this).guardsWallpaper
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
        if (!RearState.snapshot(this).guardsWallpaper) return
        val rear = BackScreen.findRearDisplay(this)?.displayId ?: return
        shell(onUnavailable = {}) { BackScreen.log("Show changes: ${RearCommands.wakeDisplay(rear)}") }
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
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_APPLY = "com.backscreen.wallpaper.APPLY"
        const val ACTION_RESTORE = "com.backscreen.wallpaper.RESTORE"
        const val ACTION_TOGGLE = "com.backscreen.wallpaper.TOGGLE"
        const val ACTION_SCHEDULED_ON = "com.backscreen.wallpaper.SCHEDULED_ON"
        const val ACTION_RESUME = "com.backscreen.wallpaper.RESUME"

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
