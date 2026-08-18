@file:Suppress("SwallowedException")

package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Configuration and durable state for the platform connector (the `/ws/device` client).
 *
 * [edgeHost] is the device-edge host only (e.g. `devices.justceo.ai`); the connector dials
 * `wss://<edgeHost>/ws/device`. No host is ever hardcoded — this comes from DataStore, set
 * through the settings UI or the adb broadcast (fork map §10).
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
    val enrolmentCode: String = "",
    val deviceId: String = "",
    val autoStart: Boolean = false,
) {
    /** True once the device holds a durable identity and no longer needs a pairing code. */
    val isEnrolled: Boolean get() = deviceId.isNotBlank()

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
