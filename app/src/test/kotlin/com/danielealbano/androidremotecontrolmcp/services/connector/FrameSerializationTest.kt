@file:Suppress("MaxLineLength")

package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Capability
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectorJson
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.StreamError
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
        // omitempty: no null device_id / terms_hash / signature leaked
        assertFalse(json.contains("null"))
    }

    @Test
    fun `accept frame carries code and terms_hash only`() {
        val json = encode(Frame(type = FrameType.ACCEPT, code = "PAIR-123", termsHash = "abcd"))
        assertEquals(setOf("type", "code", "terms_hash"), keys(json))
    }

    @Test
    fun `attach frame carries device_id and app_version`() {
        val json = encode(Frame(type = FrameType.ATTACH, deviceId = "uuid-1", appVersion = "1.4.2"))
        assertEquals(setOf("type", "device_id", "app_version"), keys(json))
    }

    @Test
    fun `an attach that advertises nothing emits no capabilities key at all`() {
        // The capability vocabulary has no negative form: the platform reads a SET, and absence is
        // the whole way to say "cannot". An empty array would be a second spelling of the same
        // answer, so the omitempty contract has to hold here or a phone could appear to be making
        // a claim it is not.
        val json = encode(Frame(type = FrameType.ATTACH, deviceId = "uuid-1", appVersion = "1.4.2", capabilities = null))
        assertFalse(json.contains("capabilities"))
    }

    @Test
    fun `an attach that can stream advertises the capability`() {
        val json =
            encode(
                Frame(
                    type = FrameType.ATTACH,
                    deviceId = "uuid-1",
                    appVersion = "1.4.2",
                    capabilities = listOf(Capability.SCREEN_STREAM),
                ),
            )
        assertEquals(setOf("type", "device_id", "app_version", "capabilities"), keys(json))
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
                """{"type":"attached","device_id":"uuid-1","future_field":123}""",
            )
        assertEquals(FrameType.ATTACHED, frame.type)
        assertEquals("uuid-1", frame.deviceId)
        assertNull(frame.error)
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
}
