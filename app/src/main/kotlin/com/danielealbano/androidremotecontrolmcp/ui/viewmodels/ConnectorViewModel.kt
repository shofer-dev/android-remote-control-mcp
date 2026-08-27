package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorEnsure
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorLiveness
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorProvisioning
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorStatus
import com.danielealbano.androidremotecontrolmcp.services.connector.PlatformConnectorService
import com.danielealbano.androidremotecontrolmcp.utils.MonotonicClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

            /** The leading segment of the platform's device UUID, or empty when not enrolled. */
            internal fun shortenDeviceId(deviceId: String): String = deviceId.take(DEVICE_ID_PREFIX_LENGTH)
        }
    }
