package com.backscreen.wallpaper.notifications

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {

    private val own = "com.backscreen.wallpaper"
    private val message = RearNotification(
        key = "k",
        packageName = "com.google.android.apps.messaging",
        appName = "Messages",
        postedAt = 0,
        content = NoteContent("Alex", "On my way"),
    )

    @Test
    fun anOrdinaryOneShows() {
        assertTrue(NotificationFilter.shows(message, own))
    }

    @Test
    fun whatIsntNewsDoesntShow() {
        assertFalse(NotificationFilter.shows(message.copy(packageName = own), own))
        assertFalse(NotificationFilter.shows(message.copy(ongoing = true), own))
        assertFalse(NotificationFilter.shows(message.copy(groupSummary = true), own))
        assertFalse(NotificationFilter.shows(message.copy(media = true), own))
        // A download in progress, and silent or minimised ones.
        assertFalse(NotificationFilter.shows(message.copy(progress = true), own))
        assertFalse(NotificationFilter.shows(message.copy(importance = NotificationManager.IMPORTANCE_LOW), own))
        assertFalse(NotificationFilter.shows(message.copy(importance = NotificationManager.IMPORTANCE_MIN), own))
        assertTrue(NotificationFilter.shows(message.copy(importance = NotificationManager.IMPORTANCE_HIGH), own))
        assertFalse(NotificationFilter.shows(message.copy(privacy = Privacy.SECRET), own))
    }

    @Test
    fun aNewOneGetsABanner() {
        assertNull(NotificationFilter.heldBack(message, previous = null))
        // "Alert once" only matters for updates.
        assertNull(NotificationFilter.heldBack(message.copy(alertOnce = true), previous = null))
    }

    @Test
    fun doNotDisturbHoldsItBack() {
        assertEquals(HeldBack.DND, NotificationFilter.heldBack(message.copy(passesDnd = false), previous = null))
    }

    @Test
    fun anUpdateThatAsksNotToAlertAgainGetsNoBanner() {
        val update = message.copy(content = NoteContent("Alex", "typing…"), alertOnce = true)
        assertEquals(HeldBack.ALERT_ONCE, NotificationFilter.heldBack(update, previous = message))
    }

    @Test
    fun anUpdateWithNothingNewGetsNoBanner() {
        assertEquals(HeldBack.NOTHING_NEW, NotificationFilter.heldBack(message.copy(postedAt = 5), previous = message))
        // A new message in the same chat does.
        assertNull(NotificationFilter.heldBack(message.copy(content = NoteContent("Alex", "Here")), previous = message))
    }

    @Test
    fun notFromTheAppThatsOpen() {
        assertEquals(HeldBack.APP_OPEN, NotificationFilter.heldBack(message, null, foregroundApp = message.packageName))
        assertNull(NotificationFilter.heldBack(message, null, foregroundApp = "com.android.chrome"))
    }

    @Test
    fun eachAppLightsTheBackScreenAtMostOnceAMinute() {
        val limit = AppWakeLimit()
        assertTrue(limit.allows("chat", now = 0))
        limit.woke("chat", now = 0)
        assertFalse(limit.allows("chat", now = 30_000))
        // Another app isn't held back by it.
        assertTrue(limit.allows("mail", now = 30_000))
        assertTrue(limit.allows("chat", now = AppWakeLimit.GAP_MS))
    }

    @Test
    fun theBannerCountsTheOthersThatCameIn() {
        val stack = BannerStack()
        assertNull(stack.current)
        stack.push("a")
        assertEquals("a", stack.current)
        assertEquals(0, stack.more)
        stack.push("b")
        stack.push("c")
        assertEquals("c", stack.current)
        assertEquals(2, stack.more)
        // An update to one already counted shows it, but doesn't count again.
        stack.push("a")
        assertEquals("a", stack.current)
        assertEquals(2, stack.more)
    }

    @Test
    fun clearingOneOnThePhoneTakesItOffTheBanner() {
        val stack = BannerStack()
        listOf("a", "b", "c").forEach(stack::push)
        assertFalse(stack.remove("a"))
        assertEquals(1, stack.more)
        assertTrue(stack.remove("c"))
        assertNull(stack.current)
        stack.push("d")
        assertEquals(0, stack.more)
    }
}
