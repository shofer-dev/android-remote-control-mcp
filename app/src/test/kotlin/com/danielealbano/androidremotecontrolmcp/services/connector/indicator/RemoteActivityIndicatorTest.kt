package com.danielealbano.androidremotecontrolmcp.services.connector.indicator

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The activity signal's lifecycle: lit while a command runs, held lit through the linger, and
 * off when the socket ends. The linger is what keeps the border from strobing between taps,
 * and a strobing warning is a warning holders learn to ignore.
 */
class RemoteActivityIndicatorTest {
    private fun indicator(): Pair<RemoteActivityIndicator, ActivityBorderOverlay> {
        val overlay = mockk<ActivityBorderOverlay>(relaxed = true)
        every { overlay.show() } returns Unit
        every { overlay.hide() } returns Unit
        return RemoteActivityIndicator(overlay) to overlay
    }

    @Test
    fun `starting a command lights the indicator and shows the border`() {
        val (indicator, overlay) = indicator()
        assertFalse(indicator.driving.value)
        indicator.onCommandStarted(0)
        assertTrue(indicator.driving.value)
        verify(exactly = 1) { overlay.show() }
    }

    @Test
    fun `the indicator stays lit through the linger after the last command`() {
        val (indicator, _) = indicator()
        indicator.onCommandStarted(0)
        indicator.onCommandFinished(1_000)
        indicator.tick(1_500)
        assertTrue(indicator.driving.value)
        indicator.tick(1_000 + RemoteActivityIndicator.LINGER_MILLIS - 1)
        assertTrue(indicator.driving.value)
    }

    @Test
    fun `the indicator goes out once the linger expires`() {
        val (indicator, overlay) = indicator()
        indicator.onCommandStarted(0)
        indicator.onCommandFinished(1_000)
        indicator.tick(1_000 + RemoteActivityIndicator.LINGER_MILLIS)
        assertFalse(indicator.driving.value)
        verify(exactly = 1) { overlay.hide() }
    }

    @Test
    fun `a command still in flight keeps the indicator lit past the linger`() {
        val (indicator, _) = indicator()
        indicator.onCommandStarted(0)
        indicator.tick(RemoteActivityIndicator.LINGER_MILLIS * 10)
        assertTrue(indicator.driving.value)
    }

    @Test
    fun `overlapping commands do not turn the indicator off early`() {
        // A redelivery after a reconnect can overlap a completion, which is why the counter
        // is a count rather than a boolean.
        val (indicator, _) = indicator()
        indicator.onCommandStarted(0)
        indicator.onCommandStarted(100)
        indicator.onCommandFinished(200)
        indicator.tick(200 + RemoteActivityIndicator.LINGER_MILLIS + 1)
        assertTrue(indicator.driving.value)
    }

    @Test
    fun `reset extinguishes the indicator immediately`() {
        val (indicator, overlay) = indicator()
        indicator.onCommandStarted(0)
        indicator.reset()
        assertFalse(indicator.driving.value)
        verify(exactly = 1) { overlay.hide() }
    }

    @Test
    fun `an idle indicator does not thrash the overlay`() {
        val (indicator, overlay) = indicator()
        repeat(10) { indicator.tick(it.toLong()) }
        assertFalse(indicator.driving.value)
        verify(exactly = 0) { overlay.show() }
        verify(exactly = 0) { overlay.hide() }
    }
}
