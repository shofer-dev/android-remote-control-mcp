package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The fixed-window command cap's accounting, driven by an explicit clock. */
class CommandRateCapTest {
    @Test
    fun `an unconfigured cap admits everything`() {
        val cap = CommandRateCap()
        repeat(1_000) { assertTrue(cap.tryConsume(it.toLong())) }
    }

    @Test
    fun `a zero command count is uncapped, not a refusal of everything`() {
        val cap = CommandRateCap()
        cap.apply(RateLimit(commands = 0, windowSeconds = 60))
        repeat(100) { assertTrue(cap.tryConsume(0)) }
    }

    @Test
    fun `a zero window is uncapped too`() {
        val cap = CommandRateCap()
        cap.apply(RateLimit(commands = 5, windowSeconds = 0))
        repeat(100) { assertTrue(cap.tryConsume(0)) }
    }

    @Test
    fun `the cap admits exactly its budget and then refuses`() {
        val cap = CommandRateCap()
        cap.apply(RateLimit(commands = 3, windowSeconds = 60))
        assertTrue(cap.tryConsume(0))
        assertTrue(cap.tryConsume(1_000))
        assertTrue(cap.tryConsume(2_000))
        assertFalse(cap.tryConsume(3_000))
    }

    @Test
    fun `a refused command is not counted, so retries do not extend the lockout`() {
        val cap = CommandRateCap()
        cap.apply(RateLimit(commands = 1, windowSeconds = 60))
        assertTrue(cap.tryConsume(0))
        // Hammer it while refused; the window must still roll over on schedule.
        repeat(50) { assertFalse(cap.tryConsume(1_000)) }
        assertTrue(cap.tryConsume(60_000))
    }

    @Test
    fun `the window rolls over and the budget is restored`() {
        val cap = CommandRateCap()
        cap.apply(RateLimit(commands = 2, windowSeconds = 10))
        assertTrue(cap.tryConsume(0))
        assertTrue(cap.tryConsume(500))
        assertFalse(cap.tryConsume(9_999))
        assertTrue(cap.tryConsume(10_000))
        assertTrue(cap.tryConsume(10_001))
        assertFalse(cap.tryConsume(10_002))
    }

    @Test
    fun `applying a policy resets the accounting`() {
        // A tightened cap must bite immediately rather than inherit a window already spent
        // under the old one — or a re-publication would be a no-op until the window rolled.
        val cap = CommandRateCap()
        cap.apply(RateLimit(commands = 2, windowSeconds = 60))
        assertTrue(cap.tryConsume(0))
        assertTrue(cap.tryConsume(0))
        assertFalse(cap.tryConsume(0))

        cap.apply(RateLimit(commands = 2, windowSeconds = 60))
        assertTrue(cap.tryConsume(0))
    }

    @Test
    fun `time until the window resets is reported for the refusal message`() {
        val cap = CommandRateCap()
        cap.apply(RateLimit(commands = 1, windowSeconds = 60))
        assertEquals(0L, cap.millisUntilWindowReset(0))
        cap.tryConsume(1_000)
        assertEquals(60_000L, cap.millisUntilWindowReset(1_000))
        assertEquals(30_000L, cap.millisUntilWindowReset(31_000))
        assertEquals(0L, cap.millisUntilWindowReset(120_000))
    }
}
