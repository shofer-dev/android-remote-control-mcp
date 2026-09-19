// The persisted-blob parse below deliberately swallows: the ONLY correct response to a config this
// build cannot read is the documented default, and there is nothing a caller could do with the
// exception. Same posture and same suppression as the neighbouring `ConnectorConfig`.
@file:Suppress("SwallowedException")

package com.danielealbano.androidremotecontrolmcp.data.model

import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The HOLDER's half of the device-event plane's two filters
 * (`docs/phone/device_events.md` §2): one switch per category, default ON.
 *
 * It is a NEW blob rather than a section of [EventChannelConfig] on purpose. That one configures
 * the upstream EVENT-CHANNEL plugin — an outbound HTTP webhook with its own dispatcher,
 * endpoint and auth token — which has nothing to do with the platform socket. Folding the
 * platform's categories into it would make one persisted object mean two unrelated features, and
 * the first person to clear the channel's config would silently reset the platform's too.
 *
 * Default ON for all five, because the plane's whole value is that an enrolled phone REPORTS; a
 * holder who wants less narrows it, exactly as they can narrow the rest of the connector. The
 * platform's policy is the other conjunct and can only narrow further — neither side can widen
 * what the other refused ([EventGate][com.danielealbano.androidremotecontrolmcp.services.connector.events.EventGate]).
 */
@Serializable
data class DeviceEventConfig(
    val notification: Boolean = true,
    val call: Boolean = true,
    val sms: Boolean = true,
    val connectivity: Boolean = true,
    val battery: Boolean = true,
) {
    /** Whether the holder permits [category] to leave this handset. */
    fun permits(category: DeviceEventCategory): Boolean =
        when (category) {
            DeviceEventCategory.NOTIFICATION -> notification
            DeviceEventCategory.CALL -> call
            DeviceEventCategory.SMS -> sms
            DeviceEventCategory.CONNECTIVITY -> connectivity
            DeviceEventCategory.BATTERY -> battery
        }

    /** This config with [category] set to [enabled]. */
    fun with(
        category: DeviceEventCategory,
        enabled: Boolean,
    ): DeviceEventConfig =
        when (category) {
            DeviceEventCategory.NOTIFICATION -> copy(notification = enabled)
            DeviceEventCategory.CALL -> copy(call = enabled)
            DeviceEventCategory.SMS -> copy(sms = enabled)
            DeviceEventCategory.CONNECTIVITY -> copy(connectivity = enabled)
            DeviceEventCategory.BATTERY -> copy(battery = enabled)
        }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /**
         * Reads a persisted blob, falling back to the all-on default.
         *
         * A blob this build cannot parse must not silence the plane: the holder never asked for
         * that, and a phone that quietly stopped reporting looks exactly like a phone with nothing
         * to report. The fallback is therefore the DEFAULT rather than an all-off config — the
         * platform's policy is still the other conjunct, so the org's narrowing survives it.
         */
        fun fromJsonOrDefault(raw: String?): DeviceEventConfig {
            if (raw.isNullOrBlank()) return DeviceEventConfig()
            return try {
                json.decodeFromString(serializer(), raw)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                DeviceEventConfig()
            }
        }
    }
}
