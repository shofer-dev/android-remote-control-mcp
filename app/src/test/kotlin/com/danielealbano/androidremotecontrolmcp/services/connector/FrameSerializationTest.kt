@file:Suppress("MaxLineLength")

package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.services.connector.events.BatteryState
import com.danielealbano.androidremotecontrolmcp.services.connector.events.DeviceEvent
import com.danielealbano.androidremotecontrolmcp.services.connector.events.DeviceEventReporter
import com.danielealbano.androidremotecontrolmcp.services.connector.events.EventPayloads
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ActionName
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Capability
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectorJson
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.StreamError
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.WireError
import com.danielealbano.androidremotecontrolmcp.services.selfupdate.UpdateRefusal
import com.danielealbano.androidremotecontrolmcp.services.selfupdate.UpdateSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the wire encoding of [Frame] (wire spec §2): the snake_case field names, and the
 * `omitempty` contract — a frame we build must not emit `null`/absent fields, or a stricter
 * gateway could reject it, and an accept frame that leaks `pubkey: null` would be a subtly
 * wrong envelope. Also checks that unknown inbound fields are ignored.
 */
class FrameSerializationTest {
    private fun encode(frame: Frame): String = ConnectorJson.encodeToString(Frame.serializer(), frame)

    private fun keys(json: String): Set<String> = ConnectorJson.parseToJsonElement(json).jsonObject.keys

    @Test
    fun `enroll frame emits exactly its populated snake_case fields`() {
        val json =
            encode(
                Frame(
                    type = FrameType.ENROLL,
                    code = "PAIR-123",
                    pubkey = "cHVia2V5",
                    appVersion = "1.4.2",
                ),
            )
        val k = keys(json)
        assertEquals(setOf("type", "code", "pubkey", "app_version"), k)
        assertTrue(json.contains("\"app_version\":\"1.4.2\""))
        // omitempty: no null phone_id / terms_hash / signature leaked
        assertFalse(json.contains("null"))
    }

    @Test
    fun `accept frame carries code and terms_hash only`() {
        val json = encode(Frame(type = FrameType.ACCEPT, code = "PAIR-123", termsHash = "abcd"))
        assertEquals(setOf("type", "code", "terms_hash"), keys(json))
    }

    @Test
    fun `attach frame carries phone_id and app_version`() {
        val json = encode(Frame(type = FrameType.ATTACH, phoneId = "uuid-1", appVersion = "1.4.2"))
        assertEquals(setOf("type", "phone_id", "app_version"), keys(json))
    }

    @Test
    fun `an attach that advertises nothing emits no capabilities key at all`() {
        // The capability vocabulary has no negative form: the platform reads a SET, and absence is
        // the whole way to say "cannot". An empty array would be a second spelling of the same
        // answer, so the omitempty contract has to hold here or a phone could appear to be making
        // a claim it is not.
        val json = encode(Frame(type = FrameType.ATTACH, phoneId = "uuid-1", appVersion = "1.4.2", capabilities = null))
        assertFalse(json.contains("capabilities"))
    }

    @Test
    fun `an attach that can stream advertises the capability`() {
        val json =
            encode(
                Frame(
                    type = FrameType.ATTACH,
                    phoneId = "uuid-1",
                    appVersion = "1.4.2",
                    capabilities = listOf(Capability.SCREEN_STREAM),
                ),
            )
        assertEquals(setOf("type", "phone_id", "app_version", "capabilities"), keys(json))
        assertTrue(json.contains("\"capabilities\":[\"screen_stream\"]"))
    }

    @Test
    fun `stream_ready carries only the stream id`() {
        val json = encode(Frame(type = FrameType.STREAM_READY, id = "stream-1"))
        assertEquals(setOf("type", "id"), keys(json))
    }

    @Test
    fun `stream_ended carries the typed refusal the platform branches on`() {
        val json =
            encode(
                Frame(
                    type = FrameType.STREAM_ENDED,
                    id = "stream-1",
                    error = StreamError.UNSUPPORTED,
                    details = "this phone has no live screen-capture consent",
                ),
            )
        assertEquals(setOf("type", "id", "error", "details"), keys(json))
    }

    @Test
    fun `stream_start decodes its stream id`() {
        val frame =
            ConnectorJson.decodeFromString(
                Frame.serializer(),
                """{"type":"stream_start","id":"stream-1"}""",
            )
        assertEquals(FrameType.STREAM_START, frame.type)
        assertEquals("stream-1", frame.id)
    }

    @Test
    fun `attach_sig frame carries only the signature`() {
        val json = encode(Frame(type = FrameType.ATTACH_SIG, signature = "c2ln"))
        assertEquals(setOf("type", "signature"), keys(json))
    }

    @Test
    fun `ping frame is just the type`() {
        assertEquals("{\"type\":\"ping\"}", encode(Frame(type = FrameType.PING)))
    }

    @Test
    fun `challenge frame decodes the nonce`() {
        val frame =
            ConnectorJson.decodeFromString(
                Frame.serializer(),
                """{"type":"challenge","nonce":"a3f1deadbeef"}""",
            )
        assertEquals(FrameType.CHALLENGE, frame.type)
        assertEquals("a3f1deadbeef", frame.nonce)
    }

    @Test
    fun `terms frame decodes text and hash`() {
        val frame =
            ConnectorJson.decodeFromString(
                Frame.serializer(),
                """{"type":"terms","terms_text":"hello","terms_hash":"deadbeef"}""",
            )
        assertEquals("hello", frame.termsText)
        assertEquals("deadbeef", frame.termsHash)
    }

    @Test
    fun `cmd frame decodes id, message_id and the opaque payload`() {
        val frame =
            ConnectorJson.decodeFromString(
                Frame.serializer(),
                """{"type":"cmd","id":"x1","message_id":"m1","payload":{"jsonrpc":"2.0","id":1,"method":"tools/list"}}""",
            )
        assertEquals("x1", frame.id)
        assertEquals("m1", frame.messageId)
        assertEquals(
            "tools/list",
            frame.payload!!
                .jsonObject["method"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `unknown inbound fields are ignored`() {
        val frame =
            ConnectorJson.decodeFromString(
                Frame.serializer(),
                """{"type":"attached","phone_id":"uuid-1","future_field":123}""",
            )
        assertEquals(FrameType.ATTACHED, frame.type)
        assertEquals("uuid-1", frame.phoneId)
        assertNull(frame.error)
    }

    @Test
    fun `an attach carrying the keyguard emits screen_locked, and false is emitted not dropped`() {
        // `false` is a POSITIVE report — the one thing the gateway's refusal-derived inference can
        // never express — so it must survive the omitempty contract. A serializer that dropped it
        // would silently downgrade every unlocked phone to "the gateway has no idea".
        val unlocked = encode(Frame(type = FrameType.ATTACH, phoneId = "uuid-1", screenLocked = false))
        assertEquals(setOf("type", "phone_id", "screen_locked"), keys(unlocked))
        assertTrue(unlocked.contains("\"screen_locked\":false"))

        val locked = encode(Frame(type = FrameType.SCREEN_STATE, screenLocked = true))
        assertEquals(setOf("type", "screen_locked"), keys(locked))
        assertTrue(locked.contains("\"screen_locked\":true"))
    }

    @Test
    fun `an attach from a phone whose OS could not be asked emits no screen_locked key`() {
        // Null is the third state: unknown. Sending it as `false` would be the one unrecoverable
        // lie — the gateway treats a reported `false` as authoritative and stops inferring.
        val json = encode(Frame(type = FrameType.ATTACH, phoneId = "uuid-1", screenLocked = null))
        assertFalse(json.contains("screen_locked"))
    }

    @Test
    fun `update_check carries only its app-minted id`() {
        // The one frame on this leg whose id the DEVICE mints. Anything else riding along would be
        // a field the gateway's handler does not expect on a type it may not know at all.
        val json = encode(Frame(type = FrameType.UPDATE_CHECK, id = "check-1"))
        assertEquals(setOf("type", "id"), keys(json))
    }

    @Test
    fun `update_info decodes the published build out of params`() {
        val frame =
            ConnectorJson.decodeFromString(
                Frame.serializer(),
                """{"type":"update_info","id":"check-1","params":{"url":"https://e.invalid/a.apk",""" +
                    """"sha256":"abcd","version":"g953943d7f944"}}""",
            )
        assertEquals(FrameType.UPDATE_INFO, frame.type)
        assertEquals("check-1", frame.id)
        val spec = UpdateSpec.parse(frame.params)!!
        assertEquals("https://e.invalid/a.apk", spec.url)
        assertEquals("abcd", spec.sha256)
        assertEquals("g953943d7f944", spec.version)
    }

    @Test
    fun `an update_app action decodes through the same params parser as update_info`() {
        // One triple, one parser, both legs — a field the gateway spelled differently on one of
        // them must not be silently tolerated on the other.
        val frame =
            ConnectorJson.decodeFromString(
                Frame.serializer(),
                """{"type":"action","id":"srv-1","action":"update_app","params":{"url":"https://e.invalid/a.apk",""" +
                    """"sha256":"abcd","version":"g953943d7f944"}}""",
            )
        assertEquals(ActionName.UPDATE_APP, frame.action)
        assertEquals(
            UpdateSpec("https://e.invalid/a.apk", "abcd", "g953943d7f944"),
            UpdateSpec.parse(frame.params),
        )
    }

    @Test
    fun `an update_app action_result carries accepted as a JSON boolean`() {
        // {"accepted": true}, never {"accepted": "true"} — the platform parses a boolean, and a
        // quoted one is the kind of mismatch that reads as a device that never answered.
        val json =
            encode(
                Frame(
                    type = FrameType.ACTION_RESULT,
                    id = "srv-1",
                    action = ActionName.UPDATE_APP,
                    payload = PlatformDeviceActionHandler.ACCEPTED_PAYLOAD,
                ),
            )
        assertEquals(setOf("type", "id", "action", "payload"), keys(json))
        assertTrue(json.contains("""{"accepted":true}"""))
    }

    @Test
    fun `a typed update refusal rides the ordinary action_result error path`() {
        val json =
            encode(
                Frame(
                    type = FrameType.ACTION_RESULT,
                    id = "srv-1",
                    action = ActionName.UPDATE_APP,
                    error = UpdateRefusal.CHECKSUM_MISMATCH,
                    details = "the fetched APK does not match the sha256 the platform declared",
                ),
            )
        assertEquals(setOf("type", "id", "action", "error", "details"), keys(json))
        assertTrue(json.contains("\"error\":\"checksum-mismatch\""))
    }

    @Test
    fun `error frame decodes code and details`() {
        val frame =
            ConnectorJson.decodeFromString(
                Frame.serializer(),
                """{"type":"error","error":"unauthorized","details":"credential-service refused: revoked"}""",
            )
        assertEquals("unauthorized", frame.error)
        assertTrue(frame.details!!.contains("revoked"))
    }

    @Test
    fun `an event frame carries exactly type, category, occurred_at and payload`() {
        // The device-event plane's one outbound shape (`docs/phone/device_events.md` §3). The
        // gateway gates `category` against its own vocabulary and forwards `payload` untouched, so
        // a fourth key here would be a field nothing reads and a renamed one an absent field.
        val json =
            encode(
                DeviceEventReporter.frameFor(
                    DeviceEvent(
                        category = DeviceEventCategory.BATTERY,
                        occurredAtMillis = 1_757_000_000_000,
                        payload = EventPayloads.battery(BatteryState(level = 42, charging = true)),
                    ),
                ),
            )
        assertEquals(setOf("type", "category", "occurred_at", "payload"), keys(json))
        assertTrue(json.contains("\"type\":\"event\""))
        assertTrue(json.contains("\"category\":\"battery\""))
        assertTrue(json.contains("\"occurred_at\":\"2025-09-04T15:33:20Z\""))
        assertTrue(json.contains("""{"level":42,"charging":true}"""))
        assertFalse(json.contains("null"))
    }

    @Test
    fun `every category's frame spells its wire name`() {
        DeviceEventCategory.entries.forEach { category ->
            val frame =
                DeviceEventReporter.frameFor(
                    DeviceEvent(category, occurredAtMillis = 0, payload = buildJsonObject { }),
                )
            assertEquals(category.wire, frame.category)
        }
        assertEquals(
            setOf("notification", "call", "sms", "connectivity", "battery"),
            DeviceEventCategory.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `an unknown-event-category refusal decodes with the rejected spelling in details`() {
        // The gateway ANSWERS rather than dropping, because it is always the older half of this
        // pair — so a category it does not know is a bug in this build, and `details` names it.
        val frame =
            ConnectorJson.decodeFromString(
                Frame.serializer(),
                """{"type":"error","error":"unknown-event-category","details":"geofence"}""",
            )
        assertEquals(WireError.UNKNOWN_EVENT_CATEGORY, frame.error)
        assertEquals("geofence", frame.details)
    }

    @Test
    fun `a non-event frame emits neither category nor occurred_at`() {
        val json = encode(Frame(type = FrameType.PING))
        assertFalse(json.contains("category"))
        assertFalse(json.contains("occurred_at"))
    }
}
