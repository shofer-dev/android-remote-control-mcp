package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.PlatformConnectorService
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests [AdbConfigHandler]'s connector wiring: the `gateway_url` extra persists a verbatim URL
 * override, and a `connector_auto_start=true` configure both persists the flag AND starts the
 * connector in the same broadcast (a [PlatformConnectorService.ACTION_START]).
 */
@DisplayName("AdbConfigHandler")
class AdbConfigHandlerTest {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var storageLocationProvider: StorageLocationProvider
    private lateinit var handler: AdbConfigHandler

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }

        settingsRepository = mockk(relaxed = true)
        storageLocationProvider = mockk(relaxed = true)
        handler = AdbConfigHandler(settingsRepository, storageLocationProvider)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /** A configure intent with all connector extras absent by default; specific ones are stubbed per test. */
    private fun configureIntent(): Intent {
        val intent = mockk<Intent>()
        every { intent.action } returns AdbConfigReceiver.ACTION_CONFIGURE
        every { intent.getStringExtra(any()) } returns null
        every { intent.hasExtra(any()) } returns false
        return intent
    }

    @Test
    fun `EXTRA_GATEWAY_URL has expected value`() {
        assertEquals("gateway_url", AdbConfigHandler.EXTRA_GATEWAY_URL)
    }

    @Test
    fun `gateway_url extra persists the trimmed url`() =
        runTest {
            val intent = configureIntent()
            every {
                intent.getStringExtra(AdbConfigHandler.EXTRA_GATEWAY_URL)
            } returns "  ws://phone-gateway.justceo.svc.cluster.local:8025/ws/phone  "
            val context = mockk<Context>(relaxed = true)

            handler.handle(context, intent)

            coVerify(exactly = 1) {
                settingsRepository.updateConnectorGatewayUrl(
                    "ws://phone-gateway.justceo.svc.cluster.local:8025/ws/phone",
                )
            }
        }

    @Test
    fun `connector_auto_start true persists the flag and starts the connector`() =
        runTest {
            val intent = configureIntent()
            every { intent.hasExtra(AdbConfigHandler.EXTRA_CONNECTOR_AUTO_START) } returns true
            every { intent.getBooleanExtra(AdbConfigHandler.EXTRA_CONNECTOR_AUTO_START, false) } returns true
            val context = mockk<Context>(relaxed = true)

            handler.handle(context, intent)

            coVerify(exactly = 1) { settingsRepository.updateConnectorAutoStart(true) }
            verify(exactly = 1) { anyConstructed<Intent>().setAction(PlatformConnectorService.ACTION_START) }
            verify(exactly = 1) { context.startForegroundService(any()) }
        }

    @Test
    fun `connector_auto_start false persists the flag without starting the connector`() =
        runTest {
            val intent = configureIntent()
            every { intent.hasExtra(AdbConfigHandler.EXTRA_CONNECTOR_AUTO_START) } returns true
            every { intent.getBooleanExtra(AdbConfigHandler.EXTRA_CONNECTOR_AUTO_START, false) } returns false
            val context = mockk<Context>(relaxed = true)

            handler.handle(context, intent)

            coVerify(exactly = 1) { settingsRepository.updateConnectorAutoStart(false) }
            verify(exactly = 0) { context.startForegroundService(any()) }
        }

    @Test
    fun `connector_auto_start true clears an earlier explicit stop`() =
        runTest {
            // Otherwise a supervisor's re-provision would persist auto_start=true and then be
            // undone by the stop veto the previous ADB_STOP_CONNECTOR left behind.
            val intent = configureIntent()
            every { intent.hasExtra(AdbConfigHandler.EXTRA_CONNECTOR_AUTO_START) } returns true
            every { intent.getBooleanExtra(AdbConfigHandler.EXTRA_CONNECTOR_AUTO_START, false) } returns true
            val context = mockk<Context>(relaxed = true)

            handler.handle(context, intent)

            coVerify(exactly = 1) { settingsRepository.updateConnectorStoppedByUser(false) }
        }

    @Test
    fun `an adb start clears the stop veto`() =
        runTest {
            val intent = mockk<Intent>()
            every { intent.action } returns AdbConfigReceiver.ACTION_START_CONNECTOR
            val context = mockk<Context>(relaxed = true)

            handler.handle(context, intent)

            coVerify(exactly = 1) { settingsRepository.updateConnectorStoppedByUser(false) }
            verify(exactly = 1) { anyConstructed<Intent>().setAction(PlatformConnectorService.ACTION_START) }
        }

    @Test
    fun `an adb stop sets the stop veto so the watchdog leaves it down`() =
        runTest {
            val intent = mockk<Intent>()
            every { intent.action } returns AdbConfigReceiver.ACTION_STOP_CONNECTOR
            val context = mockk<Context>(relaxed = true)

            handler.handle(context, intent)

            coVerify(exactly = 1) { settingsRepository.updateConnectorStoppedByUser(true) }
            verify(exactly = 1) { anyConstructed<Intent>().setAction(PlatformConnectorService.ACTION_STOP) }
        }
}
