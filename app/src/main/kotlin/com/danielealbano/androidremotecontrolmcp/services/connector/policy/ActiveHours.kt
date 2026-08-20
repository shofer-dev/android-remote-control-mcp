package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import java.util.Calendar
import java.util.TimeZone

/**
 * The active-hours window: pure arithmetic over a `"HH:MM-HH:MM"` string and a wall-clock
 * minute-of-day, with no Android and no clock of its own so every branch is unit-testable.
 *
 * Three properties are load-bearing:
 *
 * - **An unparseable or empty window is ALWAYS OPEN.** The platform validates the spelling at
 *   write time (settings-manager refuses anything but `HH:MM-HH:MM`), so a malformed value
 *   here means the platform sent something this app does not understand — and refusing every
 *   command on a string we failed to parse would brick a fleet over a format change. The
 *   direction of that failure is chosen deliberately, and it is the same one the platform
 *   takes for the APK floor and for an unpublished window.
 * - **A window may cross midnight.** `22:00-06:00` is a real overnight window and is read as
 *   "at or after 22:00, OR before 06:00". `08:00-22:00` is the ordinary daytime one. The test
 *   is `start < end` ? in-range : crosses-midnight.
 * - **A degenerate window (`start == end`) is CLOSED, not open.** `00:00-00:00` is the only
 *   way to say "never", and reading it as "always" would silently invert the one publication
 *   whose whole purpose is to stop the fleet acting.
 */
object ActiveHours {
    /** Minutes in an hour. */
    const val MINUTES_PER_HOUR: Int = 60

    /** Hours in a day. */
    const val HOURS_PER_DAY: Int = 24

    /** Minutes in a day, the modulus every calculation here works in. */
    const val MINUTES_PER_DAY: Int = HOURS_PER_DAY * MINUTES_PER_HOUR

    /** A window is exactly two `HH:MM` halves, and each half exactly two components. */
    private const val PARTS_PER_WINDOW = 2
    private const val PARTS_PER_TIME = 2

    /** Sentinel for an unparseable time component; out of every valid range by construction. */
    private const val NOT_A_TIME = -1

    /**
     * A parsed window. [startMinute] and [endMinute] are minutes since local midnight.
     * [crossesMidnight] is true when the window wraps (start after end).
     */
    data class Window(
        val startMinute: Int,
        val endMinute: Int,
    ) {
        val crossesMidnight: Boolean get() = startMinute > endMinute

        /** True when [minuteOfDay] falls inside the window. Degenerate windows are closed. */
        fun contains(minuteOfDay: Int): Boolean =
            when {
                startMinute == endMinute -> false
                crossesMidnight -> minuteOfDay >= startMinute || minuteOfDay < endMinute
                else -> minuteOfDay >= startMinute && minuteOfDay < endMinute
            }

        /**
         * Minutes from [minuteOfDay] until the window next opens, or 0 when it is already
         * open. Used to schedule the reconnect after an out-of-hours detach so the connector
         * sleeps the whole closed stretch rather than retrying on a backoff that would dial,
         * be refused and back off again all night.
         */
        fun minutesUntilOpen(minuteOfDay: Int): Int {
            if (contains(minuteOfDay)) return 0
            val delta = (startMinute - minuteOfDay + MINUTES_PER_DAY) % MINUTES_PER_DAY
            // A degenerate window never opens; report a full day so the caller retries
            // tomorrow instead of spinning on a zero-length wait.
            return if (startMinute == endMinute) MINUTES_PER_DAY else delta
        }

        /**
         * Minutes from [minuteOfDay] until the window next closes, or 0 when it is already
         * closed. Used to arm the mid-session watchdog: a socket attached at 21:59 under an
         * `08:00-22:00` window must detach itself one minute later, without a command
         * arriving to trigger the check.
         */
        fun minutesUntilClose(minuteOfDay: Int): Int {
            if (!contains(minuteOfDay)) return 0
            val delta = (endMinute - minuteOfDay + MINUTES_PER_DAY) % MINUTES_PER_DAY
            return if (delta == 0) MINUTES_PER_DAY else delta
        }
    }

    /**
     * Parses `"HH:MM-HH:MM"`. Returns null for an empty string (always open) and for anything
     * this app cannot read — the caller treats both as "no window", per the class docs.
     */
    fun parse(spec: String): Window? {
        // An empty spec falls out of this: "".split('-') is a single element, so it fails the
        // arity check and answers null — which the caller reads as "always open".
        val halves = spec.trim().split('-')
        if (halves.size != PARTS_PER_WINDOW) return null
        val start = parseHhMm(halves[0])
        val end = parseHhMm(halves[1])
        return if (start != null && end != null) Window(start, end) else null
    }

    /** Parses `"HH:MM"` into minutes since midnight, or null if it is not a valid time. */
    private fun parseHhMm(value: String): Int? {
        val parts = value.trim().split(':')
        if (parts.size != PARTS_PER_TIME) return null
        // -1 stands for "not a number" and is out of every valid range, so an unparseable
        // component is rejected by the same bounds check as an out-of-range one.
        val hour = parts[0].trim().toIntOrNull() ?: NOT_A_TIME
        val minute = parts[1].trim().toIntOrNull() ?: NOT_A_TIME
        val valid = hour in 0 until HOURS_PER_DAY && minute in 0 until MINUTES_PER_HOUR
        return if (valid) hour * MINUTES_PER_HOUR + minute else null
    }

    /**
     * True when [spec] admits [minuteOfDay]. An empty or unparseable spec is always open.
     * This is the single entry point the enforcer uses, so the "unparseable is open" rule
     * cannot be forgotten at one call site and applied at another.
     */
    fun isOpen(
        spec: String,
        minuteOfDay: Int,
    ): Boolean = parse(spec)?.contains(minuteOfDay) ?: true

    /**
     * The local minute-of-day for [epochMillis] in [zone]. Uses [Calendar] rather than
     * `java.time` so the arithmetic is identical on every API level this app supports without
     * desugaring assumptions.
     */
    fun minuteOfDay(
        epochMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Int {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = epochMillis
        return calendar.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR + calendar.get(Calendar.MINUTE)
    }
}
