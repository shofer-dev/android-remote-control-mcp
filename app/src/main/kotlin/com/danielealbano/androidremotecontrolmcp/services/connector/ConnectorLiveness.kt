package com.danielealbano.androidremotecontrolmcp.services.connector

/**
 * The rule that decides whether an attached link may still be REPORTED as connected.
 *
 * The gateway does not probe the device: on `/ws/device` the DEVICE sends `{"type":"ping"}` and
 * the gateway answers `{"type":"pong"}`, and any frame from an attached device refreshes the
 * server's own last-seen (`hub.Seen`). The server closes a socket whose last-seen has lapsed
 * past its `HEARTBEAT_LAPSE` (90s by default). So the `pong` is the only thing that proves the
 * PLATFORM still holds this socket — a TCP connection object that nobody has torn down proves
 * nothing, and a half-open socket looks identical to a healthy one from the client side.
 *
 * [HEARTBEAT_INTERVAL_MS] is the connector's ping cadence and [STALE_AFTER_MS] the tolerance:
 * two whole intervals plus a grace, so a single dropped or delayed answer never flaps the status.
 * It stays comfortably inside the server's 90s lapse, which is what keeps the two ends agreeing
 * about when this device went away — the device gives up on the link before the platform does,
 * rather than after.
 */
object ConnectorLiveness {
    /** How often the connector sends `ping`. */
    const val HEARTBEAT_INTERVAL_MS = 30_000L

    /** Slack on top of the two intervals, absorbing scheduling and radio latency. */
    const val HEARTBEAT_GRACE_MS = 5_000L

    /** No server answer for this long and the link is treated as lost. */
    const val STALE_AFTER_MS = 2 * HEARTBEAT_INTERVAL_MS + HEARTBEAT_GRACE_MS

    /** True when the gateway has not answered since [lastServerHeartbeatMillis]. */
    fun isStale(
        lastServerHeartbeatMillis: Long,
        nowMillis: Long,
    ): Boolean = nowMillis - lastServerHeartbeatMillis >= STALE_AFTER_MS

    /**
     * Downgrades a status the connector BELIEVES is attached to [ConnectorStatus.Reconnecting]
     * once the gateway has stopped answering. Every other state is returned unchanged — a halt
     * reason, a consent prompt or a closed active-hours window is not a liveness question and
     * must survive this pass verbatim.
     *
     * Consumers apply this on their own clock tick rather than trusting the last value they were
     * handed, because a status flow retains its last value even when whatever published it is no
     * longer running.
     */
    fun ground(
        status: ConnectorStatus,
        nowMillis: Long,
    ): ConnectorStatus =
        if (status is ConnectorStatus.Attached && isStale(status.lastServerHeartbeatMillis, nowMillis)) {
            ConnectorStatus.Reconnecting(nextRetryAtMillis = nowMillis)
        } else {
            status
        }
}
