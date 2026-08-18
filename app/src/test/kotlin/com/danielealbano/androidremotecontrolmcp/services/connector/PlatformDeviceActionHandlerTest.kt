package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.data.model.LocationData
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
}
