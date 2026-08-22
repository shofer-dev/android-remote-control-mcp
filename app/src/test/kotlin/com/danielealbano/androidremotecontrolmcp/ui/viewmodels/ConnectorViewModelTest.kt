package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorLiveness
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorStatus
import com.danielealbano.androidremotecontrolmcp.utils.MonotonicClock
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

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.connectorConfig } returns configFlow
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

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
                    config = ConnectorConfig(edgeHost = "devices.justceo.ai"),
                    nowMillis = 0,
                )

            assertFalse(state.isEnrolled)
            assertEquals("", state.deviceIdShort)
            assertEquals("devices.justceo.ai", state.edgeHost)
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
            assertEquals("7f3ab21c", state.deviceIdShort)
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
        fun `an explicit gateway url is shown by host, not verbatim`() {
            val state =
                ConnectorViewModel.buildState(
                    status = ConnectorStatus.Stopped,
                    config =
                        ConnectorConfig(
                            edgeHost = "devices.justceo.ai",
                            gatewayUrl = "ws://device-gateway.justceo.svc:8080/ws/device",
                        ),
                    nowMillis = 0,
                )

            assertEquals("device-gateway.justceo.svc", state.edgeHost)
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
                val viewModel = ConnectorViewModel(settingsRepository, MonotonicClock { NOW })

                viewModel.uiState.test {
                    assertEquals(ConnectorUiState(), awaitItem()) // stateIn's seed
                    val joined = awaitItem()
                    assertEquals("devices.justceo.ai", joined.edgeHost)
                    assertEquals("7f3ab21c", joined.deviceIdShort)
                    assertTrue(joined.isEnrolled)
                    cancelAndIgnoreRemainingEvents()
                }
            }

        @Test
        fun `a configuration change re-projects without a clock tick`() =
            runTest {
                val viewModel = ConnectorViewModel(settingsRepository, MonotonicClock { NOW })

                viewModel.uiState.test {
                    // The seed and the first projection of an EMPTY config are equal, so the
                    // StateFlow conflates them and only the seed is observed here.
                    assertEquals("", awaitItem().edgeHost)
                    configFlow.value = ENROLLED_CONFIG
                    assertEquals("devices.justceo.ai", awaitItem().edgeHost)
                    cancelAndIgnoreRemainingEvents()
                }
            }
    }

    private companion object {
        const val NOW = 0L

        val ENROLLED_CONFIG =
            ConnectorConfig(
                edgeHost = "devices.justceo.ai",
                deviceId = "7f3ab21c-9d44-4a1e-8f0b-2c5d6e7a8b90",
            )
    }
}
