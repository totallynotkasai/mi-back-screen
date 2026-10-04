package com.backscreen.wallpaper.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.MainThread
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.rear.RearHostActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Hears notifications arrive and go, once you've allowed Notification access, and passes them to
 * the back screen: a new one to KeeperService, which puts a banner up in the wallpaper, and the
 * ones in the phone's shade to the list you pull down there ([recent]). They're kept in memory
 * only while they're in the shade, as plain text, and never written or sent anywhere.
 *
 * Android binds it whenever access is allowed. While the section is off it asks to be let go, so
 * the app isn't woken for every notification; switching the section on binds it again
 * ([switched]). Its manifest entry asks only for notifications that alert or are conversations,
 * so silent and ongoing ones don't even reach it.
 *
 * Callbacks arrive on the main thread, which the back screen shares; reading a notification and
 * its app's name and icon is done on a thread of its own, in order.
 */
class RearNotificationListener : NotificationListenerService() {

    private val reader = Executors.newSingleThreadExecutor()

    override fun onListenerConnected() {
        instance = this
        if (!NotificationSettings.isEnabled(this)) {
            BackScreen.log("Notifications are off; letting go of notification access")
            requestUnbind()
            return
        }
        val shade = try {
            activeNotifications
        } catch (e: SecurityException) {
            null
        } ?: return
        val ranking = currentRanking
        reader.execute {
            val read = shade.map { read(it, ranking) }
            BackScreen.mainHandler.post { loaded(read) }
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        active.clear()
        RearHostActivity.notificationsChanged()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        reader.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!NotificationSettings.isEnabled(this)) return
        val late = System.currentTimeMillis() - sbn.postTime
        reader.execute {
            val n = read(sbn, rankingMap)
            BackScreen.mainHandler.post { posted(n, late) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // Through the reader too, so it can't overtake the notification arriving.
        reader.execute { BackScreen.mainHandler.post { removed(sbn.key) } }
    }

    private fun loaded(read: List<RearNotification>) {
        if (instance !== this) return
        active.clear()
        for (n in read) if (NotificationFilter.shows(n, packageName)) active[n.key] = n
        BackScreen.log("Reading notifications: ${active.size} in the shade for the back screen")
        RearHostActivity.notificationsChanged()
    }

    private fun posted(n: RearNotification, lateMs: Long) {
        if (instance !== this || !NotificationSettings.isEnabled(this)) return
        BackScreen.trace("Notification ${n.key} from ${n.packageName}, ${lateMs} ms after posting")
        if (!NotificationFilter.shows(n, packageName)) {
            // An update can turn it into one that doesn't show, such as a download starting.
            if (active.remove(n.key) != null) RearHostActivity.notificationRemoved(n.key)
            return
        }
        val previous = active.put(n.key, n)
        if (previous == null && lateMs > LATE_MS) {
            BackScreen.log("A notification reached the app ${"%.1f".format(lateMs / 1000.0)} s late")
        }
        RearHostActivity.notificationsChanged()
        KeeperService.instance?.onNotification(n, previous)
    }

    private fun removed(key: String) {
        if (active.remove(key) != null) RearHostActivity.notificationRemoved(key)
    }

    /** [sbn] as plain text, with its app's name, and its app's icon ready ([AppIcons]). Reader thread. */
    private fun read(sbn: StatusBarNotification, rankingMap: RankingMap): RearNotification {
        val n = sbn.notification
        val ranking = Ranking().also { rankingMap.getRanking(sbn.key, it) }
        val info = appInfo(sbn)
        AppIcons.load(this, sbn.packageName, info)
        // Your per-app lock screen setting can hide more than the app asked for, never less.
        val override = ranking.lockscreenVisibilityOverride
        val visibility = if (override == Ranking.VISIBILITY_NO_OVERRIDE) n.visibility else minOf(n.visibility, override)
        val extras = n.extras
        return RearNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = extras.getString(EXTRA_SUBSTITUTE_APP_NAME)
                ?: info?.let { packageManager.getApplicationLabel(it).toString() }
                ?: sbn.packageName,
            postedAt = sbn.postTime,
            content = content(n),
            publicContent = n.publicVersion?.let(::content),
            privacy = when {
                visibility < Notification.VISIBILITY_PRIVATE -> Privacy.SECRET
                visibility == Notification.VISIBILITY_PRIVATE -> Privacy.PRIVATE
                else -> Privacy.PUBLIC
            },
            importance = ranking.importance,
            ongoing = sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            media = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || n.category == Notification.CATEGORY_TRANSPORT,
            progress = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
            alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            passesDnd = ranking.matchesInterruptionFilter(),
        )
    }

    /**
     * Its title and message. A chat's message is its latest one, after who sent it in a group;
     * otherwise the longer text if there is one.
     */
    private fun content(n: Notification): NoteContent {
        val extras = n.extras
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
        val title = conversation ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
        val latest = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        ).lastOrNull { it.text != null }
        val text = if (latest != null) {
            val sender = latest.senderPerson?.name
            val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) || conversation != null
            if (group && sender != null) "$sender: ${latest.text}" else latest.text
        } else {
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        }
        return NoteContent(title?.toString(), text?.toString())
    }

    /**
     * The app that posted [sbn]. Android adds it to every notification, which works without
     * asking to see other apps; asking by name is the fallback.
     */
    private fun appInfo(sbn: StatusBarNotification): ApplicationInfo? =
        sbn.notification.extras.getParcelable(EXTRA_APP_INFO, ApplicationInfo::class.java)
            ?: try {
                packageManager.getApplicationInfo(sbn.packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }

    companion object {
        // Notification.EXTRA_BUILDER_APPLICATION_INFO and EXTRA_SUBSTITUTE_APP_NAME, hidden
        // from the SDK. The system adds the first to every notification it posts; the second
        // is a system app's name for itself, such as "Android System".
        private const val EXTRA_APP_INFO = "android.appInfo"
        private const val EXTRA_SUBSTITUTE_APP_NAME = "android.substName"
        private const val LATE_MS = 2000L

        private var instance: RearNotificationListener? = null

        // The ones in the phone's shade that the back screen shows, by key. Main thread.
        private val active = LinkedHashMap<String, RearNotification>()

        /** Whether it's connected: access is allowed and the section is on. */
        val isListening get() = instance != null

        /** What the back screen's list shows: the ones you haven't cleared, newest first. */
        @MainThread
        fun recent(): List<RearNotification> = active.values.sortedByDescending { it.postedAt }

        /** The section was switched on or off: bind again, or let go (see the class comment). */
        @MainThread
        fun switched(context: Context, on: Boolean) {
            if (!on) {
                active.clear()
                RearHostActivity.notificationsChanged()
                instance?.requestUnbind()
                return
            }
            if (instance != null || !NotificationAccess.isGranted(context)) return
            try {
                requestRebind(NotificationAccess.component(context))
            } catch (e: RuntimeException) {
                BackScreen.log("Couldn't start reading notifications: ${e.message}")
            }
        }
    }
}

/** The icons of apps that post notifications, loaded once each, off the main thread. */
object AppIcons {
    private val icons = ConcurrentHashMap<String, Drawable.ConstantState>()

    /** Loads [packageName]'s icon if it isn't yet. Not on the main thread. */
    fun load(context: Context, packageName: String, info: ApplicationInfo?) {
        if (info == null || icons.containsKey(packageName)) return
        try {
            context.packageManager.getApplicationIcon(info).constantState?.let { icons[packageName] = it }
        } catch (e: RuntimeException) {
            BackScreen.trace("No icon for $packageName: ${e.message}")
        }
    }

    /** A fresh copy of [packageName]'s icon, if it's loaded: each view needs its own. */
    fun get(context: Context, packageName: String): Drawable? = icons[packageName]?.newDrawable(context.resources)
}
