package com.danielealbano.androidremotecontrolmcp.services.connector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PairingInput")
class PairingInputTest {
    @Nested
    @DisplayName("normaliseHost")
    inner class NormaliseHost {
        @Test
        fun `a plain host is left alone`() {
            assertEquals("devices.justceo.ai", PairingInput.normaliseHost("devices.justceo.ai"))
        }

        @Test
        fun `surrounding whitespace is removed`() {
            assertEquals("devices.justceo.ai", PairingInput.normaliseHost("  devices.justceo.ai\n"))
        }

        @Test
        fun `whitespace inside a wrapped paste is removed`() {
            assertEquals("devices.justceo.ai", PairingInput.normaliseHost("devices.just\nceo.ai"))
        }

        @Test
        fun `a pasted dial url is reduced to its host`() {
            assertEquals(
                "devices.justceo.ai",
                PairingInput.normaliseHost("wss://devices.justceo.ai/ws/device"),
            )
        }

        @Test
        fun `an explicit port survives, because the dial url can carry one`() {
            assertEquals(
                "device-gateway.justceo.svc:8080",
                PairingInput.normaliseHost("ws://device-gateway.justceo.svc:8080/ws/device"),
            )
        }

        @Test
        fun `a path without a scheme is dropped too`() {
            assertEquals("devices.justceo.ai", PairingInput.normaliseHost("devices.justceo.ai/ws/device"))
        }

        @Test
        fun `a query string is dropped`() {
            assertEquals("devices.justceo.ai", PairingInput.normaliseHost("devices.justceo.ai?x=1"))
        }

        @Test
        fun `userinfo is dropped`() {
            assertEquals("devices.justceo.ai", PairingInput.normaliseHost("https://bob@devices.justceo.ai/"))
        }

        @Test
        fun `the host is lowercased, because DNS does not distinguish case`() {
            assertEquals("devices.justceo.ai", PairingInput.normaliseHost("Devices.JustCEO.ai"))
        }
    }

    @Nested
    @DisplayName("normaliseCode")
    inner class NormaliseCode {
        @Test
        fun `whitespace is stripped, including the grouping a console renders`() {
            assertEquals("ABCDEFGH", PairingInput.normaliseCode("  ABCD EFGH\n"))
        }

        @Test
        fun `case is preserved, because only the platform knows whether it matters`() {
            assertEquals("aBcD-1234", PairingInput.normaliseCode("aBcD-1234"))
        }
    }

    @Nested
    @DisplayName("validation")
    inner class Validation {
        @Test
        fun `a host and a code are accepted`() {
            assertNull(PairingInput.validate("devices.justceo.ai", "PAIR-1234"))
        }

        @Test
        fun `a host with a port is accepted`() {
            assertNull(PairingInput.validate("device-gateway.justceo.svc:8080", "PAIR-1234"))
        }

        @Test
        fun `a blank host is refused`() {
            assertEquals(PairingInput.Error.HOST_BLANK, PairingInput.validate("", "PAIR-1234"))
        }

        @Test
        fun `a blank code is refused`() {
            assertEquals(PairingInput.Error.CODE_BLANK, PairingInput.validate("devices.justceo.ai", ""))
        }

        @Test
        fun `the host is reported first, because it is the field above`() {
            assertEquals(PairingInput.Error.HOST_BLANK, PairingInput.validate("", ""))
        }

        @Test
        fun `a host that is not a host is refused rather than dialled`() {
            assertEquals(
                PairingInput.Error.HOST_INVALID,
                PairingInput.validate("not a host!", "PAIR-1234"),
            )
        }

        @Test
        fun `a pairing link left in the host field is refused rather than dialled`() {
            // It normalises to `justceo-enrol:v1`, which is not a host — so the holder is told,
            // instead of the connector halting on a URL nobody can read.
            val host = PairingInput.normaliseHost("justceo-enrol:v1?host=devices.justceo.ai&code=X")
            assertEquals(PairingInput.Error.HOST_INVALID, PairingInput.hostError(host))
        }
    }

    @Nested
    @DisplayName("parsePairingUri")
    inner class ParsePairingUri {
        @Test
        fun `a well-formed link yields both facts`() {
            val parsed = PairingInput.parsePairingUri("justceo-enrol:v1?host=devices.justceo.ai&code=PAIR-1234")

            assertEquals("devices.justceo.ai", parsed?.edgeHost)
            assertEquals("PAIR-1234", parsed?.code)
        }

        @Test
        fun `percent-encoded values are decoded`() {
            val parsed =
                PairingInput.parsePairingUri("justceo-enrol:v1?host=devices.justceo.ai&code=PAIR%2D1234")

            assertEquals("PAIR-1234", parsed?.code)
        }

        @Test
        fun `parameter order does not matter`() {
            val parsed = PairingInput.parsePairingUri("justceo-enrol:v1?code=PAIR-1234&host=devices.justceo.ai")

            assertEquals("devices.justceo.ai", parsed?.edgeHost)
            assertEquals("PAIR-1234", parsed?.code)
        }

        @Test
        fun `a host carrying a full url is normalised like a typed one`() {
            val parsed =
                PairingInput.parsePairingUri("justceo-enrol:v1?host=wss://devices.justceo.ai/ws/device&code=X")

            assertEquals("devices.justceo.ai", parsed?.edgeHost)
        }

        @Test
        fun `surrounding whitespace does not stop a link from parsing`() {
            val parsed = PairingInput.parsePairingUri("  justceo-enrol:v1?host=devices.justceo.ai&code=X  ")

            assertEquals("devices.justceo.ai", parsed?.edgeHost)
        }

        @Test
        fun `another scheme is not a pairing link`() {
            assertNull(PairingInput.parsePairingUri("https://devices.justceo.ai/?code=PAIR-1234"))
        }

        @Test
        fun `a version this app does not understand is refused rather than guessed at`() {
            assertNull(PairingInput.parsePairingUri("justceo-enrol:v2?host=devices.justceo.ai&code=X"))
        }

        @Test
        fun `a link missing the code is not half a pairing`() {
            assertNull(PairingInput.parsePairingUri("justceo-enrol:v1?host=devices.justceo.ai"))
        }

        @Test
        fun `a link missing the host is not half a pairing`() {
            assertNull(PairingInput.parsePairingUri("justceo-enrol:v1?code=PAIR-1234"))
        }

        @Test
        fun `plain text is not a pairing link`() {
            assertNull(PairingInput.parsePairingUri("devices.justceo.ai"))
        }
    }
}
