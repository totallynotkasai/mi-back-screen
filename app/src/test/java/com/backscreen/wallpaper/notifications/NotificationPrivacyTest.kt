package com.backscreen.wallpaper.notifications

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService.Ranking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPrivacyTest {

    private val none = Ranking.VISIBILITY_NO_OVERRIDE

    private val message = RearNotification(
        key = "k",
        packageName = "com.whatsapp",
        appName = "WhatsApp",
        postedAt = 0,
        content = NoteContent("Alex", "On my way"),
    )

    // As Android first ranks it: nothing changed by your settings.
    private val asPosted = Ranked(
        yourVisibility = none,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        passesDnd = true,
        suspended = false,
        suppressedEffects = 0,
    )

    @Test
    fun withoutYourSettingTheAppDecides() {
        assertEquals(Privacy.PUBLIC, NotificationPrivacy.of(Notification.VISIBILITY_PUBLIC, none))
        assertEquals(Privacy.PRIVATE, NotificationPrivacy.of(Notification.VISIBILITY_PRIVATE, none))
        assertEquals(Privacy.SECRET, NotificationPrivacy.of(Notification.VISIBILITY_SECRET, none))
    }

    @Test
    fun yourSettingHidesMoreNeverLess() {
        // "Don't show" on the lock screen for that app.
        assertEquals(Privacy.SECRET, NotificationPrivacy.of(Notification.VISIBILITY_PUBLIC, Notification.VISIBILITY_SECRET))
        // "Hide sensitive content" for that app.
        assertEquals(Privacy.PRIVATE, NotificationPrivacy.of(Notification.VISIBILITY_PUBLIC, Notification.VISIBILITY_PRIVATE))
        // "Show all" can't make a secret one show.
        assertEquals(Privacy.SECRET, NotificationPrivacy.of(Notification.VISIBILITY_SECRET, Notification.VISIBILITY_PUBLIC))
    }

    @Test
    fun rankingAgainAppliesYourNewSetting() {
        val shown = NotificationPrivacy.ranked(message.copy(appVisibility = Notification.VISIBILITY_PUBLIC), asPosted)
        assertEquals(Privacy.PUBLIC, shown.privacy)
        // You set the app to "Don't show" while it's in the list: it no longer shows.
        val hidden = NotificationPrivacy.ranked(shown, asPosted.copy(yourVisibility = Notification.VISIBILITY_SECRET))
        assertEquals(Privacy.SECRET, hidden.privacy)
        assertFalse(NotificationFilter.shows(hidden, "own"))
        // And back again: the app's own choice is kept, so it shows as before.
        assertEquals(Privacy.PUBLIC, NotificationPrivacy.ranked(hidden, asPosted).privacy)
    }

    @Test
    fun rankingAgainTakesWhatElseChanged() {
        val paused = NotificationPrivacy.ranked(message, asPosted.copy(suspended = true))
        assertTrue(paused.suspended)
        val quieter = NotificationPrivacy.ranked(message, asPosted.copy(importance = NotificationManager.IMPORTANCE_LOW))
        assertFalse(NotificationFilter.shows(quieter, "own"))
        assertFalse(NotificationPrivacy.ranked(message, asPosted.copy(passesDnd = false)).passesDnd)
    }

    @Test
    fun doNotDisturbsHiddenEffectsAreRead() {
        val list = NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
        val peek = NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK
        NotificationPrivacy.ranked(message, asPosted.copy(suppressedEffects = list)).let {
            assertTrue(it.hiddenFromList)
            assertFalse(it.noPopUp)
        }
        NotificationPrivacy.ranked(message, asPosted.copy(suppressedEffects = peek)).let {
            assertFalse(it.hiddenFromList)
            assertTrue(it.noPopUp)
        }
        // Effects that don't matter here, such as the status bar icon, change nothing.
        val other = NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR
        assertEquals(message, NotificationPrivacy.ranked(message, asPosted.copy(suppressedEffects = other)))
    }

    @Test
    fun nothingChangedLeavesItAsItWas() {
        assertEquals(message, NotificationPrivacy.ranked(message, asPosted))
    }
}
