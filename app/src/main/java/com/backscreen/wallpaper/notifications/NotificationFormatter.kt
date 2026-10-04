package com.backscreen.wallpaper.notifications

import com.backscreen.wallpaper.R
import kotlin.math.min

/**
 * How much of a notification the back screen shows. It faces away from you, so anyone nearby
 * can read it.
 */
enum class NotificationStyle(val label: Int, val summary: Int) {
    /** The app's icon and name: "WhatsApp · now". */
    DISCREET(R.string.style_discreet, R.string.style_discreet_summary),

    /** Adds the title, usually who it's from or the subject. */
    NORMAL(R.string.style_normal, R.string.style_normal_summary),

    /** Adds the message, up to 3 lines. */
    FULL(R.string.style_full, R.string.style_full_summary),
}

/**
 * What your lock screen is set to show (Settings → Lock screen): any notifications at all, and
 * their sensitive content. Xiaomi's own back screen follows the same two settings.
 */
data class LockScreen(val showNotifications: Boolean = true, val showSensitive: Boolean = true)

/** A notification as the back screen shows it: the app, then as much as the style and privacy allow. */
data class ShownNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val postedAt: Long,
)

/** How long ago something arrived, for "WhatsApp · 5 min". */
sealed interface Ago {
    data object Now : Ago
    data class Minutes(val n: Int) : Ago
    data class Hours(val n: Int) : Ago
    data class Days(val n: Int) : Ago
}

/**
 * Turns a notification into what the back screen shows, by the style you chose and its privacy.
 * Plain Kotlin, so it's unit tested.
 *
 * Privacy, as on your lock screen: a secret notification never shows. While the phone is locked,
 * one marked private (most are) shows only the app, or the app's own public version, if your
 * lock screen hides sensitive content; and with no notifications on the lock screen at all,
 * only the app shows.
 */
object NotificationFormatter {
    /** Most of the message that fits in 3 lines, and then some. */
    const val TEXT_MAX = 300
    const val BANNER_MS = 5_000L
    const val BANNER_MAX_MS = 9_000L

    // Up to this much of the message reads in the banner's usual time; each character after
    // that adds a little, about the pace of reading.
    private const val SHORT_TEXT = 60
    private const val MS_PER_CHAR = 60L

    /** What shows for [n], or null if nothing may. [locked]: the phone is locked now. */
    fun format(n: RearNotification, style: NotificationStyle, locked: Boolean, lockScreen: LockScreen): ShownNotification? {
        if (n.privacy == Privacy.SECRET) return null
        val content = when {
            !locked -> n.content
            !lockScreen.showNotifications -> NoteContent()
            n.privacy == Privacy.PRIVATE && !lockScreen.showSensitive -> n.publicContent ?: NoteContent()
            else -> n.content
        }
        // A title that only repeats the app's name says nothing more.
        val title = line(content.title)?.takeUnless { it.equals(n.appName, ignoreCase = true) }
        return ShownNotification(
            key = n.key,
            packageName = n.packageName,
            appName = n.appName,
            title = title.takeIf { style >= NotificationStyle.NORMAL },
            text = message(content.text).takeIf { style == NotificationStyle.FULL },
            postedAt = n.postedAt,
        )
    }

    /** How long the banner stays: about 5 s, longer for a long message, up to 9 s. */
    fun bannerMs(shown: ShownNotification): Long {
        val extra = (shown.text?.length ?: 0) - SHORT_TEXT
        return if (extra <= 0) BANNER_MS else min(BANNER_MAX_MS, BANNER_MS + extra * MS_PER_CHAR)
    }

    /** How long ago [then] was at [now], both in milliseconds. */
    fun ago(now: Long, then: Long): Ago {
        val minutes = ((now - then) / 60_000).toInt()
        return when {
            minutes < 1 -> Ago.Now
            minutes < 60 -> Ago.Minutes(minutes)
            minutes < 24 * 60 -> Ago.Hours(minutes / 60)
            else -> Ago.Days(minutes / (24 * 60))
        }
    }

    /** One line, with runs of spaces and line breaks made single spaces. */
    private fun line(text: String?) = text?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** The message: each line tidied, blank lines dropped, and no longer than [TEXT_MAX]. */
    private fun message(text: String?): String? {
        val lines = text?.lines()?.mapNotNull(::line) ?: return null
        return lines.joinToString("\n").take(TEXT_MAX).trim().takeIf { it.isNotEmpty() }
    }

    private val WHITESPACE = Regex("\\s+")
}
