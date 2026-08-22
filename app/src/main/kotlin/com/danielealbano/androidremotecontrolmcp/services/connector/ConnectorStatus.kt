package com.danielealbano.androidremotecontrolmcp.services.connector

/**
 * Observable state of the platform connector, collected by the UI and used to drive the
 * foreground notification text.
 *
 * The vocabulary is deliberately SERVER-GROUNDED: [Connected] is not "the socket object exists",
 * it is "the gateway answered our attach AND has answered a heartbeat recently". The two facts
 * that make that checkable — [Attached.attachedSinceMillis] and
 * [Attached.lastServerHeartbeatMillis] — ride on the state itself, so any consumer can apply
 * [ConnectorLiveness.ground] and refuse to keep saying "connected" over a half-open socket.
 *
 * The terminal-until-reconfigured states implement [Halted]: they stop the reconnect loop
 * deliberately, because retrying on those codes loops forever (wire spec §5, §7). Each carries a
 * [Halted.reason] so the notification and the status card can say WHY without a `when` over every
 * variant.
 *
 * Each state also carries a short [notificationLabel] for the same reason.
 */
sealed interface ConnectorStatus {
    /** Short human-readable label for the foreground notification / UI. */
    val notificationLabel: String

    /**
     * A state in which the gateway has confirmed the attach — the `attached` frame arrived — and
     * the connector is holding the socket. Both timestamps are on the connector's monotonic
     * clock (`SystemClock.elapsedRealtime`), so they survive a wall-clock jump.
     *
     * [lastServerHeartbeatMillis] is the last moment the SERVER proved it still holds this
     * socket: the `attached` frame itself, then every `pong` it answers our `ping` with. It is
     * what makes "connected" falsifiable rather than a local belief.
     */
    sealed interface Attached : ConnectorStatus {
        val attachedSinceMillis: Long
        val lastServerHeartbeatMillis: Long

        /** The same state with a fresher server heartbeat. */
        fun withHeartbeat(millis: Long): Attached
    }

    /**
     * A state that stops the reconnect loop until the app is reconfigured. Retrying cannot repair
     * any of these — only fresh configuration or a fresh consent can.
     */
    sealed interface Halted : ConnectorStatus {
        /** Why the loop stopped, in a sentence a holder can act on. */
        val reason: String
    }

    /** No gateway URL / edge host / no way to connect yet — waiting for configuration. */
    data object NeedsConfig : ConnectorStatus {
        override val notificationLabel = "Not configured"
    }

    /**
     * A dial target IS configured, but the device holds neither a durable identity nor a pairing
     * code, so there is nothing to enrol or attach with. Distinct from [NeedsConfig] because the
     * remedy is different: a pairing code, not a host.
     */
    data object NotEnrolled : ConnectorStatus {
        override val notificationLabel = "Not enrolled"
    }

    /** Dialing the gateway / performing the handshake. */
    data object Connecting : ConnectorStatus {
        override val notificationLabel = "Connecting…"
    }

    /** In the enrol ceremony (enroll → terms → accept → enrolled). */
    data object Enrolling : ConnectorStatus {
        override val notificationLabel = "Enrolling…"
    }

    /** Waiting for the user to accept the enrolment terms (real user action required). */
    data object AwaitingTermsConsent : ConnectorStatus {
        override val notificationLabel = "Waiting for terms acceptance"
    }

    /** In the attach challenge–response (attach → challenge → attach_sig → attached). */
    data object Attaching : ConnectorStatus {
        override val notificationLabel = "Attaching…"
    }

    /** Attached and serving relay commands. */
    data class Connected(
        override val attachedSinceMillis: Long,
        override val lastServerHeartbeatMillis: Long,
    ) : Attached {
        override val notificationLabel = "Connected"

        override fun withHeartbeat(millis: Long): Attached = copy(lastServerHeartbeatMillis = millis)
    }

    /**
     * Attached, with remote driving PAUSED by the platform (`android-use` / `android-manage`).
     * The socket stays up — presence, heartbeats and the management actions all still work —
     * and every relayed command is refused on-device with `policy-paused`.
     */
    data class Paused(
        override val attachedSinceMillis: Long,
        override val lastServerHeartbeatMillis: Long,
    ) : Attached {
        override val notificationLabel = "Remote driving paused"

        override fun withHeartbeat(millis: Long): Attached = copy(lastServerHeartbeatMillis = millis)
    }

    /**
     * DETACHED because the platform's active-hours window is closed. Not a failure and not a
     * backoff: the connector holds no socket at all until the window reopens, which is what
     * makes "the phone did not act at 3am" a guarantee rather than a server-side promise.
     *
     * [reopensAtMillis] is on the monotonic clock, so the UI can count down to it.
     */
    data class OutsideActiveHours(
        val reopensAtMillis: Long,
    ) : ConnectorStatus {
        override val notificationLabel = "Outside active hours"
    }

    /**
     * Socket dropped — or the gateway stopped answering heartbeats — and the connector is backing
     * off before the next dial. [nextRetryAtMillis] is on the monotonic clock.
     */
    data class Reconnecting(
        val nextRetryAtMillis: Long,
    ) : ConnectorStatus {
        override val notificationLabel = "Reconnecting…"
    }

    /** `upgrade-required`: the app version is below the platform minimum. Do not retry. */
    data object UpgradeRequired : Halted {
        override val notificationLabel = "Update required"
        override val reason = "this app is older than the platform minimum; update it to reconnect"
    }

    /** `unauthorized` at enrol: the pairing code was refused. A fresh code is required. */
    data class EnrolmentRejected(
        val details: String?,
    ) : Halted {
        override val notificationLabel = "Enrolment rejected"
        override val reason = details ?: "the pairing code was refused; a fresh code is required"
    }

    /** `unauthorized` at attach: revoked device or bad signature. */
    data class AttachRejected(
        val details: String?,
    ) : Halted {
        override val notificationLabel = "Attach rejected"
        override val reason = details ?: "the platform refused this device; it may have been revoked"
    }

    /**
     * `terms-required` at attach — the platform republished terms and the device must re-consent
     * (Gap B). The connector presents the fresh terms and, on acceptance, RE-ATTACHES carrying
     * the accepted hash in `attach_sig` (no pairing code needed). This state is entered while the
     * user decides.
     */
    data object ReConsenting : ConnectorStatus {
        override val notificationLabel = "Waiting for terms re-acceptance"
    }

    /**
     * The user DECLINED the republished terms at re-consent. Terminal until reconfigured — the
     * device cannot attach without accepting the current terms (wire spec §3.9 order 6).
     */
    data class TermsDeclined(
        val details: String?,
    ) : Halted {
        override val notificationLabel = "Terms declined"
        override val reason = details ?: "the current terms were declined; accept them to reconnect"
    }

    /**
     * The resolved dial target is not a usable WebSocket URL — a configured `gatewayUrl` whose
     * scheme is neither `ws://` nor `wss://`. Terminal until reconfigured: retrying cannot repair
     * a malformed URL, only a fresh configuration can.
     */
    data class Misconfigured(
        val details: String?,
    ) : Halted {
        override val notificationLabel = "Misconfigured"
        override val reason = details ?: "the configured gateway address cannot be dialled"
    }

    /** The connector has been stopped. */
    data object Stopped : ConnectorStatus {
        override val notificationLabel = "Stopped"
    }
}
