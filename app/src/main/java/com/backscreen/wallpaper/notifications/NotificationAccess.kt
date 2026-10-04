package com.backscreen.wallpaper.notifications

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.RearCommands
import java.util.concurrent.Executors

/**
 * Notification access, which [RearNotificationListener] needs, and what your lock screen shows.
 *
 * Since Android 13, Settings won't let you allow it for a sideloaded app until you've allowed
 * "restricted settings" for that app (App info → ⋮). Shizuku can allow it in one tap instead.
 */
object NotificationAccess {
    // Lock screen settings that any app may read; Xiaomi's back screen follows them too.
    private const val SHOW_NOTIFICATIONS = "lock_screen_show_notifications"
    private const val SHOW_SENSITIVE = "lock_screen_allow_private_notifications"

    private val shell = Executors.newSingleThreadExecutor()

    fun component(context: Context) = ComponentName(context, RearNotificationListener::class.java)

    fun isGranted(context: Context) =
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))

    /** Allows it through Shizuku; [done] says, on the main thread, whether it's allowed now. */
    fun grant(context: Context, done: (Boolean) -> Unit) = run(context, "Allow notification access", done) {
        RearCommands.allowNotificationListener(it)
    }

    /** Takes it away through Shizuku; [done] says, on the main thread, whether it's still allowed. */
    fun remove(context: Context, done: (Boolean) -> Unit) = run(context, "Remove notification access", done) {
        RearCommands.disallowNotificationListener(it)
    }

    private fun run(context: Context, what: String, done: (Boolean) -> Unit, command: (String) -> String) {
        val app = context.applicationContext
        val name = component(app).flattenToString()
        shell.execute {
            BackScreen.log("$what: ${command(name)}")
            BackScreen.mainHandler.post { done(isGranted(app)) }
        }
    }

    /** Notification access in Settings: straight to this app's page where the phone allows it. */
    fun openSettings(context: Context) {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
        try {
            context.startActivity(detail)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    /** What your lock screen shows. If it can't be read, sensitive content counts as hidden. */
    fun lockScreen(context: Context): LockScreen {
        val resolver = context.contentResolver
        return try {
            LockScreen(
                showNotifications = Settings.Secure.getInt(resolver, SHOW_NOTIFICATIONS, 1) != 0,
                showSensitive = Settings.Secure.getInt(resolver, SHOW_SENSITIVE, 0) != 0,
            )
        } catch (e: SecurityException) {
            LockScreen(showNotifications = true, showSensitive = false)
        }
    }

    /**
     * Whether the phone is locked with a PIN, pattern, password or biometrics, the lock screens
     * that hide sensitive content. A lock screen you just swipe away hides nothing.
     */
    fun isLocked(context: Context) = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
}
