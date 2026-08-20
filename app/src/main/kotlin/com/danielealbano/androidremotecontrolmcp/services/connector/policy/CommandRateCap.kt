package com.danielealbano.androidremotecontrolmcp.services.connector.policy

/**
 * The command rate cap: a FIXED-window counter, with the clock passed in so the accounting is
 * pure and testable to the millisecond.
 *
 * Fixed window rather than sliding, deliberately. The cap exists to bound a runaway or
 * prompt-injected agent, not to shape traffic: an operator run taps at human pace (single
 * digits per minute), so the only thing a sliding window would buy is smoother behaviour at
 * the boundary for a caller that is already three orders of magnitude inside the cap. What it
 * would cost is a per-command timestamp ring on the hot path of every device command, and a
 * refusal whose reason ("you were fast 40 seconds ago") is far harder for an operator reading
 * the audit trail to reconstruct than "the window that started at T was spent".
 *
 * [RateLimit.commands] `== 0` is UNCAPPED and is checked first, so a platform that configures
 * no cap costs nothing at all here.
 */
class CommandRateCap {
    /**
     * Whether a window is open. An explicit flag rather than a `windowStartMillis == 0`
     * sentinel: epoch 0 is a perfectly ordinary clock reading (it is what a device with no
     * time source reports, and what a test passes), and treating it as "no window" makes the
     * cap silently uncountable — every call would open a fresh window and the cap would admit
     * everything while looking configured.
     */
    private var windowOpen: Boolean = false
    private var windowStartMillis: Long = 0
    private var countInWindow: Int = 0
    private var appliedLimit: RateLimit = RateLimit()

    /**
     * Installs [limit] and resets the accounting. Called whenever a policy snapshot arrives:
     * a fresh cap starts a fresh window, so tightening the cap takes effect at once instead
     * of inheriting a window already spent under the old one.
     */
    @Synchronized
    fun apply(limit: RateLimit) {
        appliedLimit = limit
        windowOpen = false
        windowStartMillis = 0
        countInWindow = 0
    }

    /**
     * Charges one command against the cap at [nowMillis]. Returns true when the command may
     * proceed; false when the window is spent. A refused command is NOT counted — the cap
     * bounds what the device executed, and counting refusals would extend the lockout every
     * time a caller retried.
     */
    @Synchronized
    fun tryConsume(nowMillis: Long): Boolean {
        val limit = appliedLimit
        if (limit.commands <= 0 || limit.windowSeconds <= 0) return true

        val windowMillis = limit.windowSeconds.toLong() * MILLIS_PER_SECOND
        if (!windowOpen || nowMillis - windowStartMillis >= windowMillis) {
            windowOpen = true
            windowStartMillis = nowMillis
            countInWindow = 0
        }
        val admitted = countInWindow < limit.commands
        if (admitted) countInWindow++
        return admitted
    }

    /**
     * Milliseconds until the current window rolls over, for the refusal's details string. 0
     * when uncapped or when no window has started.
     */
    @Synchronized
    fun millisUntilWindowReset(nowMillis: Long): Long {
        val limit = appliedLimit
        if (limit.commands <= 0 || limit.windowSeconds <= 0 || !windowOpen) return 0
        val windowMillis = limit.windowSeconds.toLong() * MILLIS_PER_SECOND
        val elapsed = nowMillis - windowStartMillis
        return (windowMillis - elapsed).coerceAtLeast(0)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
