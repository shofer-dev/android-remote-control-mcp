package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies dial-target resolution ([PlatformConnector.resolveDialUrl]) and the "has any target"
 * guard ([PlatformConnector.hasDialTarget]): a full `gatewayUrl` wins verbatim (the in-cluster
 * `ws://host:port/ws/phone` path), an `edgeHost` falls back to `wss://<edgeHost>/ws/phone` (the
 * physical/tethered path), an unsupported scheme is rejected, and neither configured means nothing
 * to dial.
 */
class PlatformConnectorDialUrlTest {
    @Test
    fun `gatewayUrl is used verbatim when present`() {
        val config = ConnectorConfig(gatewayUrl = "ws://phone-gateway.justceo.svc.cluster.local:8025/ws/phone")

        val resolved = PlatformConnector.resolveDialUrl(config)

        assertInstanceOf(PlatformConnector.DialResolution.Ok::class.java, resolved)
        assertEquals(
            "ws://phone-gateway.justceo.svc.cluster.local:8025/ws/phone",
            (resolved as PlatformConnector.DialResolution.Ok).url,
        )
    }

    @Test
    fun `gatewayUrl wins over edgeHost`() {
        val config =
            ConnectorConfig(
                edgeHost = "phones.justceo.ai",
                gatewayUrl = "wss://gateway.internal:9443/ws/phone",
            )

        val resolved = PlatformConnector.resolveDialUrl(config)

        assertEquals(
            "wss://gateway.internal:9443/ws/phone",
            (resolved as PlatformConnector.DialResolution.Ok).url,
        )
    }

    @Test
    fun `edgeHost falls back to wss host ws device when gatewayUrl blank`() {
        val config = ConnectorConfig(edgeHost = "phones.justceo.ai")

        val resolved = PlatformConnector.resolveDialUrl(config)

        assertEquals(
            "wss://phones.justceo.ai/ws/phone",
            (resolved as PlatformConnector.DialResolution.Ok).url,
        )
    }

    @Test
    fun `gatewayUrl with unsupported scheme is invalid`() {
        val config = ConnectorConfig(gatewayUrl = "http://gateway.internal:8025/ws/phone")

        val resolved = PlatformConnector.resolveDialUrl(config)

        assertInstanceOf(PlatformConnector.DialResolution.Invalid::class.java, resolved)
        assertEquals(
            "http://gateway.internal:8025/ws/phone",
            (resolved as PlatformConnector.DialResolution.Invalid).url,
        )
    }

    @Test
    fun `hasDialTarget is false when neither gatewayUrl nor edgeHost is set`() {
        assertFalse(PlatformConnector.hasDialTarget(ConnectorConfig()))
    }

    @Test
    fun `hasDialTarget is true when only gatewayUrl is set`() {
        assertTrue(PlatformConnector.hasDialTarget(ConnectorConfig(gatewayUrl = "ws://gw:8025/ws/phone")))
    }

    @Test
    fun `hasDialTarget is true when only edgeHost is set`() {
        assertTrue(PlatformConnector.hasDialTarget(ConnectorConfig(edgeHost = "phones.justceo.ai")))
    }
}
