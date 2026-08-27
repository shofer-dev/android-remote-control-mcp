package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorEnsure
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorLiveness
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorProvisioning
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorStatus
import com.danielealbano.androidremotecontrolmcp.services.connector.PairingInput
import com.danielealbano.androidremotecontrolmcp.services.connector.PlatformConnectorService
import com.danielealbano.androidremotecontrolmcp.utils.MonotonicClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the "Platform Connector" card renders. Everything is derived — the ViewModel holds no
 * connector state of its own, because the connector is the authority and this is a projection
 * of it joined with the durable configuration.
 *
 * [lastServerHeartbeatAgoMillis] and [attachUptimeMillis] are ages rather than timestamps so the
 * card stays a dumb renderer, and they are computed from the RAW status: a link whose heartbeat
 * has just lapsed shows "Reconnecting" and still reports how long the platform has been silent,
 * which is the number that explains the state.
 *
 * [countdownMillis] is the remaining wait a waiting state carries — the backoff before the next
 * dial, or the closed active-hours window before it reopens — and is null for every state that
 * is not waiting on a deadline.
 *
 * [stoppedByUser] is the durable veto, and the card shows it because a device that is down on
 * purpose looks identical to one that is down by accident unless something says so.
 */
data class ConnectorUiState(
    val status: ConnectorStatus = ConnectorStatus.Stopped,
    val deviceIdShort: String = "",
    val edgeHost: String = "",
    val isEnrolled: Boolean = false,
    val lastServerHeartbeatAgoMillis: Long? = null,
    val attachUptimeMillis: Long? = null,
    val countdownMillis: Long? = null,
    val stoppedByUser: Boolean = false,
) {
    /**
     * Whether the connector service is up, which is what decides whether the card offers Start or
     * Stop. [ConnectorStatus.Stopped] is published only by the service's `onDestroy` and as the
     * flow's initial value, so it means precisely "no connector service in this process" — every
     * other state, halted ones included, belongs to a service that is running.
     */
    val isRunning: Boolean get() = status !is ConnectorStatus.Stopped
}

/**
 * How far a holder's pairing attempt has got, as the CONNECTOR sees it.
 *
 * There is no second notion of progress here: every value below is derived from the connector's
 * own [ConnectorStatus] and the durable configuration. A pairing screen that tracked its own
 * "submitting…" flag would be reporting that a DataStore write returned, which is never the
 * question the holder is asking.
 */
sealed interface PairingProgress {
    /** No attempt in flight — the form is what the holder should see. */
    data object Idle : PairingProgress

    /**
     * The pairing has been written and the connector has not yet reacted to it. It is a state of
     * its own rather than a spinner labelled with whatever the connector last said, because the
     * status at that instant still belongs to the PREVIOUS attempt — including its refusal.
     */
    data object Starting : PairingProgress

    /** The connector is dialling, enrolling or attaching; [label] is its own word for where it is. */
    data class InProgress(
        val label: String,
    ) : PairingProgress

    /** Enrolled and attached: the phone is a platform device. */
    data object Paired : PairingProgress

    /**
     * The attempt failed. [reason] is the platform's own refusal — an expired code and a spent one
     * are different sentences, and a holder sent back to the console for a fresh code they did not
     * need has been failed twice.
     */
    data class Refused(
        val reason: String?,
    ) : PairingProgress
}

/**
 * Feeds the connector status card.
 *
 * Three inputs are joined: the connector's own status flow, the durable [ConnectorConfig], and a
 * one-second ticker. The ticker is what makes "3s ago" tick, and it is also what re-applies
 * [ConnectorLiveness.ground] — the card must not go on saying "Connected" off a value it was
 * handed before the platform went quiet, and a [StateFlow] retains its last value even when the
 * service that published it is no longer running.
 */
@HiltViewModel
class ConnectorViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val connectorEnsure: ConnectorEnsure,
        private val provisioning: ConnectorProvisioning,
        private val clock: MonotonicClock,
    ) : ViewModel() {
        private val ticks: Flow<Long> =
            flow {
                while (true) {
                    emit(clock.nowMillis())
                    delay(TICK_INTERVAL_MS)
                }
            }

        val uiState: StateFlow<ConnectorUiState> =
            combine(
                PlatformConnectorService.status,
                settingsRepository.connectorConfig,
                ticks,
            ) { status, config, now ->
                buildState(status, config, now)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), ConnectorUiState())

        /**
         * Whether to show the one-time "keep the connector alive" hint (OEM autostart and battery
         * optimisation). Enrolment is the moment it becomes relevant — before that the device has
         * no connector to keep alive — and dismissing it is remembered for good.
         */
        val keepAliveHintVisible: StateFlow<Boolean> =
            combine(
                settingsRepository.connectorConfig,
                settingsRepository.connectorKeepAliveHintDismissed,
            ) { config, dismissed ->
                config.isEnrolled && !dismissed
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), false)

        /**
         * When the holder's current pairing attempt was submitted, on the same monotonic clock the
         * rest of this ViewModel uses; null when there is no attempt in flight.
         */
        private val pairingStartedAtMillis = MutableStateFlow<Long?>(null)

        /**
         * How the current pairing attempt is going, projected from the connector's own status.
         *
         * The durable configuration is in the join because "paired" is two facts, not one: the
         * device holds an identity AND the platform is answering on it. Either alone is a state
         * that can go backwards.
         */
        val pairingProgress: StateFlow<PairingProgress> =
            combine(
                PlatformConnectorService.status,
                settingsRepository.connectorConfig,
                pairingStartedAtMillis,
                ticks,
            ) { status, config, startedAt, now ->
                buildPairingProgress(status, config.isEnrolled, startedAt, now)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), PairingProgress.Idle)

        /**
         * Applies a pairing the holder scanned or typed: normalise, validate, write, start.
         *
         * The input is re-normalised and re-validated HERE even though the surface that collected
         * it already did — this is the last place before something unusable is made durable, and
         * the scanner and the form are two callers, not one.
         *
         * The write and the start are both needed, and neither is redundant. The write is what a
         * running connector is waiting for: its loop parks in `awaitConfigChange` in exactly the
         * states this surface is offered from, so the pairing lands and it dials, with no Start to
         * press. The start is for the other case — a freshly installed app whose connector service
         * has never run at all, where there is no loop to wake. [ConnectorEnsure.start] is
         * idempotent, so the first case simply reports that the service is already up.
         */
        fun pair(
            hostInput: String,
            codeInput: String,
        ) {
            val host = PairingInput.normaliseHost(hostInput)
            val code = PairingInput.normaliseCode(codeInput)
            if (PairingInput.validate(host, code) != null) return
            pairingStartedAtMillis.value = clock.nowMillis()
            viewModelScope.launch {
                settingsRepository.updateConnectorPairing(host, code)
                connectorEnsure.start()
            }
        }

        /**
         * Forgets the current attempt so the surface returns to its form, WITHOUT touching the
         * configuration: a holder who closes the dialog while the phone is enrolling has changed
         * their mind about watching, not about pairing.
         */
        fun cancelPairing() {
            pairingStartedAtMillis.value = null
        }

        /** The card's Start control. The rules — clearing the stop veto, scheduling the watchdog,
         * not re-starting a running service — live in [ConnectorEnsure], shared with the revive
         * paths; this is only the intent. */
        fun start() {
            viewModelScope.launch { connectorEnsure.start() }
        }

        /** The card's Stop control: an explicit stop that the revive paths must respect. */
        fun stop() {
            viewModelScope.launch { connectorEnsure.stop() }
        }

        /**
         * The card's Unprovision control: forget this phone's platform identity so it can be
         * provisioned again.
         *
         * It is a LOCAL act with no platform round-trip, which is exactly why it exists — the
         * recovery it serves is a handset whose platform record is already gone, so there is
         * nothing to ask and nobody to ask. The connector is deliberately NOT stopped: without an
         * identity it settles into [ConnectorStatus.NotEnrolled] and waits, which means a fresh
         * pairing code provisions the phone the instant it arrives instead of after someone
         * presses Start. Ending the connection the cleared identity belonged to is the connector's
         * own job (its identity watch), not this method's.
         */
        fun unprovision() {
            viewModelScope.launch { provisioning.unprovision() }
        }

        /** Remembers that the holder dismissed the keep-alive hint. */
        fun dismissKeepAliveHint() {
            viewModelScope.launch { settingsRepository.dismissConnectorKeepAliveHint() }
        }

        companion object {
            private const val TICK_INTERVAL_MS = 1_000L
            private const val FLOW_TIMEOUT_MS = 5_000L

            /** How much of the device UUID the card shows; enough to tell two devices apart. */
            private const val DEVICE_ID_PREFIX_LENGTH = 8

            /**
             * The pure projection, extracted so every rule it encodes — grounding a stale link,
             * the ages, which host is shown — is unit-testable against a fake clock value.
             */
            internal fun buildState(
                status: ConnectorStatus,
                config: ConnectorConfig,
                nowMillis: Long,
            ): ConnectorUiState {
                val attached = status as? ConnectorStatus.Attached
                val grounded = ConnectorLiveness.ground(status, nowMillis)
                return ConnectorUiState(
                    status = grounded,
                    deviceIdShort = shortenDeviceId(config.deviceId),
                    edgeHost = config.dialHost,
                    isEnrolled = config.isEnrolled,
                    lastServerHeartbeatAgoMillis = attached?.let { nowMillis - it.lastServerHeartbeatMillis },
                    attachUptimeMillis = attached?.let { nowMillis - it.attachedSinceMillis },
                    countdownMillis = countdown(grounded, nowMillis),
                    stoppedByUser = config.stoppedByUser,
                )
            }

            /** The remaining wait a deadline-bearing state carries, or null when there is none. */
            private fun countdown(
                status: ConnectorStatus,
                nowMillis: Long,
            ): Long? {
                val deadline =
                    when (status) {
                        is ConnectorStatus.Reconnecting -> status.nextRetryAtMillis
                        is ConnectorStatus.OutsideActiveHours -> status.reopensAtMillis
                        else -> return null
                    }
                return (deadline - nowMillis).takeIf { it > 0 }
            }

            /**
             * How long a freshly submitted pairing is given before the connector's status is
             * believed to be about IT.
             *
             * Without the window a pairing submitted while the connector sits in
             * [ConnectorStatus.EnrolmentRejected] — which is the ordinary case, since a refused
             * code is exactly why someone fetches a new one — would render the PREVIOUS attempt's
             * refusal as the new one's, instantly and convincingly. The window is generous because
             * the cost of the two mistakes is not symmetric: waiting a moment for a real refusal
             * costs the holder a moment, while a false refusal sends them back to the console for
             * a code they already have.
             */
            private const val PAIRING_SETTLE_MS = 3_000L

            /**
             * The pure projection of a pairing attempt, extracted for the same reason [buildState]
             * is: the settle window and the definition of "paired" are rules, and rules that decide
             * what a holder is told belong somewhere they can be tested.
             *
             * Attached AND enrolled is the only success. A halted or absent connector is a failure
             * once the window has passed — including [ConnectorStatus.NeedsConfig] and
             * [ConnectorStatus.NotEnrolled], which after a write that supplied both facts mean the
             * connector read the pairing and still has nothing to dial with. Everything else is the
             * handshake in motion, reported in the connector's own words.
             */
            internal fun buildPairingProgress(
                status: ConnectorStatus,
                isEnrolled: Boolean,
                startedAtMillis: Long?,
                nowMillis: Long,
            ): PairingProgress {
                val settled = startedAtMillis != null && nowMillis - startedAtMillis >= PAIRING_SETTLE_MS
                return when {
                    startedAtMillis == null -> {
                        PairingProgress.Idle
                    }

                    isEnrolled && status is ConnectorStatus.Attached -> {
                        PairingProgress.Paired
                    }

                    status is ConnectorStatus.Halted -> {
                        if (settled) PairingProgress.Refused(status.reason) else PairingProgress.Starting
                    }

                    status is ConnectorStatus.Stopped ||
                        status is ConnectorStatus.NeedsConfig ||
                        status is ConnectorStatus.NotEnrolled -> {
                        if (settled) PairingProgress.Refused(null) else PairingProgress.Starting
                    }

                    else -> {
                        PairingProgress.InProgress(status.notificationLabel)
                    }
                }
            }

            /** The leading segment of the platform's device UUID, or empty when not enrolled. */
            internal fun shortenDeviceId(deviceId: String): String = deviceId.take(DEVICE_ID_PREFIX_LENGTH)
        }
    }
