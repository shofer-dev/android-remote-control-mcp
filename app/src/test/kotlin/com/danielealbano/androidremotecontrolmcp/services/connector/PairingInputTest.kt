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
            assertEquals("phones.justceo.ai", PairingInput.normaliseHost("phones.justceo.ai"))
        }

        @Test
        fun `surrounding whitespace is removed`() {
            assertEquals("phones.justceo.ai", PairingInput.normaliseHost("  phones.justceo.ai\n"))
        }

        @Test
        fun `whitespace inside a wrapped paste is removed`() {
            assertEquals("phones.justceo.ai", PairingInput.normaliseHost("phones.just\nceo.ai"))
        }

        @Test
        fun `a pasted dial url is reduced to its host`() {
            assertEquals(
                "phones.justceo.ai",
                PairingInput.normaliseHost("wss://phones.justceo.ai/ws/phone"),
            )
        }

        @Test
        fun `an explicit port survives, because the dial url can carry one`() {
            assertEquals(
                "phone-gateway.justceo.svc:8080",
                PairingInput.normaliseHost("ws://phone-gateway.justceo.svc:8080/ws/phone"),
            )
        }

        @Test
        fun `a path without a scheme is dropped too`() {
            assertEquals("phones.justceo.ai", PairingInput.normaliseHost("phones.justceo.ai/ws/phone"))
        }

        @Test
        fun `a query string is dropped`() {
            assertEquals("phones.justceo.ai", PairingInput.normaliseHost("phones.justceo.ai?x=1"))
        }

        @Test
        fun `userinfo is dropped`() {
            assertEquals("phones.justceo.ai", PairingInput.normaliseHost("https://bob@phones.justceo.ai/"))
        }

        @Test
        fun `the host is lowercased, because DNS does not distinguish case`() {
            assertEquals("phones.justceo.ai", PairingInput.normaliseHost("Phones.JustCEO.ai"))
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
            assertNull(PairingInput.validate("phones.justceo.ai", "PAIR-1234"))
        }

        @Test
        fun `a host with a port is accepted`() {
            assertNull(PairingInput.validate("phone-gateway.justceo.svc:8080", "PAIR-1234"))
        }

        @Test
        fun `a blank host is refused`() {
            assertEquals(PairingInput.Error.HOST_BLANK, PairingInput.validate("", "PAIR-1234"))
        }

        @Test
        fun `a blank code is refused`() {
            assertEquals(PairingInput.Error.CODE_BLANK, PairingInput.validate("phones.justceo.ai", ""))
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
            val host = PairingInput.normaliseHost("justceo-enrol:v1?host=phones.justceo.ai&code=X")
            assertEquals(PairingInput.Error.HOST_INVALID, PairingInput.hostError(host))
        }
    }

    @Nested
    @DisplayName("parsePairingUri")
    inner class ParsePairingUri {
        @Test
        fun `a well-formed link yields both facts`() {
            val parsed = PairingInput.parsePairingUri("justceo-enrol:v1?host=phones.justceo.ai&code=PAIR-1234")

            assertEquals("phones.justceo.ai", parsed?.edgeHost)
            assertEquals("PAIR-1234", parsed?.code)
        }

        @Test
        fun `percent-encoded values are decoded`() {
            val parsed =
                PairingInput.parsePairingUri("justceo-enrol:v1?host=phones.justceo.ai&code=PAIR%2D1234")

            assertEquals("PAIR-1234", parsed?.code)
        }

        @Test
        fun `parameter order does not matter`() {
            val parsed = PairingInput.parsePairingUri("justceo-enrol:v1?code=PAIR-1234&host=phones.justceo.ai")

            assertEquals("phones.justceo.ai", parsed?.edgeHost)
            assertEquals("PAIR-1234", parsed?.code)
        }

        @Test
        fun `a host carrying a full url is normalised like a typed one`() {
            val parsed =
                PairingInput.parsePairingUri("justceo-enrol:v1?host=wss://phones.justceo.ai/ws/phone&code=X")

            assertEquals("phones.justceo.ai", parsed?.edgeHost)
        }

        @Test
        fun `surrounding whitespace does not stop a link from parsing`() {
            val parsed = PairingInput.parsePairingUri("  justceo-enrol:v1?host=phones.justceo.ai&code=X  ")

            assertEquals("phones.justceo.ai", parsed?.edgeHost)
        }

        @Test
        fun `another scheme is not a pairing link`() {
            assertNull(PairingInput.parsePairingUri("https://phones.justceo.ai/?code=PAIR-1234"))
        }

        @Test
        fun `a version this app does not understand is refused rather than guessed at`() {
            assertNull(PairingInput.parsePairingUri("justceo-enrol:v2?host=phones.justceo.ai&code=X"))
        }

        @Test
        fun `a link missing the code is not half a pairing`() {
            assertNull(PairingInput.parsePairingUri("justceo-enrol:v1?host=phones.justceo.ai"))
        }

        @Test
        fun `a link missing the host is not half a pairing`() {
            assertNull(PairingInput.parsePairingUri("justceo-enrol:v1?code=PAIR-1234"))
        }

        @Test
        fun `plain text is not a pairing link`() {
            assertNull(PairingInput.parsePairingUri("phones.justceo.ai"))
        }
    }
}
