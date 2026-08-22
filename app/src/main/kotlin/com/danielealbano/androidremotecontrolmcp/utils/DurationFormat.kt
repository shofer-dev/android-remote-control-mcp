package com.danielealbano.androidremotecontrolmcp.utils

/**
 * Renders an elapsed millisecond span as the shortest string that still reads unambiguously —
 * `4s`, `2m 5s`, `3h 07m`, `2d 4h`.
 *
 * Two units at most, and the smaller one is dropped once the larger makes it noise: nobody
 * reading "attached for 2d 4h" needs the seconds, and a card that re-renders every second must
 * not redraw a digit that carries no information.
 */
object DurationFormat {
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L

    /** Below this a minutes value needs a leading zero to keep `3h 07m` aligned. */
    private const val TWO_DIGIT_FLOOR = 10L

    /** Formats a non-negative span; a negative input (a clock that moved) reads as `0s`. */
    fun short(millis: Long): String {
        val totalSeconds = (millis / MILLIS_PER_SECOND).coerceAtLeast(0)
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
        val minutes = totalMinutes % MINUTES_PER_HOUR
        val totalHours = totalMinutes / MINUTES_PER_HOUR
        val hours = totalHours % HOURS_PER_DAY
        val days = totalHours / HOURS_PER_DAY

        return when {
            days > 0 -> "${days}d ${hours}h"
            totalHours > 0 -> "${totalHours}h ${pad(minutes)}m"
            totalMinutes > 0 -> "${totalMinutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun pad(value: Long): String = if (value < TWO_DIGIT_FLOOR) "0$value" else "$value"
}
