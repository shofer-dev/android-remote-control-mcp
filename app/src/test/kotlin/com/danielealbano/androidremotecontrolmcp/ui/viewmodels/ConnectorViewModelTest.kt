package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorEnsure
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorLiveness
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorProvisioning
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorStatus
import com.danielealbano.androidremotecontrolmcp.utils.MonotonicClock
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ConnectorViewModel")
class ConnectorViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val configFlow = MutableStateFlow(ConnectorConfig())
    private val hintDismissedFlow = MutableStateFlow(false)
    private val connectorEnsure = mockk<ConnectorEnsure>(relaxed = true)
    private val provisioning = mockk<ConnectorProvisioning>(relaxed = true)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.connectorConfig } returns configFlow
        every { settingsRepository.connectorKeepAliveHintDismissed } returns hintDismissedFlow
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): ConnectorViewModel =
        ConnectorViewModel(settingsRepository, connectorEnsure, provisioning, MonotonicClock { NOW })

    @Nested
    @DisplayName("buildState")
    inner class BuildState {
        @Test
        fun `a fresh attached link reports connected with its ages`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.Connected(attachedSinceMillis = 1_000, lastServerHeartbeatMillis = 9_000),
                    config = ENROLLED_CONFIG,
                    nowMillis = 12_000,
                )

            assertTrue(state.status is ConnectorStatus.Connected)
            assertEquals(3_000L, state.lastServerHeartbeatAgoMillis)
            assertEquals(11_000L, state.attachUptimeMillis)
        }

        @Test
        fun `a silent link reports reconnecting but keeps the silence visible`() {
            val now = 1_000 + ConnectorLiveness.STALE_AFTER_MS
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.Connected(attachedSinceMillis = 0, lastServerHeartbeatMillis = 1_000),
                    config = ENROLLED_CONFIG,
                    nowMillis = now,
                )

            assertTrue(state.status is ConnectorStatus.Reconnecting)
            assertEquals(ConnectorLiveness.STALE_AFTER_MS, state.lastServerHeartbeatAgoMillis)
        }

        @Test
        fun `a state that was never attached carries no ages`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.Connecting,
                    config = ENROLLED_CONFIG,
                    nowMillis = 5_000,
                )

            assertNull(state.lastServerHeartbeatAgoMillis)
            assertNull(state.attachUptimeMillis)
        }

        @Test
        fun `a halted state passes through with its reason`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.AttachRejected("device revoked"),
                    config = ENROLLED_CONFIG,
                    nowMillis = 5_000,
                )

            assertEquals("device revoked", (state.status as ConnectorStatus.Halted).reason)
        }

        @Test
        fun `an unenrolled device says so and shows no device id`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.NotEnrolled,
                    config = ConnectorConfig(edgeHost = "phones.justceo.ai"),
                    nowMillis = 0,
                )

            assertFalse(state.isEnrolled)
            assertEquals("", state.phoneIdShort)
            assertEquals("phones.justceo.ai", state.edgeHost)
        }

        @Test
        fun `the device id is shortened to its leading segment`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.Stopped,
                    config = ENROLLED_CONFIG,
                    nowMillis = 0,
                )

            assertTrue(state.isEnrolled)
            assertEquals("7f3ab21c", state.phoneIdShort)
        }

        @Test
        fun `a backing-off reconnect exposes the remaining wait`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.Reconnecting(nextRetryAtMillis = 12_000),
                    config = ENROLLED_CONFIG,
                    nowMillis = 4_000,
                )

            assertEquals(8_000L, state.countdownMillis)
        }

        @Test
        fun `a closed active-hours window exposes when it reopens`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.OutsideActiveHours(reopensAtMillis = 40_000),
                    config = ENROLLED_CONFIG,
                    nowMillis = 10_000,
                )

            assertEquals(30_000L, state.countdownMillis)
        }

        @Test
        fun `an elapsed deadline reports no countdown rather than a negative one`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.Reconnecting(nextRetryAtMillis = 1_000),
                    config = ENROLLED_CONFIG,
                    nowMillis = 9_000,
                )

            assertNull(state.countdownMillis)
        }

        @Test
        fun `states with no deadline carry no countdown`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.Connected(attachedSinceMillis = 0, lastServerHeartbeatMillis = 0),
                    config = ENROLLED_CONFIG,
                    nowMillis = 1_000,
                )

            assertNull(state.countdownMillis)
        }

        @Test
        fun `a stopped connector is reported as not running`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.Stopped,
                    config = ENROLLED_CONFIG,
                    nowMillis = 0,
                )

            assertFalse(state.isRunning)
        }

        @Test
        fun `a halted connector is still RUNNING, because the service is up`() {
            // The distinction the Start/Stop control depends on: only Stopped means "no service".
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.AttachRejected("device revoked"),
                    config = ENROLLED_CONFIG,
                    nowMillis = 0,
                )

            assertTrue(state.isRunning)
        }

        @Test
        fun `the durable stop veto is projected onto the card`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.Stopped,
                    config = ENROLLED_CONFIG.copy(stoppedByUser = true),
                    nowMillis = 0,
                )

            assertTrue(state.stoppedByUser)
            assertFalse(state.isRunning)
        }

        @Test
        fun `an explicit gateway url is shown by host, not verbatim`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.Stopped,
                    config =
                        ConnectorConfig(
                            edgeHost = "phones.justceo.ai",
                            gatewayUrl = "ws://phone-gateway.justceo.svc:8080/ws/phone",
                        ),
                    nowMillis = 0,
                )

            assertEquals("phone-gateway.justceo.svc", state.edgeHost)
        }
    }

    @Nested
    @DisplayName("uiState")
    inner class UiState {
        @Test
        fun `the projection joins the connector status with the durable configuration`() =
            runTest {
                configFlow.value = ENROLLED_CONFIG
                // The ticker never completes, so the flow is read with Turbine and cancelled
                // rather than drained.
                val viewModel = newViewModel()

                viewModel.uiState.test {
                    assertEquals(ConnectorUiState(), awaitItem()) // stateIn's seed
                    val joined = awaitItem()
                    assertEquals("phones.justceo.ai", joined.edgeHost)
                    assertEquals("7f3ab21c", joined.phoneIdShort)
                    assertTrue(joined.isEnrolled)
                    cancelAndIgnoreRemainingEvents()
                }
            }

        @Test
        fun `a configuration change re-projects without a clock tick`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.uiState.test {
                    // The seed and the first projection of an EMPTY config are equal, so the
                    // StateFlow conflates them and only the seed is observed here.
                    assertEquals("", awaitItem().edgeHost)
                    configFlow.value = ENROLLED_CONFIG
                    assertEquals("phones.justceo.ai", awaitItem().edgeHost)
                    cancelAndIgnoreRemainingEvents()
                }
            }
    }

    @Nested
    @DisplayName("lifecycle control")
    inner class LifecycleControl {
        @Test
        fun `start delegates to the shared ensure path`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.start()
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 1) { connectorEnsure.start() }
            }

        @Test
        fun `stop delegates to the shared ensure path`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.stop()
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 1) { connectorEnsure.stop() }
            }

        @Test
        fun `unprovision delegates to the one place an identity is destroyed`() =
            runTest {
                val viewModel =
                    newViewModel()

                viewModel.unprovision()
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 1) { provisioning.unprovision() }
            }

        @Test
        fun `unprovision does NOT stop the connector`() =
            runTest {
                // Without an identity the connector settles into NotEnrolled and waits, so a
                // fresh pairing code provisions the phone the instant it arrives. Stopping the
                // service would make re-provisioning need a second, undiscoverable step.
                val viewModel =
                    newViewModel()

                viewModel.unprovision()
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 0) { connectorEnsure.stop() }
                coVerify(exactly = 0) { settingsRepository.updateConnectorStoppedByUser(any()) }
            }

        @Test
        fun `dismissing the keep-alive hint is remembered`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.dismissKeepAliveHint()
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 1) { settingsRepository.dismissConnectorKeepAliveHint() }
            }
    }

    @Nested
    @DisplayName("pair")
    inner class PairAction {
        @Test
        fun `a pairing is written as one config write, exactly as the adb broadcast writes it`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.pair("phones.justceo.ai", "PAIR-4KJ2")
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 1) {
                    settingsRepository.updateConnectorPairing("phones.justceo.ai", "PAIR-4KJ2")
                }
            }

        @Test
        fun `a pasted url and a spaced code are normalised before they are made durable`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.pair(" wss://Phones.JustCEO.ai/ws/phone ", "PAIR 4KJ2\n")
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 1) {
                    settingsRepository.updateConnectorPairing("phones.justceo.ai", "PAIR4KJ2")
                }
            }

        @Test
        fun `the connector is started, so no Start tap is needed after pairing`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.pair("phones.justceo.ai", "PAIR-4KJ2")
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 1) { connectorEnsure.start() }
            }

        @Test
        fun `nothing is written when the code is missing`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.pair("phones.justceo.ai", "   ")
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 0) { settingsRepository.updateConnectorPairing(any(), any()) }
                coVerify(exactly = 0) { connectorEnsure.start() }
            }

        @Test
        fun `nothing is written when the host is not a host`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.pair("not a host!", "PAIR-4KJ2")
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 0) { settingsRepository.updateConnectorPairing(any(), any()) }
            }

        @Test
        fun `pairing destroys nothing — not an identity, not a preference, not a hint`() =
            runTest {
                // Pairing is additive. Anything cleared here would be cleared behind the holder's
                // back: unprovisioning is its own control, and the stop veto is lifted by the
                // explicit start rather than by a write nobody asked for.
                val viewModel = newViewModel()

                viewModel.pair("phones.justceo.ai", "PAIR-4KJ2")
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 0) { settingsRepository.clearConnectorEnrolment() }
                coVerify(exactly = 0) { settingsRepository.clearConnectorIdentity() }
                coVerify(exactly = 0) { settingsRepository.updateConnectorAutoStart(any()) }
                coVerify(exactly = 0) { settingsRepository.updateConnectorStoppedByUser(any()) }
                coVerify(exactly = 0) { settingsRepository.dismissConnectorKeepAliveHint() }
                coVerify(exactly = 0) { provisioning.unprovision() }
            }
    }

    @Nested
    @DisplayName("buildPairingProgress")
    inner class BuildPairingProgress {
        @Test
        fun `no attempt reports nothing`() {
            val progress =
                ConnectorViewModel.buildPairingProgress(
                    status = ConnectorStatus.NotEnrolled,
                    isEnrolled = false,
                    startedAtMillis = null,
                    nowMillis = 10_000,
                )

            assertEquals(PairingProgress.Idle, progress)
        }

        @Test
        fun `the handshake is reported in the connector's own words`() {
            val progress =
                ConnectorViewModel.buildPairingProgress(
                    status = ConnectorStatus.Enrolling,
                    isEnrolled = false,
                    startedAtMillis = 0,
                    nowMillis = 500,
                )

            assertEquals(PairingProgress.InProgress(ConnectorStatus.Enrolling.notificationLabel), progress)
        }

        @Test
        fun `enrolled and attached is the only success`() {
            val progress =
                ConnectorViewModel.buildPairingProgress(
                    status = ConnectorStatus.Connected(attachedSinceMillis = 0, lastServerHeartbeatMillis = 0),
                    isEnrolled = true,
                    startedAtMillis = 0,
                    nowMillis = 1_000,
                )

            assertEquals(PairingProgress.Paired, progress)
        }

        @Test
        fun `attached without an identity is not paired yet`() {
            val progress =
                ConnectorViewModel.buildPairingProgress(
                    status = ConnectorStatus.Connected(attachedSinceMillis = 0, lastServerHeartbeatMillis = 0),
                    isEnrolled = false,
                    startedAtMillis = 0,
                    nowMillis = 1_000,
                )

            assertTrue(progress is PairingProgress.InProgress)
        }

        @Test
        fun `the PREVIOUS attempt's refusal is not shown as this one's`() {
            // The case this window exists for: a holder fetches a fresh code precisely BECAUSE the
            // connector is sitting in EnrolmentRejected, so the stale status is still on the flow
            // when the new pairing is submitted.
            val progress =
                ConnectorViewModel.buildPairingProgress(
                    status = ConnectorStatus.EnrolmentRejected("that code expired"),
                    isEnrolled = false,
                    startedAtMillis = 0,
                    nowMillis = 100,
                )

            assertEquals(PairingProgress.Starting, progress)
        }

        @Test
        fun `a refusal that outlives the settle window is reported with the platform's reason`() {
            val progress =
                ConnectorViewModel.buildPairingProgress(
                    status = ConnectorStatus.EnrolmentRejected("that pairing code has already been used"),
                    isEnrolled = false,
                    startedAtMillis = 0,
                    nowMillis = 5_000,
                )

            assertEquals(PairingProgress.Refused("that pairing code has already been used"), progress)
        }

        @Test
        fun `a connector that never moved is a failure, not a spinner forever`() {
            val progress =
                ConnectorViewModel.buildPairingProgress(
                    status = ConnectorStatus.Stopped,
                    isEnrolled = false,
                    startedAtMillis = 0,
                    nowMillis = 5_000,
                )

            assertEquals(PairingProgress.Refused(null), progress)
        }

        @Test
        fun `a connector that still says not-enrolled after the window did not take the code`() {
            val progress =
                ConnectorViewModel.buildPairingProgress(
                    status = ConnectorStatus.NotEnrolled,
                    isEnrolled = false,
                    startedAtMillis = 0,
                    nowMillis = 5_000,
                )

            assertEquals(PairingProgress.Refused(null), progress)
        }
    }

    @Nested
    @DisplayName("cancelPairing")
    inner class CancelPairing {
        @Test
        fun `forgetting the attempt touches no configuration`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.pair("phones.justceo.ai", "PAIR-4KJ2")
                testDispatcher.scheduler.advanceUntilIdle()
                viewModel.cancelPairing()
                testDispatcher.scheduler.advanceUntilIdle()

                // The pairing itself stays written: closing the dialog is a decision about
                // watching, not about pairing.
                coVerify(exactly = 1) { settingsRepository.updateConnectorPairing(any(), any()) }
                coVerify(exactly = 0) { settingsRepository.clearConnectorEnrolment() }
            }

        @Test
        fun `the surface returns to its form`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.pairingProgress.test {
                    assertEquals(PairingProgress.Idle, awaitItem())
                    viewModel.pair("phones.justceo.ai", "PAIR-4KJ2")
                    assertTrue(awaitItem() !is PairingProgress.Idle)
                    viewModel.cancelPairing()
                    assertEquals(PairingProgress.Idle, awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
            }
    }

    @Nested
    @DisplayName("keepAliveHintVisible")
    inner class KeepAliveHint {
        @Test
        fun `the hint appears once the device is enrolled`() =
            runTest {
                configFlow.value = ENROLLED_CONFIG
                val viewModel = newViewModel()

                viewModel.keepAliveHintVisible.test {
                    assertFalse(awaitItem()) // stateIn's seed
                    assertTrue(awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
            }

        @Test
        fun `an unenrolled device is not nagged about keeping a connector alive`() =
            runTest {
                val viewModel = newViewModel()

                viewModel.keepAliveHintVisible.test {
                    assertFalse(awaitItem())
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }

        @Test
        fun `a dismissed hint stays dismissed`() =
            runTest {
                configFlow.value = ENROLLED_CONFIG
                hintDismissedFlow.value = true
                val viewModel = newViewModel()

                viewModel.keepAliveHintVisible.test {
                    assertFalse(awaitItem())
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
    }

    private companion object {
        const val NOW = 0L

        val ENROLLED_CONFIG =
            ConnectorConfig(
                edgeHost = "phones.justceo.ai",
                phoneId = "7f3ab21c-9d44-4a1e-8f0b-2c5d6e7a8b90",
            )
    }
}
