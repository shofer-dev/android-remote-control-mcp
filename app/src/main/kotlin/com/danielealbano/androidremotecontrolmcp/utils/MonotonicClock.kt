package com.danielealbano.androidremotecontrolmcp.utils

/**
 * A monotonic millisecond time source, behind an interface so anything that measures an ELAPSED
 * interval — how long a link has been attached, how long since the platform last answered — is
 * unit-testable with a fake clock instead of a real delay.
 *
 * Implementations must be monotonic (`SystemClock.elapsedRealtime`, not `System.currentTimeMillis`):
 * these durations must survive an NTP correction or a user changing the device's clock, both of
 * which would otherwise make a healthy link look hours stale.
 */
fun interface MonotonicClock {
    fun nowMillis(): Long
}
