package com.backscreen.wallpaper

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
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Puts the wallpaper on the rear display and keeps it there.
 *
 * Applying is:
 *   1. launch RearWallpaperActivity straight onto the rear display (allowed while unlocked
 *      because the manifest carries Xiaomi's `miui.rear.policy` opt-in),
 *   2. stop Xiaomi's rear launcher so it doesn't cover the wallpaper again.
 * When locked, or if HyperOS refuses step 1, launch on the main display instead and move the
 * task to the rear, which HyperOS doesn't block.
 * Each step needs shell access, which Shizuku provides (see RearCommands).
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
    private val wakeForChanges = Runnable { wakeRear() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val rear = BackScreen.findRearDisplay(this@KeeperService) ?: return
            if (displayId != rear.displayId || rear.state != Display.STATE_ON) return
            // The gallery doesn't move on while the phone sleeps; catch up now it can be seen.
            RearWallpaperActivity.refreshImage()
            scheduleRearCheck()
        }
    }

    // HyperOS's own "back screen turned on" broadcast. Can arrive when the display listener
    // doesn't fire (the doze layer reports the display as off); anyone can send it, but all
    // it does is trigger a check.
    private val subScreenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = scheduleRearCheck()
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
            ACTION_TOGGLE -> if (BackScreen.isEnabled(this)) restore() else turnOn()
            ACTION_APPLY -> turnOn()
            ACTION_SCHEDULED_ON -> turnOn(wake = false)
            // The system restarted us after the process was killed.
            else -> if (BackScreen.isEnabled(this)) apply() else stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        BackScreen.mainHandler.removeCallbacks(checkRear)
        BackScreen.mainHandler.removeCallbacks(checkRearLate)
        BackScreen.mainHandler.removeCallbacks(wakeForChanges)
        BackScreen.mainHandler.removeCallbacks(shizukuTimeout)
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        Shizuku.removeBinderReceivedListener(binderReceived)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        unregisterReceiver(subScreenOn)
        executor.shutdown()
        super.onDestroy()
    }

    private fun turnOn(wake: Boolean = true) {
        if (!Gallery.hasImages(this)) {
            BackScreen.log("Choose an image first")
            fail()
            return
        }
        BackScreen.setEnabled(this, true)
        // You just asked for it, so light the back screen up to show it (a schedule doesn't).
        wakeWhenShown = wake
        apply()
    }

    private fun apply() {
        lastApply = SystemClock.elapsedRealtime()
        RearWallpaperActivity.finishCurrent()
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        BackScreen.log("Applying wallpaper...")
        val rear = BackScreen.findRearDisplay(this)?.displayId
        // HyperOS only honours our rear display opt-in while the phone is unlocked.
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (rear == null || locked || directLaunchBlocked) {
            awaitingDirectLaunch = false
            shell { BackScreen.log("Open wallpaper: ${RearCommands.launchWallpaperActivity(Display.DEFAULT_DISPLAY)}") }
            return
        }
        awaitingDirectLaunch = true
        shell {
            val result = RearCommands.launchWallpaperActivity(rear)
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
        apply()
    }

    /** Called by RearWallpaperActivity when it has been created on the main display. */
    fun onWallpaperCreatedOnMain(taskId: Int) {
        // HyperOS may put a direct launch on the main display instead; moving it fixes that.
        awaitingDirectLaunch = false
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        val rear = BackScreen.findRearDisplay(this)?.displayId
            ?: return BackScreen.log("Back screen not found")
        shell {
            BackScreen.log("Move to back screen: ${RearCommands.moveTaskToDisplay(taskId, rear)}")
            BackScreen.log("Stop Xiaomi back screen app: ${RearCommands.stopXiaomiRearLauncher()}")
        }
    }

    /** Called by RearWallpaperActivity when it is showing on the rear display. */
    fun onWallpaperCreatedOnRear(displayId: Int) {
        val direct = awaitingDirectLaunch
        val wake = wakeWhenShown
        awaitingDirectLaunch = false
        wakeWhenShown = false
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        if (!direct && !wake) return
        shell {
            // After a move, onWallpaperCreatedOnMain has already stopped it.
            if (direct) BackScreen.log("Stop Xiaomi back screen app: ${RearCommands.stopXiaomiRearLauncher()}")
            if (wake) BackScreen.log("Wake back screen: ${RearCommands.wakeDisplay(displayId)}")
        }
    }

    private fun restore() {
        BackScreen.setEnabled(this, false)
        awaitingDirectLaunch = false
        wakeWhenShown = false
        BackScreen.mainHandler.removeCallbacks(directLaunchTimeout)
        RearWallpaperActivity.finishCurrent()
        val rear = BackScreen.findRearDisplay(this)?.displayId ?: 1
        shell(onUnavailable = { stopSelf() }) {
            BackScreen.log("Restore Xiaomi back screen: ${RearCommands.restoreXiaomiRearLauncher(rear)}")
            BackScreen.mainHandler.post { stopSelf() }
        }
    }

    /** Couldn't turn on: show "off" everywhere and stop. */
    private fun fail() {
        BackScreen.setEnabled(this, false)
        RearWallpaperActivity.finishCurrent()
        stopSelf()
    }

    /**
     * A setting changed. The wallpaper takes it at once, but a back screen that has gone to
     * sleep (or is covered by Xiaomi's always-on layer) doesn't draw it until it's woken, so
     * wake it. Waits a moment so a run of changes wakes it once.
     */
    fun showChanges() {
        BackScreen.mainHandler.removeCallbacks(wakeForChanges)
        BackScreen.mainHandler.postDelayed(wakeForChanges, SHOW_CHANGES_DELAY_MS)
    }

    private fun wakeRear() {
        if (!BackScreen.isEnabled(this)) return
        val rear = BackScreen.findRearDisplay(this)?.displayId ?: return
        // Waking fires the display listener, which also clears Xiaomi's layer if it's on top.
        shell(onUnavailable = {}) { BackScreen.log("Show changes: ${RearCommands.wakeDisplay(rear)}") }
    }

    /**
     * Called when the wallpaper stops being visible on the rear. Xiaomi's doze layer starts a
     * moment after the rear display wakes and hides us; this catches it whenever it lands.
     */
    fun onWallpaperHidden() {
        BackScreen.mainHandler.removeCallbacks(checkRear)
        BackScreen.mainHandler.postDelayed(checkRear, HIDDEN_CHECK_DELAY_MS)
    }

    private fun scheduleRearCheck() {
        BackScreen.mainHandler.removeCallbacks(checkRear)
        BackScreen.mainHandler.removeCallbacks(checkRearLate)
        BackScreen.mainHandler.postDelayed(checkRear, CHECK_DELAY_MS)
        // Backup: the doze layer can arrive after the first check.
        BackScreen.mainHandler.postDelayed(checkRearLate, LATE_CHECK_DELAY_MS)
    }

    /** The rear display just turned on: make sure it's showing our wallpaper. */
    private fun checkRearDisplay() {
        // No display-state check here: Xiaomi's doze layer reports the back screen as off
        // while it covers us, which is exactly when we need to act.
        if (!BackScreen.isEnabled(this) || RearWallpaperActivity.visibleOnRear) return
        if (RearWallpaperActivity.isOnRear()) {
            // Usually Xiaomi's always-on "doze" layer drawn over us; stopping the app ends it.
            BackScreen.log("Xiaomi app covered the wallpaper; stopping it")
            shell { RearCommands.stopXiaomiRearLauncher() }
        } else if (SystemClock.elapsedRealtime() - lastApply > REAPPLY_MIN_INTERVAL_MS) {
            BackScreen.log("Back screen is on but wallpaper isn't showing; re-applying")
            apply()
        }
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
            NotificationChannel(CHANNEL_ID, "Back screen wallpaper", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_image)
            .setContentTitle("Back screen wallpaper active")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_APPLY = "com.backscreen.wallpaper.APPLY"
        const val ACTION_RESTORE = "com.backscreen.wallpaper.RESTORE"
        const val ACTION_TOGGLE = "com.backscreen.wallpaper.TOGGLE"
        const val ACTION_SCHEDULED_ON = "com.backscreen.wallpaper.SCHEDULED_ON"

        private const val ACTION_SUB_SCREEN_ON = "miui.intent.action.SUB_SCREEN_ON"
        private const val CHANNEL_ID = "keeper"
        private const val NOTIFICATION_ID = 1
        private const val CHECK_DELAY_MS = 600L
        private const val LATE_CHECK_DELAY_MS = 1500L
        private const val HIDDEN_CHECK_DELAY_MS = 300L
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
    }
}
