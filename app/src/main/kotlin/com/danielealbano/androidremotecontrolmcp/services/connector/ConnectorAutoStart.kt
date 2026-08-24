package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig

/**
 * The ONE rule that decides whether the platform connector ought to be running.
 *
 * Four callers ask it and none may answer it themselves, because a device that self-heals is
 * exactly a device on which every revive path agrees about what "should be running" means:
 * - [com.danielealbano.androidremotecontrolmcp.services.mcp.BootCompletedReceiver] on boot,
 * - [ConnectorEnsure] when the app comes to the foreground,
 * - [ConnectorWatchdogWorker] every 15 minutes,
 * - the connector card's Start control, which asks [shouldStart] only to know whether the start
 *   it is about to perform is redundant.
 *
 * The predicate is pure and takes the durable [ConnectorConfig] alone, so the whole matrix —
 * including the two ways it says "no" that look alike but are not — is unit-testable.
 *
 * It is deliberately the CONJUNCTION of four independent facts:
 * - [ConnectorConfig.autoStart]: the supervisor (or the holder) asked for an always-on connector.
 * - NOT [ConnectorConfig.stoppedByUser]: nobody has since vetoed it. This clause is what stops
 *   the watchdog from re-starting, fifteen minutes later, a connector a human just stopped.
 * - a dial target exists ([PlatformConnector.hasDialTarget] — a gateway URL or an edge host).
 * - a credential exists: an enrolled device id, or an unspent pairing code.
 *
 * The last two mirror the guards inside [PlatformConnector.run]: without either, the service
 * would come up, post a foreground notification, and sit in
 * [ConnectorStatus.NeedsConfig]/[ConnectorStatus.NotEnrolled] forever. That is not harmful, but a
 * permanent notification for a connector that cannot connect is a lie, so the decision is made
 * before the service starts rather than after.
 */
object ConnectorAutoStart {
    /** Whether the durable configuration says the connector ought to be running right now. */
    fun shouldRun(config: ConnectorConfig): Boolean =
        config.autoStart &&
            !config.stoppedByUser &&
            PlatformConnector.hasDialTarget(config) &&
            (config.isEnrolled || config.enrolmentCode.isNotBlank())

    /**
     * Whether a revive path should actually send a start intent: only when the connector ought to
     * be running AND is not already. Starting a running service is harmless but not free — it
     * re-enters `onStartCommand` and re-posts the foreground notification — so the ensure paths
     * ask this rather than starting unconditionally.
     */
    fun shouldStart(
        config: ConnectorConfig,
        isRunning: Boolean,
    ): Boolean = !isRunning && shouldRun(config)
}
