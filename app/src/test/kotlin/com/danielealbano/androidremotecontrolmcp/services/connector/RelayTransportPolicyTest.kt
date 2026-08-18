@file:Suppress("MaxLineLength")

package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectorJson
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the last-hop policy enforcement inside [RelayTransport] (§6.4): a denied `tools/call`
 * is answered with an isError result WITHOUT ever reaching the MCP session, while an allowed
 * call flows through to the server.
 */
class RelayTransportPolicyTest {
    private fun payload(json: String) = ConnectorJson.parseToJsonElement(json)

    private val toolsCall =
        """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"android_tap","arguments":{"x":1,"y":2}}}"""

    @Test
    fun `a denied tools_call is refused with an isError reply and never reaches the server`() =
        runTest {
            val outbox = mutableListOf<Frame>()
            var invocations = 0
            val gate =
                RelayTransport.CommandPolicy { _, _ ->
                    ConnectorPolicyEnforcer.Decision.Deny("policy-outside-active-hours", "nope")
                }
            val transport = RelayTransport(commandPolicy = gate) { outbox.add(it) }
            transport.onMessage { msg ->
                invocations++
                transport.send(msg)
            }

            transport.dispatchCommand(
                Frame(type = FrameType.CMD, id = "relay-1", messageId = "m1", payload = payload(toolsCall)),
            )

            assertEquals(0, invocations, "server must not be invoked for a denied call")
            assertEquals(1, outbox.size)
            assertEquals("relay-1", outbox[0].id)
            val body = outbox[0].payload.toString()
            assertTrue(body.contains("isError"), "reply should be an isError result: $body")
            assertTrue(body.contains("policy-outside-active-hours"), "reply should carry the typed code: $body")
            assertTrue(body.contains("\"id\":7"), "reply must echo the request id: $body")
        }

    @Test
    fun `an allowed tools_call flows through to the server`() =
        runTest {
            val outbox = mutableListOf<Frame>()
            var invocations = 0
            val gate = RelayTransport.CommandPolicy { _, _ -> ConnectorPolicyEnforcer.Decision.Allow }
            val transport = RelayTransport(commandPolicy = gate) { outbox.add(it) }
            transport.onMessage { msg ->
                invocations++
                transport.send(msg)
            }

            transport.dispatchCommand(
                Frame(type = FrameType.CMD, id = "relay-2", messageId = "m2", payload = payload(toolsCall)),
            )

            assertEquals(1, invocations, "allowed call must reach the server")
            assertEquals(1, outbox.size)
            assertEquals("relay-2", outbox[0].id)
        }
}
