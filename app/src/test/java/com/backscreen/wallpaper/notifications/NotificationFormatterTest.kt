package com.backscreen.wallpaper.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFormatterTest {

    private val message = RearNotification(
        key = "k",
        packageName = "com.whatsapp",
        appName = "WhatsApp",
        postedAt = 0,
        content = NoteContent("Alex", "Running late, save me a seat"),
        publicContent = NoteContent("1 new message"),
    )

    // Your phone's lock screen hides sensitive content.
    private val hidesSensitive = LockScreen(showNotifications = true, showSensitive = false)
    private val showsAll = LockScreen()

    private fun shown(
        n: RearNotification = message,
        style: NotificationStyle = NotificationStyle.FULL,
        locked: Boolean = false,
        lockScreen: LockScreen = hidesSensitive,
    ) = NotificationFormatter.format(n, style, locked, lockScreen)

    @Test
    fun eachStyleShowsMoreThanTheLast() {
        shown(style = NotificationStyle.DISCREET)!!.let {
            assertEquals("WhatsApp", it.appName)
            assertNull(it.title)
            assertNull(it.text)
        }
        shown(style = NotificationStyle.NORMAL)!!.let {
            assertEquals("Alex", it.title)
            assertNull(it.text)
        }
        shown(style = NotificationStyle.FULL)!!.let {
            assertEquals("Alex", it.title)
            assertEquals("Running late, save me a seat", it.text)
        }
    }

    @Test
    fun aSecretOneNeverShows() {
        assertNull(shown(message.copy(privacy = Privacy.SECRET)))
        assertNull(shown(message.copy(privacy = Privacy.SECRET), lockScreen = showsAll))
    }

    @Test
    fun lockedAPrivateOneShowsItsPublicVersionWhenTheLockScreenHidesSensitiveContent() {
        shown(locked = true)!!.let {
            assertEquals("1 new message", it.title)
            assertNull(it.text)
        }
        // Still no more than the style.
        assertNull(shown(locked = true, style = NotificationStyle.DISCREET)!!.title)
    }

    @Test
    fun lockedAPrivateOneWithNoPublicVersionShowsJustTheApp() {
        shown(message.copy(publicContent = null), locked = true)!!.let {
            assertEquals("WhatsApp", it.appName)
            assertNull(it.title)
            assertNull(it.text)
        }
    }

    @Test
    fun lockedItFollowsALockScreenThatShowsEverything() {
        assertEquals("Running late, save me a seat", shown(locked = true, lockScreen = showsAll)!!.text)
    }

    @Test
    fun lockedAPublicOneShowsInFull() {
        assertEquals("Running late, save me a seat", shown(message.copy(privacy = Privacy.PUBLIC), locked = true)!!.text)
    }

    @Test
    fun lockedWithNoNotificationsOnTheLockScreenOnlyTheAppShows() {
        val none = LockScreen(showNotifications = false, showSensitive = true)
        shown(message.copy(privacy = Privacy.PUBLIC), locked = true, lockScreen = none)!!.let {
            assertNull(it.title)
            assertNull(it.text)
        }
    }

    @Test
    fun unlockedTheLockScreenDoesntMatter() {
        val none = LockScreen(showNotifications = false, showSensitive = false)
        assertEquals("Running late, save me a seat", shown(locked = false, lockScreen = none)!!.text)
    }

    @Test
    fun aTitleThatOnlyRepeatsTheAppIsDropped() {
        assertNull(shown(message.copy(content = NoteContent("whatsapp", "Hi")))!!.title)
    }

    @Test
    fun textIsTidied() {
        val messy = message.copy(content = NoteContent("  Alex \n Smith ", "First line  \n\n\n  second   line \n"))
        shown(messy)!!.let {
            assertEquals("Alex Smith", it.title)
            assertEquals("First line\nsecond line", it.text)
        }
        assertNull(shown(message.copy(content = NoteContent(" ", "\n ")))!!.text)
        val long = message.copy(content = NoteContent("Alex", "x".repeat(1000)))
        assertEquals(NotificationFormatter.TEXT_MAX, shown(long)!!.text!!.length)
    }

    @Test
    fun aLongMessageStaysLonger() {
        val short = shown()!!
        assertEquals(NotificationFormatter.BANNER_MS, NotificationFormatter.bannerMs(short))
        assertEquals(NotificationFormatter.BANNER_MS, NotificationFormatter.bannerMs(shown(style = NotificationStyle.NORMAL)!!))
        val long = shown(message.copy(content = NoteContent("Alex", "y".repeat(120))))!!
        assertTrue(NotificationFormatter.bannerMs(long) > NotificationFormatter.BANNER_MS)
        val veryLong = shown(message.copy(content = NoteContent("Alex", "z".repeat(300))))!!
        assertEquals(NotificationFormatter.BANNER_MAX_MS, NotificationFormatter.bannerMs(veryLong))
    }

    @Test
    fun howLongAgo() {
        val minute = 60_000L
        assertEquals(Ago.Now, NotificationFormatter.ago(now = 59_000, then = 0))
        assertEquals(Ago.Minutes(5), NotificationFormatter.ago(now = 5 * minute, then = 0))
        assertEquals(Ago.Hours(2), NotificationFormatter.ago(now = 150 * minute, then = 0))
        assertEquals(Ago.Days(3), NotificationFormatter.ago(now = 3 * 24 * 60 * minute, then = 0))
        // Dated in the future, as some reminders are.
        assertEquals(Ago.Now, NotificationFormatter.ago(now = 0, then = 10 * minute))
    }
}
