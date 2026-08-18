package com.danielealbano.androidremotecontrolmcp.services.connector

/**
 * Observable state of the platform connector, collected by the UI and used to drive the
 * foreground notification text. The terminal-until-reconfigured states ([UpgradeRequired],
 * [EnrolmentRejected], [AttachRejected], [TermsDeclined]) stop the reconnect loop deliberately —
 * retrying on those codes loops forever (wire spec §5, §7).
 */
sealed interface ConnectorStatus {
    /** No edge host / no way to connect yet — waiting for configuration. */
    data object NeedsConfig : ConnectorStatus

    /** Dialing the edge / performing the handshake. */
    data object Connecting : ConnectorStatus

    /** In the enrol ceremony (enroll → terms → accept → enrolled). */
    data object Enrolling : ConnectorStatus

    /** Waiting for the user to accept the enrolment terms (real user action required). */
    data object AwaitingTermsConsent : ConnectorStatus

    /** In the attach challenge–response (attach → challenge → attach_sig → attached). */
    data object Attaching : ConnectorStatus

    /** Attached and serving relay commands. */
    data object Connected : ConnectorStatus

    /** Socket dropped; backing off before the next reconnect. */
    data object Reconnecting : ConnectorStatus

    /** `upgrade-required`: the app version is below the platform minimum. Do not retry. */
    data object UpgradeRequired : ConnectorStatus

    /** `unauthorized` at enrol: the pairing code was refused. A fresh code is required. */
    data class EnrolmentRejected(
        val details: String?,
    ) : ConnectorStatus

    /** `unauthorized` at attach: revoked device or bad signature. */
    data class AttachRejected(
        val details: String?,
    ) : ConnectorStatus

    /**
     * `terms-required` at attach — the platform republished terms and the device must re-consent
     * (Gap B). The connector presents the fresh terms and, on acceptance, RE-ATTACHES carrying
     * the accepted hash in `attach_sig` (no pairing code needed). This state is entered while the
     * user decides.
     */
    data object ReConsenting : ConnectorStatus

    /**
     * The user DECLINED the republished terms at re-consent. Terminal until reconfigured — the
     * device cannot attach without accepting the current terms (wire spec §3.9 order 6).
     */
    data class TermsDeclined(
        val details: String?,
    ) : ConnectorStatus

    /** The connector has been stopped. */
    data object Stopped : ConnectorStatus
}
