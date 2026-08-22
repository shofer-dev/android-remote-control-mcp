@file:Suppress("SwallowedException")

package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException

/**
 * Configuration and durable state for the platform connector (the `/ws/device` client).
 *
 * The connector resolves its dial target from two fields, in precedence order:
 * - [gatewayUrl], when non-blank, is used VERBATIM — a full `ws://…/ws/device` or
 *   `wss://…/ws/device` URL, scheme and explicit port included. This is the in-cluster path: an
 *   emulated device in an egress-locked pod cannot reach the public edge and must dial its
 *   internal gateway service directly (plain `ws://` with a port).
 * - [edgeHost] is the device-edge host only (e.g. `devices.justceo.ai`); when [gatewayUrl] is
 *   blank the connector falls back to `wss://<edgeHost>/ws/device`. This is the physical/tethered
 *   path. At least one of the two is required to dial.
 *
 * No host or URL is ever hardcoded — both come from DataStore, set through the settings UI or the
 * adb broadcast (fork map §10).
 *
 * [enrolmentCode] is the ONE-TIME pairing code. It is consumed by a successful enrolment and
 * cleared afterwards ([deviceId] is what every subsequent attach names). [deviceId] is the
 * platform `resources` row UUID the gateway returns in `enrolled`; its presence is what
 * distinguishes "must enrol" from "may attach".
 *
 * [autoStart] gates whether the connector foreground service starts on boot / app launch.
 */
@Serializable
data class ConnectorConfig(
    val edgeHost: String = "",
    val gatewayUrl: String = "",
    val enrolmentCode: String = "",
    val deviceId: String = "",
    val autoStart: Boolean = false,
) {
    /** True once the device holds a durable identity and no longer needs a pairing code. */
    val isEnrolled: Boolean get() = deviceId.isNotBlank()

    /**
     * The host the connector dials, for DISPLAY only — the notification text and the connector
     * status card. It follows the same precedence the dial does ([gatewayUrl] wins over
     * [edgeHost]) but reduces a full URL to its authority, because a holder reading a
     * notification wants to know which platform holds the device, not the path and port.
     *
     * A [gatewayUrl] too malformed to parse falls back to the raw value: showing what was
     * configured is more useful than showing nothing when the reason the connector is halted is
     * that very string.
     */
    val dialHost: String
        get() {
            if (gatewayUrl.isBlank()) return edgeHost
            val authority =
                try {
                    URI(gatewayUrl).host
                } catch (e: URISyntaxException) {
                    null
                }
            return authority ?: gatewayUrl
        }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        fun fromJsonOrDefault(raw: String?): ConnectorConfig {
            if (raw.isNullOrBlank()) return ConnectorConfig()
            return try {
                json.decodeFromString(serializer(), raw)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                ConnectorConfig()
            }
        }
    }
}
