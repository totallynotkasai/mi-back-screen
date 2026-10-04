package com.backscreen.wallpaper.notifications

import android.app.NotificationManager

/** What a notification says, as plain text. */
data class NoteContent(val title: String? = null, val text: String? = null)

/**
 * How much of a notification may show on a lock screen, from its app and your per-app setting
 * (Notification.visibility): all of it, all of it unless your lock screen hides sensitive
 * content, or none of it.
 */
enum class Privacy { PUBLIC, PRIVATE, SECRET }

/**
 * One notification, as the back screen needs it: plain text, kept in memory only while it's in
 * the phone's shade and never written anywhere. [RearNotificationListener] makes it from what
 * Android passes on; the rules that decide whether and how it shows are plain Kotlin
 * ([NotificationFilter], [NotificationFormatter]), so they're unit tested.
 */
data class RearNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    /** When its app posted it, in wall-clock time. */
    val postedAt: Long,
    val content: NoteContent,
    /** The app's own version for lock screens that hide sensitive content, e.g. "2 new emails". */
    val publicContent: NoteContent? = null,
    val privacy: Privacy = Privacy.PRIVATE,
    /** Its channel's importance (NotificationManager.IMPORTANCE_*). */
    val importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
    /** Ongoing, or a foreground service's: always there, not news. */
    val ongoing: Boolean = false,
    /** A group's summary; the group's own notifications show instead. */
    val groupSummary: Boolean = false,
    /** A media player. */
    val media: Boolean = false,
    /** A progress bar, such as a download. */
    val progress: Boolean = false,
    /** Its app asked for an update to it not to alert again. */
    val alertOnce: Boolean = false,
    /** False while Do Not Disturb holds it back. */
    val passesDnd: Boolean = true,
)
