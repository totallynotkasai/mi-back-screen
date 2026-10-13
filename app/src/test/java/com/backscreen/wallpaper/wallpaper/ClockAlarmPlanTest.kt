package com.backscreen.wallpaper.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockAlarmPlanTest {

    private val minute = 60_000L

    @Test
    fun notWantedIsCancelledEvenWithNothingSetHere() {
        // One set by a process that has since died: this process has nothing due, but it's there.
        assertEquals(ClockAlarmStep.CANCEL, ClockAlarmPlan.decide(wanted = false, due = 0L, next = minute))
        assertEquals(ClockAlarmStep.CANCEL, ClockAlarmPlan.decide(wanted = false, due = minute, next = minute))
    }

    @Test
    fun setForThisMinuteAlreadyIsKept() {
        assertEquals(ClockAlarmStep.KEEP, ClockAlarmPlan.decide(wanted = true, due = minute, next = minute))
    }

    @Test
    fun aNewMinuteIsSet() {
        assertEquals(ClockAlarmStep.SET, ClockAlarmPlan.decide(wanted = true, due = 0L, next = minute))
        assertEquals(ClockAlarmStep.SET, ClockAlarmPlan.decide(wanted = true, due = minute, next = 2 * minute))
    }
}
