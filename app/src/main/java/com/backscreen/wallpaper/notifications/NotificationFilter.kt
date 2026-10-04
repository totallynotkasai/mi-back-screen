package com.backscreen.wallpaper.notifications

import android.app.NotificationManager

/** Why a notification that shows on the back screen gets no banner. */
enum class HeldBack(val why: String) {
    DND("Do Not Disturb"),
    ALERT_ONCE("an update that doesn't alert again"),
    NOTHING_NEW("an update with nothing new"),
    APP_OPEN("its app is open on the main screen"),
}

/**
 * Which notifications the back screen shows, and which of them get a banner. Plain Kotlin, so
 * it's unit tested.
 *
 * Shown, in a banner and in the list: what's news. Not this app's own, ongoing ones and
 * foreground services', media players, progress bars such as downloads, group summaries (the
 * group's own notifications show instead), silent or minimised ones, or secret ones.
 *
 * A banner interrupts, so it also waits for Do Not Disturb, and isn't shown again for an update
 * that asks not to alert again or says nothing new, nor for the app that's open on the main
 * screen. The list, which you open yourself, shows them all.
 */
object NotificationFilter {

    /** Whether [n] belongs on the back screen at all. [ownPackage]: this app's. */
    fun shows(n: RearNotification, ownPackage: String) =
        n.packageName != ownPackage && !n.ongoing && !n.groupSummary && !n.media && !n.progress &&
            n.importance >= NotificationManager.IMPORTANCE_DEFAULT && n.privacy != Privacy.SECRET

    /**
     * Why [n] gets no banner, or null if it does. [previous]: the notification it updates, if
     * it's an update; [foregroundApp]: the app open on the main screen, if known.
     */
    fun heldBack(n: RearNotification, previous: RearNotification?, foregroundApp: String? = null) = when {
        !n.passesDnd -> HeldBack.DND
        previous != null && n.alertOnce -> HeldBack.ALERT_ONCE
        previous != null && previous.content == n.content && previous.publicContent == n.publicContent ->
            HeldBack.NOTHING_NEW
        n.packageName == foregroundApp -> HeldBack.APP_OPEN
        else -> null
    }
}

/**
 * Each app may light up the back screen at most once every [gapMs], so a busy chat can't keep it
 * lit: a wake keeps it lit about 16 s. Its banners still show while it's dimmed. Times are from
 * [android.os.SystemClock.elapsedRealtime]. Plain Kotlin, so it's unit tested.
 */
class AppWakeLimit(private val gapMs: Long = GAP_MS) {
    private val last = HashMap<String, Long>()

    fun allows(packageName: String, now: Long) = last[packageName]?.let { now - it >= gapMs } ?: true

    fun woke(packageName: String, now: Long) {
        last.values.removeAll { now - it >= gapMs }
        last[packageName] = now
    }

    companion object {
        const val GAP_MS = 60_000L
    }
}

/**
 * What the banner shows: the newest notification, and how many others came in while it was up,
 * for "+2 more". An update to one already counted doesn't count again. Plain Kotlin, so it's
 * unit tested.
 */
class BannerStack {
    private val keys = LinkedHashSet<String>()

    /** The notification the banner shows, if it's up. */
    val current: String? get() = keys.lastOrNull()

    /** How many others came in while it was up. */
    val more: Int get() = (keys.size - 1).coerceAtLeast(0)

    /** [key] arrived, or was updated: it's the one shown now. */
    fun push(key: String) {
        keys.remove(key)
        keys.add(key)
    }

    /** [key] was cleared. True if it was the one shown, which then goes. */
    fun remove(key: String): Boolean {
        val wasCurrent = key == current
        keys.remove(key)
        if (wasCurrent) keys.clear()
        return wasCurrent
    }

    /** The banner went away. */
    fun clear() = keys.clear()
}
