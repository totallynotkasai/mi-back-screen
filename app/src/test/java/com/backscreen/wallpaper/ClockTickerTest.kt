package com.backscreen.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ClockTickerTest {

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun nextMinuteIsTheFollowingBoundary() {
        assertEquals(at("2026-10-01T16:37:00Z"), ClockTicker.nextMinute(at("2026-10-01T16:36:00.001Z")))
        assertEquals(at("2026-10-01T16:37:00Z"), ClockTicker.nextMinute(at("2026-10-01T16:36:59.999Z")))
    }

    @Test
    fun onABoundaryItIsTheNextOne() {
        assertEquals(at("2026-10-01T16:37:00Z"), ClockTicker.nextMinute(at("2026-10-01T16:36:00Z")))
    }

    @Test
    fun crossesHoursAndDays() {
        assertEquals(at("2026-10-02T00:00:00Z"), ClockTicker.nextMinute(at("2026-10-01T23:59:30Z")))
    }

    @Test
    fun boundaryIsALocalMinuteInOddZones() {
        // Zones a quarter or half hour off UTC still change minute on a UTC minute boundary.
        for (zone in listOf("Asia/Kathmandu", "Asia/Kolkata", "Australia/Eucla", "Europe/London")) {
            val now = at("2026-10-01T16:36:42.5Z")
            val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ClockTicker.nextMinute(now)), ZoneId.of(zone))
            assertEquals(zone, 0, local.second)
            assertEquals(zone, 0, local.nano)
        }
    }

    @Test
    fun beforeTheEpoch() {
        assertEquals(0L, ClockTicker.nextMinute(-1L))
        assertEquals(-60_000L, ClockTicker.nextMinute(-60_001L))
    }
}
