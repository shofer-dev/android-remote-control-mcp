package com.danielealbano.androidremotecontrolmcp.services.selfupdate

import android.content.pm.PackageInstaller
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The pure pieces of the self-update path — the one parser both wire legs share, the hex encoding
 * the whole integrity check rests on, and the sentence a failed install is reported with.
 *
 * Each of these fails QUIETLY when it is wrong, which is why they are pinned here: a parser that
 * accepts a half-formed spec installs whatever it points at, a hex encoder that sign-extends makes
 * every checksum mismatch, and a failure description that loses the OS's own message turns "a
 * different signing certificate" into "the install failed".
 */
@DisplayName("Self-update pure helpers")
class UpdateSpecTest {
    @Nested
    @DisplayName("UpdateSpec.parse")
    inner class Parse {
        @Test
        fun `a complete https spec parses`() {
            val spec =
                UpdateSpec.parse(
                    buildJsonObject {
                        put("url", "https://downloads.example.invalid/connector.apk")
                        put("sha256", "abcd")
                        put("version", "g953943d7f944")
                    },
                )

            assertEquals(
                UpdateSpec("https://downloads.example.invalid/connector.apk", "abcd", "g953943d7f944"),
                spec,
            )
        }

        @Test
        fun `a missing field is not a spec`() {
            val spec =
                UpdateSpec.parse(
                    buildJsonObject {
                        put("url", "https://downloads.example.invalid/connector.apk")
                        put("version", "g953943d7f944")
                    },
                )

            assertNull(spec)
        }

        @Test
        fun `an empty version is not a spec — it is how the platform says nothing is published`() {
            val spec =
                UpdateSpec.parse(
                    buildJsonObject {
                        put("url", "https://downloads.example.invalid/connector.apk")
                        put("sha256", "abcd")
                        put("version", "")
                    },
                )

            assertNull(spec)
        }

        @Test
        fun `a non-https url is refused`() {
            val spec =
                UpdateSpec.parse(
                    buildJsonObject {
                        put("url", "http://downloads.example.invalid/connector.apk")
                        put("sha256", "abcd")
                        put("version", "g953943d7f944")
                    },
                )

            assertNull(spec)
        }

        @Test
        fun `absent params are not a spec`() {
            assertNull(UpdateSpec.parse(null))
        }

        @Test
        fun `a params value of the wrong SHAPE is refused rather than throwing`() {
            // A gateway bug must be a typed refusal, not a crashed action handler.
            val spec =
                UpdateSpec.parse(
                    buildJsonObject {
                        put("url", buildJsonObject { put("nested", "https://e.invalid/a.apk") })
                        put("sha256", JsonPrimitive("abcd"))
                        put("version", JsonPrimitive("g953943d7f944"))
                    },
                )

            assertNull(spec)
        }

        @Test
        fun `an explicit JSON null is not a value`() {
            // JsonNull IS a JsonPrimitive and its `content` is the string "null", so the obvious
            // spelling of this parser would read `"sha256": null` as a perfectly good hash.
            val spec =
                UpdateSpec.parse(
                    buildJsonObject {
                        put("url", "https://downloads.example.invalid/connector.apk")
                        put("sha256", JsonNull)
                        put("version", "g953943d7f944")
                    },
                )

            assertNull(spec)
        }
    }

    @Nested
    @DisplayName("hex encoding")
    inner class Hex {
        @Test
        fun `bytes above 0x7f encode unsigned, not sign-extended`() {
            // The trap in hand-rolling this: a naive Int conversion renders -1 as "ffffffff", and
            // every checksum comparison then fails for reasons nobody can see.
            assertEquals("00017fff80fe", OkHttpApkDownloader.toHex(byteArrayOf(0, 1, 127, -1, -128, -2)))
        }

        @Test
        fun `an empty digest encodes to an empty string`() {
            assertEquals("", OkHttpApkDownloader.toHex(byteArrayOf()))
        }
    }

    @Nested
    @DisplayName("install failure descriptions")
    inner class Failures {
        @Test
        fun `a certificate conflict names the likely cause`() {
            val description =
                InstallFailure.describe(PackageInstaller.STATUS_FAILURE_CONFLICT, null)

            assertTrue(description.contains("signing certificate"))
        }

        @Test
        fun `the OS's own message is appended when there is one`() {
            val description =
                InstallFailure.describe(
                    PackageInstaller.STATUS_FAILURE_STORAGE,
                    "insufficient space",
                )

            assertTrue(description.contains("storage"))
            assertTrue(description.contains("insufficient space"))
        }

        @Test
        fun `an unrecognised status still says something specific`() {
            val description = InstallFailure.describe(UNKNOWN_STATUS, null)

            assertTrue(description.contains(UNKNOWN_STATUS.toString()))
        }
    }

    private companion object {
        const val UNKNOWN_STATUS = 99
    }
}
