package com.danielealbano.androidremotecontrolmcp.services.connector.events

import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectivityTransport
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceCallState
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The payload key spellings, pinned.
 *
 * Nothing between this file and the bus subscriber would notice a rename: the gateway is a COURIER
 * for the body, exactly as it is for the MCP payload, so a mis-spelled key travels the whole plane
 * and arrives as an absent field. These are the shapes `deviceevent.go` declares.
 */
@DisplayName("device-event payloads")
class EventPayloadsTest {
    @Test
    fun `notification carries exactly its six keys`() {
        val payload = EventPayloads.notification(testNotification(), key = "0|com.example.mail|17|null|10123")
        assertEquals(setOf("package", "app_name", "title", "text", "posted_at", "key"), payload.keys)
        assertEquals("com.example.mail", payload["package"]?.jsonPrimitive?.content)
        assertEquals("Mail", payload["app_name"]?.jsonPrimitive?.content)
        assertEquals("0|com.example.mail|17|null|10123", payload["key"]?.jsonPrimitive?.content)
        assertEquals(EventPayloads.rfc3339(1_757_000_000_000), payload["posted_at"]?.jsonPrimitive?.content)
    }

    @Test
    fun `absent notification text is the empty string, never a JSON null`() {
        // One fact, one spelling: a subscriber must not need a branch for null and another for "".
        val payload = EventPayloads.notification(testNotification(title = null, text = null), key = "k")
        assertEquals("", payload["title"]?.jsonPrimitive?.content)
        assertEquals("", payload["text"]?.jsonPrimitive?.content)
        assertFalse(payload.toString().contains("null"))
    }

    @Test
    fun `call carries state and number, and the number is empty by design`() {
        // READ_CALL_LOG is deliberately not held (see CallEventSource), so `number` is always "".
        // The key is still emitted: the shape must be stable and read as "not disclosed".
        val payload = EventPayloads.call(DeviceCallState.RINGING, "")
        assertEquals(setOf("state", "number"), payload.keys)
        assertEquals("ringing", payload["state"]?.jsonPrimitive?.content)
        assertEquals("", payload["number"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sms carries exactly its three keys`() {
        val payload =
            EventPayloads.sms(
                from = "+441234567890",
                body = "on my way",
                receivedAtMillis = 1_757_000_000_000,
            )
        assertEquals(setOf("from", "body", "received_at"), payload.keys)
        assertEquals("+441234567890", payload["from"]?.jsonPrimitive?.content)
        assertEquals("on my way", payload["body"]?.jsonPrimitive?.content)
    }

    @Test
    fun `connectivity carries a vocabulary transport and a boolean online`() {
        val payload = EventPayloads.connectivity(ConnectivityState(ConnectivityTransport.CELLULAR, online = false))
        assertEquals(setOf("transport", "online"), payload.keys)
        assertEquals("cellular", payload["transport"]?.jsonPrimitive?.content)
        assertEquals(false, payload["online"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `battery carries an integer level and a boolean charging`() {
        // {"level":42} and never {"level":"42"} — a quoted number reads to a parser as no number.
        val payload = EventPayloads.battery(BatteryState(level = 42, charging = true))
        assertEquals(setOf("level", "charging"), payload.keys)
        assertEquals(42, payload["level"]?.jsonPrimitive?.intOrNull)
        assertEquals(true, payload["charging"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(payload.toString().contains("\"level\":42"))
    }

    @Test
    fun `timestamps are RFC3339 in UTC`() {
        assertEquals("2025-09-04T15:33:20Z", EventPayloads.rfc3339(1_757_000_000_000))
        assertEquals("1970-01-01T00:00:00Z", EventPayloads.rfc3339(0))
    }
}
