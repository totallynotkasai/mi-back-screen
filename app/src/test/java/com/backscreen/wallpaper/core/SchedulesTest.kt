package com.backscreen.wallpaper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class SchedulesTest {

    // 12 October 2026 is a Monday; London's clocks go back on Sunday 25 October.
    private val london = ZoneId.of("Europe/London")

    private fun at(dateTime: String) = LocalDateTime.parse(dateTime).atZone(london).toInstant().toEpochMilli()

    private fun schedule(start: String, end: String, days: Int = Schedule.EVERY_DAY, id: Long = 1, enabled: Boolean = true) =
        Schedule(id, LocalTime.parse(start), LocalTime.parse(end), days, enabled)

    private fun flip(dateTime: String, on: Boolean) = Schedules.Flip(at(dateTime), on)

    private fun flips(schedules: List<Schedule>, from: String) = Schedules.flips(schedules, at(from), london)

    private val day = schedule("07:00", "23:00")
    private val lunch = schedule("12:00", "14:00", id = 2)

    @Test
    fun overlappingSchedulesDontFlipInside() {
        // 07–23 and 12–14 together: 12:00 and 14:00 change nothing.
        assertEquals(
            listOf(flip("2026-10-12T23:00", false), flip("2026-10-13T07:00", true)),
            flips(listOf(day, lunch), "2026-10-12T10:00").take(2),
        )
    }

    @Test
    fun anEveryDay24HourScheduleNeverFlips() {
        val always = schedule("07:00", "07:00")
        assertEquals(emptyList<Schedules.Flip>(), flips(listOf(always), "2026-10-12T10:00"))
        // So a switch by hand is never undone.
        assertNull(Schedules.nextChange(listOf(always), at("2026-10-12T10:00"), on = false, zone = london))
    }

    @Test
    fun aWeekday24HourScheduleFlipsAtTheWeekend() {
        val weekdays = schedule("00:00", "00:00", Schedule.WEEKDAYS)
        assertEquals(
            listOf(flip("2026-10-17T00:00", false), flip("2026-10-19T00:00", true)),
            flips(listOf(weekdays), "2026-10-13T10:00"),
        )
    }

    @Test
    fun anOvernightScheduleFlipsAtBothEnds() {
        val night = schedule("22:00", "06:00")
        assertEquals(
            listOf(flip("2026-10-12T22:00", true), flip("2026-10-13T06:00", false)),
            flips(listOf(night), "2026-10-12T10:00").take(2),
        )
        assertTrue(Schedules.activeAt(listOf(night), at("2026-10-13T01:00"), london))
    }

    @Test
    fun switchedOffByHandTheNextChangeIsTheNextOn() {
        // Off by hand at 13:00, inside 07–23 and 12–14: it comes back on at 07:00, not at 14:00.
        assertEquals(
            at("2026-10-13T07:00") to true,
            Schedules.nextChange(listOf(day, lunch), at("2026-10-12T13:00"), on = false, zone = london),
        )
        // Left on, the next change is 23:00's off.
        assertEquals(
            at("2026-10-12T23:00") to false,
            Schedules.nextChange(listOf(day, lunch), at("2026-10-12T13:00"), on = true, zone = london),
        )
    }

    @Test
    fun aClockChangeDayFlipsOnce() {
        // 01:00–02:00 happens twice on 25 October. 01:30–03:00 starts once, at the first 01:30,
        // and lasts two and a half hours.
        val early = schedule("01:30", "03:00")
        val sunday = flips(listOf(early), "2026-10-25T00:00").filter { it.time < at("2026-10-26T00:00") }
        assertEquals(listOf(flip("2026-10-25T01:30", true), flip("2026-10-25T03:00", false)), sunday)
        assertEquals(150 * 60 * 1000L, sunday[1].time - sunday[0].time)
    }

    @Test
    fun switchedOffSchedulesDontCount() {
        assertEquals(emptyList<Schedules.Flip>(), flips(listOf(day.copy(enabled = false)), "2026-10-12T10:00"))
    }

    @Test
    fun aMissedEndIsCaughtUp() {
        // On at 22:00; the phone was off across 23:00 and started again at 06:00.
        assertEquals(
            flip("2026-10-12T23:00", false),
            Schedules.missed(listOf(day), at("2026-10-12T22:00"), at("2026-10-13T06:00"), on = true, zone = london),
        )
    }

    @Test
    fun aSwitchByHandAfterTheMissedFlipWins() {
        // Switched back on by hand at 23:30, after 23:00's off: it stays on.
        assertNull(Schedules.missed(listOf(day), at("2026-10-12T23:30"), at("2026-10-13T06:00"), on = true, zone = london))
    }

    @Test
    fun onlyTheLatestMissedFlipCounts() {
        // Off from 22:00 to 08:00: 23:00's off and 07:00's on were both missed, and 07:00's counts.
        val decided = at("2026-10-12T22:00")
        val now = at("2026-10-13T08:00")
        assertNull(Schedules.missed(listOf(day), decided, now, on = true, zone = london))
        assertEquals(flip("2026-10-13T07:00", true), Schedules.missed(listOf(day), decided, now, on = false, zone = london))
    }

    @Test
    fun aFlipMissedLongAgoIsStillFound() {
        // Mondays 07–23 only; the phone was off for ten days from Monday 12th at 22:00.
        val mondays = schedule("07:00", "23:00", days = 1)
        assertEquals(
            flip("2026-10-19T23:00", false),
            Schedules.missed(listOf(mondays), at("2026-10-12T22:00"), at("2026-10-22T12:00"), on = true, zone = london),
        )
    }

    @Test
    fun savingSchedulesDoesntSwitch() {
        // 07–23 added at 12:00 with the wallpaper off. Saving decides the switch then, so nothing
        // is missed; it first switches on at 07:00 tomorrow.
        val now = at("2026-10-12T12:00")
        assertNull(Schedules.missed(listOf(day), now, now, on = false, zone = london))
        assertEquals(at("2026-10-13T07:00") to true, Schedules.nextChange(listOf(day), now, on = false, zone = london))
    }

    @Test
    fun aTimeZoneChangeCatchesUpByTheClock() {
        // Decided at 20:30 in London; at 21:00 London time the phone moves to Dubai, where it's
        // 00:00. 23:00's off has passed on the new clock, so it's caught up.
        val dubai = ZoneId.of("Asia/Dubai")
        val decided = Schedules.inZone(at("2026-10-12T20:30"), london, dubai)
        val now = at("2026-10-12T21:00")
        assertEquals(
            Schedules.Flip(LocalDateTime.parse("2026-10-12T23:00").atZone(dubai).toInstant().toEpochMilli(), false),
            Schedules.missed(listOf(day), decided, now, on = true, zone = dubai),
        )
        // Westward, to New York at 16:00: 20:30 hasn't come yet there, so nothing is replayed.
        val newYork = ZoneId.of("America/New_York")
        assertNull(Schedules.missed(listOf(day), Schedules.inZone(at("2026-10-12T20:30"), london, newYork), now, on = false, zone = newYork))
        // No change of zone, no change.
        assertEquals(at("2026-10-12T20:30"), Schedules.inZone(at("2026-10-12T20:30"), london, london))
    }

    @Test
    fun theClockSetBackDoesntReplay() {
        // Decided at 07:30 Tuesday; then the clock was set back to 23:30 Monday. 23:00's off
        // isn't applied, and nor is anything with no decision saved.
        assertNull(Schedules.missed(listOf(day), at("2026-10-13T07:30"), at("2026-10-12T23:30"), on = true, zone = london))
        assertNull(Schedules.missed(listOf(day), null, at("2026-10-12T23:30"), on = true, zone = london))
    }
}
