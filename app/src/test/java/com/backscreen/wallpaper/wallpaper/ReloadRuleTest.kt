package com.backscreen.wallpaper.wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadRuleTest {

    @Test
    fun aReloadOvertakenByAnOrdinaryLoadStillHappens() {
        val rule = ReloadRule()
        val forced = rule.start(force = true)
        // The back screen woke, say, before it finished.
        val ordinary = rule.start(force = false)
        assertTrue(ordinary.reload)
        assertFalse(rule.ended(forced))
        assertTrue(rule.ended(ordinary))
        assertFalse(rule.start(force = false).reload)
    }

    @Test
    fun theNewestEndingSettlesItEvenIfItFailed() {
        val rule = ReloadRule()
        val failed = rule.start(force = true)
        // Before, a failed load left the reload pending, so the next ordinary one decoded again.
        assertTrue(rule.ended(failed))
        assertFalse(rule.start(force = false).reload)
    }

    @Test
    fun oneAskedForWhileNothingCanLoadIsDoneByTheNext() {
        val rule = ReloadRule()
        rule.ask()
        assertTrue(rule.start(force = false).reload)
    }

    @Test
    fun onlyTheNewestCounts() {
        val rule = ReloadRule()
        val older = rule.start(force = false)
        val newer = rule.start(force = false)
        assertFalse(rule.ended(older))
        assertTrue(rule.ended(newer))
    }
}
