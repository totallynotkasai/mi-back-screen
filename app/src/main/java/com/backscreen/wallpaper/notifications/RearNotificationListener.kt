package com.backscreen.wallpaper.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.os.Process
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.MainThread
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.VersionedCache
import com.backscreen.wallpaper.rear.RearHostActivity
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Hears notifications arrive and go, once you've allowed Notification access, and passes them to
 * the back screen: a new one to KeeperService, which puts a banner up in the wallpaper, and the
 * ones in the phone's shade to the list you pull down there ([recent]). They're kept in memory
 * only while they're in the shade, as plain text, and never written or sent anywhere.
 *
 * Android binds it whenever access is allowed. While the section is off it asks to be let go, so
 * the app isn't woken for every notification; switching the section on binds it again
 * ([switched]). Its manifest entry asks for notifications that alert or are conversations, so by
 * default silent and ongoing ones don't reach it; that's only a default, which Settings can
 * change, so [NotificationFilter] leaves them out too.
 *
 * Android ranks the notifications again when your settings change (an app's lock screen
 * setting, Do Not Disturb, an app paused), and the ones kept follow ([reranked]).
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
            val read = shade.mapNotNull { readSafely(it, ranking) }
            BackScreen.mainHandler.post { loaded(read) }
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        shade.clear()
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
            val n = readSafely(sbn, rankingMap) ?: return@execute
            BackScreen.mainHandler.post { posted(n, late) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // Through the reader too, so it can't overtake the notification arriving.
        reader.execute { BackScreen.mainHandler.post { removed(sbn.key) } }
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        // Through the reader too, so it comes after a notification still being read.
        reader.execute { BackScreen.mainHandler.post { reranked(rankingMap) } }
    }

    private fun loaded(read: List<RearNotification>) {
        if (instance !== this) return
        shade.clear()
        for (n in read) shade[n.key] = n
        BackScreen.log("Reading notifications: ${recent().size} in the shade for the back screen")
        RearHostActivity.notificationsChanged()
    }

    private fun posted(n: RearNotification, lateMs: Long) {
        if (instance !== this || !NotificationSettings.isEnabled(this)) return
        BackScreen.trace("Notification ${logName(n.key)} from ${n.packageName}, ${lateMs} ms after posting")
        // The one it updates, if the back screen showed that.
        val previous = shade.put(n.key, n)?.takeIf(::shows)
        if (!shows(n)) {
            // An update can turn it into one that doesn't show, such as a download starting.
            if (previous != null) RearHostActivity.notificationRemoved(n.key)
            return
        }
        if (previous == null && lateMs > LATE_MS) {
            BackScreen.log("A notification reached the app ${"%.1f".format(lateMs / 1000.0)} s late")
        }
        RearHostActivity.notificationsChanged()
        KeeperService.instance?.onNotification(n, previous)
    }

    private fun removed(key: String) {
        val n = shade.remove(key) ?: return
        if (shows(n)) RearHostActivity.notificationRemoved(key)
    }

    /**
     * Android ranked the notifications again: each one in the shade takes what changed (your lock
     * screen setting for its app, its importance, Do Not Disturb, its app paused). One that no
     * longer shows goes from the list and the banner; one that shows again, as Do Not Disturb
     * ends say, is back in the list, without a banner. A banner or list showing one that changed
     * shows it as it is now.
     */
    private fun reranked(rankingMap: RankingMap) {
        if (instance !== this || shade.isEmpty()) return
        val ranking = Ranking()
        var changed = false
        for ((key, n) in shade.entries.toList()) {
            if (!rankingMap.getRanking(key, ranking)) continue
            val now = NotificationPrivacy.ranked(n, ranked(ranking))
            if (now == n) continue
            shade[key] = now
            val showed = shows(n)
            val showsNow = shows(now)
            if (showed && !showsNow) {
                BackScreen.log("A notification from ${n.packageName} no longer shows on the back screen")
                RearHostActivity.notificationRemoved(key)
            } else if (showsNow && !showed) {
                BackScreen.log("A notification from ${n.packageName} shows on the back screen again")
            }
            if (showed || showsNow) changed = true
        }
        if (changed) RearHostActivity.privacyChanged()
    }

    /** Whether the back screen shows [n] ([NotificationFilter]). */
    private fun shows(n: RearNotification) = NotificationFilter.shows(n, packageName)

    /**
     * [read], or null if [sbn] can't be read. Any app can post a malformed notification (a bad
     * parcel in its extras, say); it's skipped rather than crashing the app and the wallpaper,
     * or stopping the rest being read.
     */
    private fun readSafely(sbn: StatusBarNotification, rankingMap: RankingMap): RearNotification? = try {
        read(sbn, rankingMap)
    } catch (e: RuntimeException) {
        BackScreen.trace("Couldn't read a notification from ${sbn.packageName}: $e")
        null
    }

    /** [sbn] as plain text, with its app's name, and its app's icon ready ([AppIcons]). Reader thread. */
    private fun read(sbn: StatusBarNotification, rankingMap: RankingMap): RearNotification {
        val n = sbn.notification
        val ranking = Ranking().also { rankingMap.getRanking(sbn.key, it) }
        val info = appInfo(sbn)
        AppIcons.load(this, sbn, info)
        val extras = n.extras
        val read = RearNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = extras.getString(EXTRA_SUBSTITUTE_APP_NAME)
                ?: info?.let { packageManager.getApplicationLabel(it).toString() }
                ?: sbn.packageName,
            postedAt = sbn.postTime,
            content = content(n),
            publicContent = n.publicVersion?.let(::content),
            appVisibility = n.visibility,
            ongoing = sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            media = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || n.category == Notification.CATEGORY_TRANSPORT,
            progress = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
            alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            otherProfile = isOtherProfile(sbn),
            uid = sbn.uid,
        )
        // Its privacy, importance and the rest, from the ranking.
        return NotificationPrivacy.ranked(read, ranked(ranking))
    }

    /**
     * Whether [sbn] is from another of your profiles, such as a work profile. Not one posted for
     * every user (some of Android's own are), which is yours too.
     */
    private fun isOtherProfile(sbn: StatusBarNotification): Boolean {
        val user = sbn.user
        if (user == Process.myUserHandle()) return false
        return getSystemService(UserManager::class.java).userProfiles.contains(user)
    }

    /**
     * What [ranking] says that can change while a notification is in the shade. Your lock screen
     * setting for its app is its channel's: Android's own override also takes in your lock
     * screen's general settings, which [NotificationFormatter] applies only while locked.
     */
    private fun ranked(ranking: Ranking) = Ranked(
        yourVisibility = ranking.channel?.lockscreenVisibility ?: Ranking.VISIBILITY_NO_OVERRIDE,
        importance = ranking.importance,
        passesDnd = ranking.matchesInterruptionFilter(),
        suspended = ranking.isSuspended,
        suppressedEffects = ranking.suppressedVisualEffects,
    )

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
     * asking to see other apps; asking by name is the fallback, and for a notification posted
     * before its app was updated, whose app has moved since.
     */
    private fun appInfo(sbn: StatusBarNotification): ApplicationInfo? =
        sbn.notification.extras.getParcelable(EXTRA_APP_INFO, ApplicationInfo::class.java)
            ?.takeIf { File(it.sourceDir.orEmpty()).exists() }
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

        // Mixed into the log's stand-ins for keys so they can't be worked back; new each time the app starts.
        private val logSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }

        private var instance: RearNotificationListener? = null

        // Every notification in the phone's shade, by key, whether the back screen shows it or not:
        // one that doesn't may later, when Android ranks it again. Main thread.
        private val shade = LinkedHashMap<String, RearNotification>()

        /** Whether it's connected: access is allowed and the section is on. */
        val isListening get() = instance != null

        /** What the back screen's list shows: the ones you haven't cleared, newest first. */
        @MainThread
        fun recent(): List<RearNotification> {
            val listener = instance ?: return emptyList()
            return shade.values.filter(listener::shows).sortedByDescending { it.postedAt }
        }

        /** The one with [key] as it is now, if it's in the shade and shows. */
        @MainThread
        fun latest(key: String): RearNotification? = shade[key]?.takeIf { instance?.shows(it) == true }

        /** The section was switched on or off: bind again, or let go (see the class comment). */
        @MainThread
        fun switched(context: Context, on: Boolean) {
            if (!on) {
                shade.clear()
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

        /**
         * A short stand-in for a notification's [key] in logcat. The key holds the app's tag,
         * which can be a phone number or a contact's id; the same key gets the same stand-in
         * until the app restarts.
         */
        private fun logName(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").run {
                update(logSalt)
                digest(key.toByteArray())
            }
            return digest.take(4).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * The icons of apps that post notifications, read off the main thread and kept until the app is
 * updated ([VersionedCache]). By uid as well as package: an app in a work profile has a uid of
 * its own there, and may be another version. Without the work badge: on Android 16, asking for
 * it (PackageManager.getUserBadgedIcon) needs a permission only the system has.
 */
object AppIcons {
    private val icons = VersionedCache<Pair<Int, String>, Drawable.ConstantState>()

    /** Reads [sbn]'s app's icon, unless it's kept for this version of the app. Not on the main thread. */
    fun load(context: Context, sbn: StatusBarNotification, info: ApplicationInfo?) {
        if (info == null) return
        icons.get(sbn.uid to sbn.packageName, info.sourceDir.orEmpty()) {
            try {
                context.packageManager.getApplicationIcon(info).constantState
            } catch (e: RuntimeException) {
                BackScreen.trace("No icon for ${sbn.packageName}: ${e.message}")
                null
            }
        }
    }

    /** A fresh copy of the icon of [packageName] as [uid], if it's loaded: each view needs its own. */
    fun get(context: Context, uid: Int, packageName: String): Drawable? =
        icons.peek(uid to packageName)?.newDrawable(context.resources)
}
