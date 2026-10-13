package com.backscreen.wallpaper

import android.content.Intent
import com.backscreen.wallpaper.core.KeeperService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchCommandTest {

    private val tileFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    private val alias = LaunchCommand.ALIAS

    @Test
    fun aTileOpeningTheAppIsACommand() {
        assertEquals(LaunchCommand.TOGGLE, LaunchCommand.of(KeeperService.ACTION_TOGGLE, tileFlags, alias))
        assertEquals(LaunchCommand.QUICK_SWITCH, LaunchCommand.of(KeeperService.ACTION_QUICK_SWITCH, tileFlags, alias))
        assertEquals(LaunchCommand.SHOW_WALLPAPER, LaunchCommand.of(KeeperService.ACTION_SHOW_WALLPAPER, tileFlags, alias))
    }

    @Test
    fun recentsReplayingItIsNot() {
        val fromRecents = tileFlags or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        assertNull(LaunchCommand.of(KeeperService.ACTION_TOGGLE, fromRecents, alias))
        assertNull(LaunchCommand.of(KeeperService.ACTION_QUICK_SWITCH, fromRecents, alias))
    }

    @Test
    fun theLauncherIsNot() {
        assertNull(LaunchCommand.of(Intent.ACTION_MAIN, Intent.FLAG_ACTIVITY_NEW_TASK, MainActivity::class.java.name))
        assertNull(LaunchCommand.of(null, 0, null))
    }

    @Test
    fun anotherAppOpeningMainActivityWithOneIsNot() {
        // MainActivity is exported; only the private alias carries commands.
        val main = MainActivity::class.java.name
        assertNull(LaunchCommand.of(KeeperService.ACTION_TOGGLE, tileFlags, main))
        assertNull(LaunchCommand.of(KeeperService.ACTION_QUICK_SWITCH, 0, main))
        assertNull(LaunchCommand.of(KeeperService.ACTION_TOGGLE, tileFlags, null))
    }
}
