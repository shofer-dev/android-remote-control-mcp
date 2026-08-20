package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The active-hours arithmetic — the rule that makes "the phone did not act at 3am" a
 * guarantee. Every branch is pinned here because the failure modes are silent in both
 * directions: a window read too wide drives at night, and a window read too narrow bricks a
 * fleet with no error anywhere.
 */
class ActiveHoursTest {
    private fun minutes(
        hour: Int,
        minute: Int = 0,
    ) = hour * 60 + minute

    @Test
    fun `an empty window is always open`() {
        assertTrue(ActiveHours.isOpen("", minutes(3)))
        assertTrue(ActiveHours.isOpen("   ", minutes(3)))
        assertNull(ActiveHours.parse(""))
    }

    @Test
    fun `an unparseable window is always open rather than always closed`() {
        // The platform validates the spelling at write time, so a value this app cannot read
        // is a contract change — and refusing every command over a format change would brick
        // the fleet with nothing in any log to explain it.
        assertTrue(ActiveHours.isOpen("not-a-window", minutes(3)))
        assertTrue(ActiveHours.isOpen("08:00", minutes(3)))
        assertTrue(ActiveHours.isOpen("25:00-26:00", minutes(3)))
        assertTrue(ActiveHours.isOpen("08:61-22:00", minutes(3)))
    }

    @Test
    fun `a daytime window admits its own hours and refuses the night`() {
        val spec = "08:00-22:00"
        assertFalse(ActiveHours.isOpen(spec, minutes(3)))
        assertFalse(ActiveHours.isOpen(spec, minutes(7, 59)))
        assertTrue(ActiveHours.isOpen(spec, minutes(8)))
        assertTrue(ActiveHours.isOpen(spec, minutes(21, 59)))
        assertFalse(ActiveHours.isOpen(spec, minutes(22)))
        assertFalse(ActiveHours.isOpen(spec, minutes(23, 59)))
    }

    @Test
    fun `a window crossing midnight wraps`() {
        val spec = "22:00-06:00"
        assertTrue(ActiveHours.isOpen(spec, minutes(22)))
        assertTrue(ActiveHours.isOpen(spec, minutes(23, 59)))
        assertTrue(ActiveHours.isOpen(spec, minutes(0)))
        assertTrue(ActiveHours.isOpen(spec, minutes(5, 59)))
        assertFalse(ActiveHours.isOpen(spec, minutes(6)))
        assertFalse(ActiveHours.isOpen(spec, minutes(12)))
    }

    @Test
    fun `a degenerate window is closed, never open`() {
        // `00:00-00:00` is the only way to say "never"; reading it as "always" would invert
        // the one publication whose whole point is to stop the fleet acting.
        val window = ActiveHours.parse("00:00-00:00")!!
        assertFalse(window.contains(minutes(0)))
        assertFalse(window.contains(minutes(12)))
        assertFalse(ActiveHours.isOpen("09:30-09:30", minutes(9, 30)))
    }

    @Test
    fun `minutes until open is zero inside the window`() {
        val window = ActiveHours.parse("08:00-22:00")!!
        assertEquals(0, window.minutesUntilOpen(minutes(12)))
    }

    @Test
    fun `minutes until open counts forward to the next opening`() {
        val window = ActiveHours.parse("08:00-22:00")!!
        assertEquals(60, window.minutesUntilOpen(minutes(7)))
        // 23:00 → 08:00 the next morning is nine hours away, not minus fifteen.
        assertEquals(9 * 60, window.minutesUntilOpen(minutes(23)))
    }

    @Test
    fun `minutes until open on a degenerate window is a whole day, never zero`() {
        // A zero-length wait would spin the connector's out-of-hours sleep.
        val window = ActiveHours.parse("00:00-00:00")!!
        assertEquals(ActiveHours.MINUTES_PER_DAY, window.minutesUntilOpen(minutes(0)))
    }

    @Test
    fun `minutes until close arms the mid-session watchdog`() {
        val window = ActiveHours.parse("08:00-22:00")!!
        assertEquals(1, window.minutesUntilClose(minutes(21, 59)))
        assertEquals(14 * 60, window.minutesUntilClose(minutes(8)))
        // Outside the window there is nothing to arm.
        assertEquals(0, window.minutesUntilClose(minutes(23)))
    }

    @Test
    fun `minutes until close wraps for an overnight window`() {
        val window = ActiveHours.parse("22:00-06:00")!!
        assertEquals(8 * 60, window.minutesUntilClose(minutes(22)))
        assertEquals(1, window.minutesUntilClose(minutes(5, 59)))
    }

    @Test
    fun `minute of day is read in the given zone`() {
        // The window is device-LOCAL wall clock, so the zone is what the arithmetic must key
        // on — the same instant is a different minute-of-day in two places, which is the
        // whole reason the platform does not evaluate this itself.
        val utc = TimeZone.getTimeZone("UTC")
        val calendar = Calendar.getInstance(utc)
        calendar.clear()
        calendar.set(2026, Calendar.AUGUST, 20, 23, 30, 0)
        val instant = calendar.timeInMillis

        assertEquals(minutes(23, 30), ActiveHours.minuteOfDay(instant, utc))
        assertEquals(minutes(2, 30), ActiveHours.minuteOfDay(instant, TimeZone.getTimeZone("Europe/Athens")))
    }
}
