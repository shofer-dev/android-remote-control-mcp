package com.danielealbano.androidremotecontrolmcp.services.selfupdate

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * The self-updater's ORDER, pinned: refuse a same-version push, fetch, verify, install — and never
 * reach a later step when an earlier one refused.
 *
 * The last clause is the one worth a test file. A checksum that is computed but not ACTED ON, or a
 * download failure that still hands a half-written file to `PackageInstaller`, both look like
 * working code and both install whatever bytes arrived. So every refusal path asserts the
 * installer was not called, not merely that the returned code was right.
 *
 * Nothing here touches the network or a package manager: [ApkDownloader] and [ApkInstaller] are the
 * seams, which is what they exist for.
 */
@DisplayName("SelfUpdater")
class SelfUpdaterTest {
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

    private fun updater(): SelfUpdater =
        SelfUpdater(
            workDir = workDir,
            downloader = downloader,
            installer = installer,
            appVersion = CURRENT_VERSION,
        )

    @Nested
    @DisplayName("applyUpdate")
    inner class ApplyUpdate {
        @Test
        fun `a push of the running version is refused as already-current and nothing is fetched`() =
            runTest {
                val updater = updater()

                val outcome = updater.applyUpdate(SPEC.copy(version = CURRENT_VERSION))

                assertEquals(UpdateRefusal.ALREADY_CURRENT, refusal(outcome).error)
                coVerify(exactly = 0) { downloader.download(any(), any()) }
                verify(exactly = 0) { installer.install(any()) }
                // Not a failure: the platform re-publishing what is running has proved this device
                // is current, and the card must say so rather than showing an error.
                assertEquals(UpdateAvailability.UpToDate, updater.state.value.availability)
                assertEquals(UpdateInstall.Idle, updater.state.value.install)
            }

        @Test
        fun `a download failure is reported as download-failed and never installed`() =
            runTest {
                coEvery { downloader.download(SPEC.url, any()) } throws IOException("the publisher answered HTTP 404")
                val updater = updater()

                val outcome = updater.applyUpdate(SPEC)

                assertEquals(UpdateRefusal.DOWNLOAD_FAILED, refusal(outcome).error)
                assertTrue(refusal(outcome).details.contains("404"))
                verify(exactly = 0) { installer.install(any()) }
                assertEquals(UpdateRefusal.DOWNLOAD_FAILED, failedState(updater).error)
            }

        @Test
        fun `bytes that do not match the declared sha256 are discarded, not installed`() =
            runTest {
                coEvery { downloader.download(SPEC.url, any()) } coAnswers {
                    stage(secondArg<File>())
                    "0000000000000000000000000000000000000000000000000000000000000000"
                }
                val updater = updater()

                val outcome = updater.applyUpdate(SPEC)

                assertEquals(UpdateRefusal.CHECKSUM_MISMATCH, refusal(outcome).error)
                verify(exactly = 0) { installer.install(any()) }
                // The staged APK must not survive a failed verification: leaving it is how a later
                // bug installs bytes that were already rejected once.
                assertFalse(File(workDir, SelfUpdater.APK_FILE_NAME).exists())
            }

        @Test
        fun `a hash that matches in a different case still verifies`() =
            runTest {
                // The wire contract says lowercase hex, but a case-sensitive compare would turn a
                // publisher's harmless uppercase into an unexplainable checksum failure.
                coEvery { downloader.download(SPEC.url, any()) } coAnswers {
                    stage(secondArg<File>())
                    DIGEST
                }
                val updater = updater()

                val outcome = updater.applyUpdate(SPEC.copy(sha256 = DIGEST.uppercase()))

                assertEquals(UpdateOutcome.Accepted, outcome)
            }

        @Test
        fun `a verified APK is handed to the installer and accepted`() =
            runTest {
                val staged = slot<File>()
                coEvery { downloader.download(SPEC.url, any()) } coAnswers {
                    stage(secondArg<File>())
                    DIGEST
                }
                val updater = updater()

                val outcome = updater.applyUpdate(SPEC)

                assertEquals(UpdateOutcome.Accepted, outcome)
                verify(exactly = 1) { installer.install(capture(staged)) }
                assertEquals(SelfUpdater.APK_FILE_NAME, staged.captured.name)
            }

        @Test
        fun `an installer that refuses the session is reported as install-refused`() =
            runTest {
                coEvery { downloader.download(SPEC.url, any()) } coAnswers {
                    stage(secondArg<File>())
                    DIGEST
                }
                every { installer.install(any()) } throws IOException("no session could be created")
                val updater = updater()

                val outcome = updater.applyUpdate(SPEC)

                assertEquals(UpdateRefusal.INSTALL_REFUSED, refusal(outcome).error)
                assertTrue(refusal(outcome).details.contains("no session"))
                assertEquals(UpdateRefusal.INSTALL_REFUSED, failedState(updater).error)
            }
    }

    @Nested
    @DisplayName("update checks")
    inner class Checks {
        @Test
        fun `a check with no answer lapses to unknown, never to up-to-date`() {
            // "Up to date" would be a claim about the PLATFORM made from its silence — and an older
            // gateway with no update_check handler is silent by construction.
            val updater = updater()

            updater.onCheckSent(CHECK_ID)
            assertEquals(UpdateAvailability.Checking, updater.state.value.availability)

            updater.onCheckLapsed(CHECK_ID)
            assertEquals(UpdateAvailability.Unknown, updater.state.value.availability)
        }

        @Test
        fun `an older check's lapse does not retire a newer one`() {
            val updater = updater()

            updater.onCheckSent(CHECK_ID)
            updater.onCheckSent("a-second-check")
            updater.onCheckLapsed(CHECK_ID)

            assertEquals(UpdateAvailability.Checking, updater.state.value.availability)
        }

        @Test
        fun `an update_info naming a different version is offered`() {
            val updater = updater()
            updater.onCheckSent(CHECK_ID)

            updater.onUpdateInfo(
                buildJsonObject {
                    put("url", SPEC.url)
                    put("sha256", SPEC.sha256)
                    put("version", SPEC.version)
                },
            )

            assertEquals(UpdateAvailability.Available(SPEC), updater.state.value.availability)
        }

        @Test
        fun `an update_info naming the running version is up-to-date`() {
            val updater = updater()

            updater.onUpdateInfo(
                buildJsonObject {
                    put("url", SPEC.url)
                    put("sha256", SPEC.sha256)
                    put("version", CURRENT_VERSION)
                },
            )

            assertEquals(UpdateAvailability.UpToDate, updater.state.value.availability)
        }

        @Test
        fun `an update_info publishing nothing is up-to-date rather than unknown`() {
            // The gateway ANSWERED. The contract's way of saying "nothing is published" is an
            // absent or empty version, and that is a fact about the platform, not an absence of one.
            val updater = updater()

            updater.onUpdateInfo(buildJsonObject { put("version", "") })

            assertEquals(UpdateAvailability.UpToDate, updater.state.value.availability)
        }

        @Test
        fun `applyAvailableUpdate does nothing when no update is offered`() =
            runTest {
                val updater = updater()

                assertNull(updater.applyAvailableUpdate())

                coVerify(exactly = 0) { downloader.download(any(), any()) }
            }

        @Test
        fun `applyAvailableUpdate applies what the last check found`() =
            runTest {
                coEvery { downloader.download(SPEC.url, any()) } coAnswers {
                    stage(secondArg<File>())
                    DIGEST
                }
                val updater = updater()
                updater.onUpdateInfo(
                    buildJsonObject {
                        put("url", SPEC.url)
                        put("sha256", SPEC.sha256)
                        put("version", SPEC.version)
                    },
                )

                assertEquals(UpdateOutcome.Accepted, updater.applyAvailableUpdate())

                verify(exactly = 1) { installer.install(any()) }
            }
    }

    @Nested
    @DisplayName("install callbacks")
    inner class InstallCallbacks {
        @Test
        fun `a pending user action is shown as awaiting confirmation`() {
            val updater = updater()

            updater.onInstallPending()

            assertEquals(UpdateInstall.AwaitingConfirmation, updater.state.value.install)
        }

        @Test
        fun `a failure reported by the OS carries its own message`() {
            val updater = updater()

            updater.onInstallFailed("the update conflicts with the installed app")

            assertEquals(
                UpdateInstall.Failed(UpdateRefusal.INSTALL_REFUSED, "the update conflicts with the installed app"),
                updater.state.value.install,
            )
        }

        @Test
        fun `a success clears the offer`() {
            val updater = updater()
            updater.onUpdateInfo(
                buildJsonObject {
                    put("url", SPEC.url)
                    put("sha256", SPEC.sha256)
                    put("version", SPEC.version)
                },
            )

            updater.onInstallSucceeded()

            assertEquals(UpdateAvailability.UpToDate, updater.state.value.availability)
            assertEquals(UpdateInstall.Idle, updater.state.value.install)
        }
    }

    /** Writes a placeholder APK where the updater expects one, as a real download would. */
    private fun stage(target: File) {
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(APK_PLACEHOLDER_BYTES))
    }

    private fun refusal(outcome: UpdateOutcome): UpdateOutcome.Refused = outcome as UpdateOutcome.Refused

    private fun failedState(u: SelfUpdater): UpdateInstall.Failed = u.state.value.install as UpdateInstall.Failed

    private companion object {
        const val CURRENT_VERSION = "g111111111111"
        const val CHECK_ID = "check-1"
        const val APK_PLACEHOLDER_BYTES = 32

        /** Any 64-character hex string; the updater compares, it does not recompute. */
        const val DIGEST = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

        val SPEC =
            UpdateSpec(
                url = "https://downloads.example.invalid/connector.apk",
                sha256 = DIGEST,
                version = "g953943d7f944",
            )
    }
}
