package com.danielealbano.androidremotecontrolmcp.services.connector

/**
 * Observable state of the platform connector, collected by the UI and used to drive the
 * foreground notification text. The terminal-until-reconfigured states ([UpgradeRequired],
 * [EnrolmentRejected], [AttachRejected], [TermsDeclined], [Misconfigured]) stop the reconnect loop
 * deliberately — retrying on those codes loops forever (wire spec §5, §7).
 *
 * Each state carries its own short [notificationLabel] so the foreground-notification text is a
 * single property read rather than an ever-growing `when` over every variant.
 */
sealed interface ConnectorStatus {
    /** Short human-readable label for the foreground notification / UI. */
    val notificationLabel: String

    /** No gateway URL / edge host / no way to connect yet — waiting for configuration. */
    data object NeedsConfig : ConnectorStatus {
        override val notificationLabel = "Not configured"
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
    data object Connected : ConnectorStatus {
        override val notificationLabel = "Connected"
    }

    /**
     * Attached, with remote driving PAUSED by the platform (`android-use` / `android-manage`).
     * The socket stays up — presence, heartbeats and the management actions all still work —
     * and every relayed command is refused on-device with `policy-paused`.
     */
    data object Paused : ConnectorStatus {
        override val notificationLabel = "Remote driving paused"
    }

    /**
     * DETACHED because the platform's active-hours window is closed. Not a failure and not a
     * backoff: the connector holds no socket at all until the window reopens, which is what
     * makes "the phone did not act at 3am" a guarantee rather than a server-side promise.
     */
    data object OutsideActiveHours : ConnectorStatus {
        override val notificationLabel = "Outside active hours"
    }

    /** Socket dropped; backing off before the next reconnect. */
    data object Reconnecting : ConnectorStatus {
        override val notificationLabel = "Reconnecting…"
    }

    /** `upgrade-required`: the app version is below the platform minimum. Do not retry. */
    data object UpgradeRequired : ConnectorStatus {
        override val notificationLabel = "Update required"
    }

    /** `unauthorized` at enrol: the pairing code was refused. A fresh code is required. */
    data class EnrolmentRejected(
        val details: String?,
    ) : ConnectorStatus {
        override val notificationLabel = "Enrolment rejected"
    }

    /** `unauthorized` at attach: revoked device or bad signature. */
    data class AttachRejected(
        val details: String?,
    ) : ConnectorStatus {
        override val notificationLabel = "Attach rejected"
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
    ) : ConnectorStatus {
        override val notificationLabel = "Terms declined"
    }

    /**
     * The resolved dial target is not a usable WebSocket URL — a configured `gatewayUrl` whose
     * scheme is neither `ws://` nor `wss://`. Terminal until reconfigured: retrying cannot repair
     * a malformed URL, only a fresh configuration can.
     */
    data class Misconfigured(
        val details: String?,
    ) : ConnectorStatus {
        override val notificationLabel = "Misconfigured"
    }

    /** The connector has been stopped. */
    data object Stopped : ConnectorStatus {
        override val notificationLabel = "Stopped"
    }
}
