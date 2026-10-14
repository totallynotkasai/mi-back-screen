package com.backscreen.wallpaper.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuStateTest {

    private fun of(ping: Boolean = true, granted: Boolean = false, rationale: Boolean = false, installed: Boolean = true) =
        ShizukuState.of(ping, granted, rationale, installed)

    @Test
    fun readyWhenRunningAndAllowed() {
        assertEquals(ShizukuState.READY, of(granted = true))
    }

    @Test
    fun allowedWinsOverAnOldRefusal() {
        // Shizuku keeps "won't ask again" until it reconnects, though it's been allowed since in its app.
        assertEquals(ShizukuState.READY, of(granted = true, rationale = true))
    }

    @Test
    fun notAllowedWhileItCanStillAsk() {
        assertEquals(ShizukuState.NOT_ALLOWED, of())
    }

    @Test
    fun blockedOnceItWontAskAgain() {
        assertEquals(ShizukuState.BLOCKED, of(rationale = true))
    }

    @Test
    fun notRunningWhenInstalledButSilent() {
        assertEquals(ShizukuState.NOT_RUNNING, of(ping = false))
        // Nothing it said before it stopped counts.
        assertEquals(ShizukuState.NOT_RUNNING, of(ping = false, granted = true, rationale = true))
    }

    @Test
    fun notInstalledWhenNeither() {
        assertEquals(ShizukuState.NOT_INSTALLED, of(ping = false, installed = false))
    }

    @Test
    fun runningWithoutItsAppIsNotNotInstalled() {
        // Sui (Shizuku for rooted phones) answers without Shizuku's app.
        assertEquals(ShizukuState.NOT_ALLOWED, of(installed = false))
        assertEquals(ShizukuState.READY, of(granted = true, installed = false))
    }
}
