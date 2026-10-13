package com.backscreen.wallpaper.notifications

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService.Ranking

/**
 * How private a notification is, and what changes when Android ranks it again. Plain Kotlin, so
 * it's unit tested.
 */
object NotificationPrivacy {

    /**
     * From its app's choice ([appVisibility], Notification.visibility) and your lock screen
     * setting for that app ([yours], or Ranking.VISIBILITY_NO_OVERRIDE), which can hide more
     * than the app asked for, never less. Your lock screen's general settings aren't in it:
     * they apply only while the phone is locked ([NotificationFormatter]).
     */
    fun of(appVisibility: Int, yours: Int): Privacy {
        val visibility = if (yours == Ranking.VISIBILITY_NO_OVERRIDE) appVisibility else minOf(appVisibility, yours)
        return when {
            visibility < Notification.VISIBILITY_PRIVATE -> Privacy.SECRET
            visibility == Notification.VISIBILITY_PRIVATE -> Privacy.PRIVATE
            else -> Privacy.PUBLIC
        }
    }

    /** [n] as Android now ranks it ([ranked]). */
    fun ranked(n: RearNotification, ranked: Ranked) = n.copy(
        privacy = of(n.appVisibility, ranked.yourVisibility),
        importance = ranked.importance,
        passesDnd = ranked.passesDnd,
        suspended = ranked.suspended,
        hiddenFromList = ranked.suppressedEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST != 0,
        noPopUp = ranked.suppressedEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK != 0,
    )
}
