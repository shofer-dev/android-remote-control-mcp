package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ConnectorConfig")
class ConnectorConfigTest {
    @Nested
    @DisplayName("isEnrolled")
    inner class IsEnrolled {
        @Test
        fun `a device id is what makes a device enrolled`() {
            assertFalse(ConnectorConfig().isEnrolled)
            assertFalse(ConnectorConfig(enrolmentCode = "ABC123").isEnrolled)
            assertTrue(ConnectorConfig(deviceId = "7f3ab21c-9d44").isEnrolled)
        }
    }

    @Nested
    @DisplayName("dialHost")
    inner class DialHost {
        @Test
        fun `the edge host is shown when no gateway url is set`() {
            assertEquals("devices.justceo.ai", ConnectorConfig(edgeHost = "devices.justceo.ai").dialHost)
        }

        @Test
        fun `a gateway url wins and is reduced to its host`() {
            val config =
                ConnectorConfig(
                    edgeHost = "devices.justceo.ai",
                    gatewayUrl = "ws://device-gateway.justceo.svc:8080/ws/device",
                )

            assertEquals("device-gateway.justceo.svc", config.dialHost)
        }

        @Test
        fun `a wss url is reduced the same way`() {
            assertEquals(
                "devices.justceo.ai",
                ConnectorConfig(gatewayUrl = "wss://devices.justceo.ai/ws/device").dialHost,
            )
        }

        @Test
        fun `an unparseable gateway url is shown verbatim rather than hidden`() {
            // The holder needs to SEE the bad value: it is the reason the connector is halted.
            val raw = "not a url at all"

            assertEquals(raw, ConnectorConfig(gatewayUrl = raw).dialHost)
        }

        @Test
        fun `a url with no authority falls back to the raw value`() {
            assertEquals("ws:///ws/device", ConnectorConfig(gatewayUrl = "ws:///ws/device").dialHost)
        }

        @Test
        fun `nothing configured shows nothing`() {
            assertEquals("", ConnectorConfig().dialHost)
        }
    }

    @Nested
    @DisplayName("serialization")
    inner class Serialization {
        @Test
        fun `a config round-trips through json`() {
            val config =
                ConnectorConfig(
                    edgeHost = "devices.justceo.ai",
                    deviceId = "7f3ab21c-9d44",
                    autoStart = true,
                )

            assertEquals(config, ConnectorConfig.fromJsonOrDefault(config.toJson()))
        }

        @Test
        fun `unparseable persisted json falls back to the defaults`() {
            assertEquals(ConnectorConfig(), ConnectorConfig.fromJsonOrDefault("{not json"))
            assertEquals(ConnectorConfig(), ConnectorConfig.fromJsonOrDefault(null))
        }
    }
}
