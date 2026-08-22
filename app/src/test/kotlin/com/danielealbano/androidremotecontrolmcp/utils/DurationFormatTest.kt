package com.danielealbano.androidremotecontrolmcp.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DurationFormat")
class DurationFormatTest {
    @Test
    fun `sub-second spans read as zero seconds`() {
        assertEquals("0s", DurationFormat.short(0))
        assertEquals("0s", DurationFormat.short(999))
    }

    @Test
    fun `seconds are shown alone`() {
        assertEquals("3s", DurationFormat.short(3_000))
        assertEquals("59s", DurationFormat.short(59_999))
    }

    @Test
    fun `minutes carry their seconds`() {
        assertEquals("1m 0s", DurationFormat.short(60_000))
        assertEquals("2m 5s", DurationFormat.short(125_000))
    }

    @Test
    fun `hours drop the seconds and pad the minutes`() {
        assertEquals("1h 00m", DurationFormat.short(3_600_000))
        assertEquals("1h 02m", DurationFormat.short(3_725_000))
        assertEquals("3h 45m", DurationFormat.short(13_500_000))
    }

    @Test
    fun `days drop the minutes`() {
        assertEquals("1d 0h", DurationFormat.short(86_400_000))
        assertEquals("2d 4h", DurationFormat.short(187_200_000))
    }

    @Test
    fun `a clock that moved backwards reads as zero, never as a negative age`() {
        assertEquals("0s", DurationFormat.short(-5_000))
    }
}
