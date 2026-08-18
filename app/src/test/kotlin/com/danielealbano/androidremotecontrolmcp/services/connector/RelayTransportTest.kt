@file:Suppress("MaxLineLength")

package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectorJson
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the relay ↔ MCP bridge behaviour of [RelayTransport] WITHOUT a real MCP Server:
 * `AbstractTransport.onMessage`/`send` are public, so a fake handler can stand in for the
 * server. Focus is the idempotency contract (wire spec §4.2): a redelivered command under the
 * same `message_id` after a reconnect must NOT re-run the MCP request — it replays the cached
 * reply on the new relay `id`.
 */
class RelayTransportTest {
    private fun payload(json: String) = ConnectorJson.parseToJsonElement(json)

    // The transport treats the MCP payload as opaque, so the fake "server" simply echoes the
    // decoded request back through send() — enough to drive correlation and dedupe. (Building a
    // real typed response is unnecessary and the SDK's result serializer rejects a synthetic one.)
    private fun echoHandler(transport: RelayTransport): suspend (JSONRPCMessage) -> Unit = { msg -> transport.send(msg) }

    @Test
    fun `command is answered with a reply correlated by jsonrpc id`() =
        runTest {
            val outbox = mutableListOf<Frame>()
            val transport = RelayTransport { outbox.add(it) }
            transport.onMessage(echoHandler(transport))

            transport.dispatchCommand(
                Frame(
                    type = FrameType.CMD,
                    id = "relay-1",
                    messageId = "m1",
                    payload = payload("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""),
                ),
            )

            assertEquals(1, outbox.size)
            assertEquals(FrameType.REPLY, outbox[0].type)
            assertEquals("relay-1", outbox[0].id)
            assertTrue(outbox[0].payload.toString().contains("tools/list"))
        }

    @Test
    fun `redelivered command replays the cached reply without re-invoking the server`() =
        runTest {
            val outbox = mutableListOf<Frame>()
            var invocations = 0
            val transport = RelayTransport { outbox.add(it) }
            transport.onMessage { msg ->
                invocations++
                transport.send(msg)
            }

            val cmd =
                Frame(
                    type = FrameType.CMD,
                    id = "relay-1",
                    messageId = "m1",
                    payload = payload("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""),
                )

            transport.dispatchCommand(cmd)
            // Reconnect: the gateway redelivers the SAME message_id under a new exchange id.
            transport.dispatchCommand(cmd.copy(id = "relay-2"))

            assertEquals(1, invocations, "the MCP request must run exactly once")
            assertEquals(2, outbox.size, "both deliveries get a reply")
            assertEquals("relay-1", outbox[0].id)
            assertEquals("relay-2", outbox[1].id, "the replay carries the new relay id")
            // Same payload replayed.
            assertEquals(outbox[0].payload!!.jsonObject, outbox[1].payload!!.jsonObject)
        }
}
