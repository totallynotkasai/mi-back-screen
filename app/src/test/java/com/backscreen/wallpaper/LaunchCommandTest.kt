package com.backscreen.wallpaper

import android.content.Intent
import com.backscreen.wallpaper.core.KeeperService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchCommandTest {

    private val tileFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    @Test
    fun aTileOpeningTheAppIsACommand() {
        assertEquals(LaunchCommand.TOGGLE, LaunchCommand.of(KeeperService.ACTION_TOGGLE, tileFlags))
        assertEquals(LaunchCommand.QUICK_SWITCH, LaunchCommand.of(KeeperService.ACTION_QUICK_SWITCH, tileFlags))
        assertEquals(LaunchCommand.SHOW_WALLPAPER, LaunchCommand.of(KeeperService.ACTION_SHOW_WALLPAPER, tileFlags))
    }

    @Test
    fun recentsReplayingItIsNot() {
        val fromRecents = tileFlags or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        assertNull(LaunchCommand.of(KeeperService.ACTION_TOGGLE, fromRecents))
        assertNull(LaunchCommand.of(KeeperService.ACTION_QUICK_SWITCH, fromRecents))
    }

    @Test
    fun theLauncherIsNot() {
        assertNull(LaunchCommand.of(Intent.ACTION_MAIN, Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNull(LaunchCommand.of(null, 0))
    }
}
