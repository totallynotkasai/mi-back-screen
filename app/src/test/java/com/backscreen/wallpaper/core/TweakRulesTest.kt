package com.backscreen.wallpaper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakRulesTest {

    @Test
    fun theOriginalIsSavedBeforeTheFirstChange() {
        assertEquals(Tweak("10000", "120000"), TweakRules.beforeChange(null, "10000", "120000"))
        // An unset setting is saved as unset, so it's unset again afterwards.
        assertEquals(Tweak(null, "120000"), TweakRules.beforeChange(null, null, "120000"))
    }

    @Test
    fun aSecondChangeKeepsTheFirstOriginal() {
        // 2 min, then 10 min chosen while the app is there: 10 s still goes back.
        val first = TweakRules.beforeChange(null, "10000", "120000")
        assertEquals(Tweak("10000", "600000"), TweakRules.beforeChange(first, "120000", "600000"))
    }

    @Test
    fun aChangeYouMadeMeanwhileBecomesTheOriginal() {
        // You set Xiaomi's timeout to 30 s yourself, then chose 10 min here.
        val first = TweakRules.beforeChange(null, "10000", "120000")
        assertEquals(Tweak("30000", "600000"), TweakRules.beforeChange(first, "30000", "600000"))
    }

    @Test
    fun itIsPutBackOnlyWhileItIsStillOurs() {
        val saved = Tweak("10000", "120000")
        assertTrue(TweakRules.shouldPutBack(saved, "120000"))
        assertFalse(TweakRules.shouldPutBack(saved, "30000"))
        assertFalse(TweakRules.shouldPutBack(saved, null))
        assertEquals("10000", TweakRules.toPutBack(saved, "120000"))
        assertEquals("30000", TweakRules.toPutBack(saved, "30000"))
        assertEquals("260", TweakRules.toPutBack(null, "260"))
    }

    @Test
    fun staysLitForChangedDuringALendIsWhatTheLendPutsBack() {
        // Quick Switch raised Xiaomi's 10 s to 2 min; Stays lit for is now 30 s. The app keeps its
        // 2 min, and 30 s goes back when it leaves.
        val lend = Tweak("10000", "120000")
        assertEquals(BaseChange.PutBackLater(Tweak("30000", "120000")), TweakRules.baseChanged(lend, "120000", "30000"))
        // Then As set, still during the lend: Xiaomi's own 10 s goes back instead.
        assertEquals(
            BaseChange.PutBackLater(Tweak("10000", "120000")),
            TweakRules.baseChanged(Tweak("30000", "120000"), "120000", "10000"),
        )
        // What it puts back already.
        assertEquals(BaseChange.None, TweakRules.baseChanged(lend, "120000", "10000"))
    }

    @Test
    fun withNoLendStaysLitForIsSetAtOnce() {
        assertEquals(BaseChange.Write("30000"), TweakRules.baseChanged(null, "10000", "30000"))
        assertEquals(BaseChange.None, TweakRules.baseChanged(null, "30000", "30000"))
        // A lend whose value you changed yourself meanwhile won't put anything back, so it's set now.
        assertEquals(BaseChange.Write("30000"), TweakRules.baseChanged(Tweak("10000", "120000"), "60000", "30000"))
        // Xiaomi's own value: the one saved first, else what a lend would put back, else what it holds.
        assertEquals("10000", TweakRules.xiaomiOwn(Saved("10000"), null, "30000"))
        assertNull(TweakRules.xiaomiOwn(Saved(null), null, "30000"))
        assertEquals("10000", TweakRules.xiaomiOwn(null, Tweak("10000", "120000"), "120000"))
        assertEquals("10000", TweakRules.xiaomiOwn(null, null, "10000"))
    }

    @Test
    fun readsXiaomisTimeout() {
        assertEquals("10000", TweakRules.timeout("10000\n"))
        assertNull(TweakRules.timeout("null\n"))
        assertNull(TweakRules.timeout(""))
    }

    @Test
    fun readsTheDensityOverrideNotThePanels() {
        // As the 17 Pro Max prints it: Xiaomi overrides the panel's 450 to 260.
        val xiaomi = "Physical density: 450\nOverride density: 260\n"
        assertEquals("260", TweakRules.density(xiaomi))
        assertEquals(450, TweakRules.physicalDensity(xiaomi))
        assertNull(TweakRules.density("Physical density: 450\n"))
    }

    @Test
    fun rotationIsOneValueAndSplitsBack() {
        val natural = TweakRules.rotation("free\n", "default\n")
        assertEquals("free|default", natural)
        assertEquals(null to "default", TweakRules.splitRotation(natural))
        assertEquals(3 to "enabled", TweakRules.splitRotation("lock 3|enabled"))
        // Anything unreadable puts the natural, free rotation back.
        assertEquals(null to "default", TweakRules.splitRotation(null))
    }
}
