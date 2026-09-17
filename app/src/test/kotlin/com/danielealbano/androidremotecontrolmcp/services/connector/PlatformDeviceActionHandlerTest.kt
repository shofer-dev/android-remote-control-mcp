package com.danielealbano.androidremotecontrolmcp.services.connector

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.LocationData
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ActionName
import com.danielealbano.androidremotecontrolmcp.services.selfupdate.ApkDownloader
import com.danielealbano.androidremotecontrolmcp.services.selfupdate.ApkInstaller
import com.danielealbano.androidremotecontrolmcp.services.selfupdate.SelfUpdater
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Verifies the pure, framework-free helpers of the device-action executors: the `ring`
 * duration parsing/clamping (wire spec Q5 — schema-less params) and the `locate` action_result
 * payload serialization.
 */
class PlatformDeviceActionHandlerTest {
    @Test
    fun `ring duration defaults when absent`() {
        assertEquals(PlatformDeviceActionHandler.DEFAULT_RING_MS, PlatformDeviceActionHandler.parseDurationMs(null))
    }

    @Test
    fun `ring duration is read from params`() {
        val params = buildJsonObject { put("duration_ms", 5_000L) }
        assertEquals(5_000L, PlatformDeviceActionHandler.parseDurationMs(params))
    }

    @Test
    fun `ring duration is clamped to the maximum`() {
        val params = buildJsonObject { put("duration_ms", 10_000_000L) }
        assertEquals(PlatformDeviceActionHandler.MAX_RING_MS, PlatformDeviceActionHandler.parseDurationMs(params))
    }

    @Test
    fun `ring duration is clamped to the minimum`() {
        val params = buildJsonObject { put("duration_ms", 1L) }
        assertEquals(PlatformDeviceActionHandler.MIN_RING_MS, PlatformDeviceActionHandler.parseDurationMs(params))
    }

    @Test
    fun `locate payload carries the fix fields`() {
        val payload =
            PlatformDeviceActionHandler
                .buildLocatePayload(
                    LocationData(latitude = 37.9, longitude = 23.7, accuracyMeters = 12.5f, street = "Odos Ermou"),
                ).jsonObject
        assertEquals(37.9, payload["latitude"]!!.jsonPrimitive.double)
        assertEquals(23.7, payload["longitude"]!!.jsonPrimitive.double)
        assertEquals(12.5f, payload["accuracy_meters"]!!.jsonPrimitive.float)
        assertEquals("Odos Ermou", payload["street"]!!.jsonPrimitive.content)
    }

    @Test
    fun `locate payload omits a null street`() {
        val payload =
            PlatformDeviceActionHandler
                .buildLocatePayload(
                    LocationData(latitude = 0.0, longitude = 0.0, accuracyMeters = 1f, street = null),
                ).jsonObject
        assertNull(payload["street"])
    }

    /**
     * The `update_app` dispatch. It is tested through [DeviceActionHandler.execute] rather than at
     * [SelfUpdater] — which has its own suite — because the thing that can break here is the
     * ROUTING and the rendering of an outcome into an `action_result`: an action name that reaches
     * the unknown-action arm, or a typed refusal flattened into a generic failure, both look
     * perfectly healthy from inside the updater.
     */
    @Nested
    @DisplayName("update_app")
    inner class UpdateApp {
        @TempDir
        lateinit var workDir: File

        private val downloader = mockk<ApkDownloader>()
        private val installer = mockk<ApkInstaller>(relaxed = true)

        @BeforeEach
        fun setUp() {
            mockkStatic(Log::class)
            every { Log.i(any(), any<String>()) } returns 0
            every { Log.w(any<String>(), any<String>()) } returns 0
            every { Log.w(any<String>(), any<String>(), any()) } returns 0
            every { Log.e(any<String>(), any<String>()) } returns 0
            every { Log.e(any<String>(), any<String>(), any()) } returns 0
        }

        @AfterEach
        fun tearDown() {
            unmockkAll()
        }

        private fun handler(): PlatformDeviceActionHandler =
            PlatformDeviceActionHandler(
                appContext = mockk(relaxed = true),
                locationProvider = mockk(relaxed = true),
                selfUpdater =
                    SelfUpdater(
                        workDir = workDir,
                        downloader = downloader,
                        installer = installer,
                        appVersion = CURRENT_VERSION,
                    ),
            )

        private fun params(
            url: String = "https://downloads.example.invalid/connector.apk",
            sha256: String = DIGEST,
            version: String = "g953943d7f944",
        ) = buildJsonObject {
            put("url", url)
            put("sha256", sha256)
            put("version", version)
        }

        @Test
        fun `a verified push is accepted with a boolean payload`() =
            runTest {
                coEvery { downloader.download(any(), any()) } coAnswers {
                    secondArg<File>().apply { parentFile?.mkdirs() }.writeBytes(ByteArray(8))
                    DIGEST
                }

                val outcome = handler().execute(ActionName.UPDATE_APP, params())

                val success = outcome as ActionOutcome.Success
                assertEquals(PlatformDeviceActionHandler.ACCEPTED_PAYLOAD, success.payload)
                // A JSON boolean, not the string "true": the platform parses a boolean, and a
                // quoted one reads to it as a device that never answered.
                val accepted = success.payload!!.jsonObject["accepted"]!!.jsonPrimitive
                assertFalse(accepted.isString)
                assertEquals("true", accepted.content)
                verify(exactly = 1) { installer.install(any()) }
            }

        @Test
        fun `a push of the running version is refused as already-current`() =
            runTest {
                val outcome = handler().execute(ActionName.UPDATE_APP, params(version = CURRENT_VERSION))

                assertEquals("already-current", (outcome as ActionOutcome.Failure).error)
                coVerify(exactly = 0) { downloader.download(any(), any()) }
            }

        @Test
        fun `a checksum mismatch rides back as its own typed refusal`() =
            runTest {
                coEvery { downloader.download(any(), any()) } coAnswers {
                    secondArg<File>().apply { parentFile?.mkdirs() }.writeBytes(ByteArray(8))
                    "0000000000000000000000000000000000000000000000000000000000000000"
                }

                val outcome = handler().execute(ActionName.UPDATE_APP, params())

                assertEquals("checksum-mismatch", (outcome as ActionOutcome.Failure).error)
                verify(exactly = 0) { installer.install(any()) }
            }

        @Test
        fun `params missing a field are refused as bad-params and nothing is fetched`() =
            runTest {
                val outcome = handler().execute(ActionName.UPDATE_APP, buildJsonObject { put("version", "g1") })

                assertEquals("bad-params", (outcome as ActionOutcome.Failure).error)
                coVerify(exactly = 0) { downloader.download(any(), any()) }
            }

        @Test
        fun `a plain http url is refused rather than fetched`() =
            runTest {
                // The bytes are protected by the hash and the OS signature check, but the URL
                // itself may carry a capability token — handing one to a cleartext socket is a
                // disclosure no later check can undo.
                val outcome =
                    handler().execute(
                        ActionName.UPDATE_APP,
                        params(url = "http://downloads.example.invalid/connector.apk"),
                    )

                assertEquals("bad-params", (outcome as ActionOutcome.Failure).error)
                coVerify(exactly = 0) { downloader.download(any(), any()) }
            }
    }

    private companion object {
        const val CURRENT_VERSION = "g111111111111"
        const val DIGEST = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
