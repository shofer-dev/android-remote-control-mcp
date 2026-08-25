package com.danielealbano.androidremotecontrolmcp.services.connector

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The relay's central guarantee, pinned: **every `cmd` carrying a relay id produces exactly one
 * `reply`.** A caller two hops away is blocked on that reply, so a command that neither runs nor
 * answers is indistinguishable from a dead device and costs the caller its whole timeout.
 *
 * The `session not built` case is the one that matters most and the reason this file exists: the
 * dispatch used to be `transport?.dispatchCommand(frame)`, whose safe-call swallowed the command
 * with no reply, no dispatch and no log line. There was no seam to write this test at, which is
 * most of why that shape was invisible.
 */
@DisplayName("RelayCommandServer")
class RelayCommandServerTest {
    private val sent = mutableListOf<Frame>()
    private val dispatched = mutableListOf<Frame>()
    private val indicator = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `an allowed call is dispatched to the MCP sink`() =
        runTest {
            val server = server(decision = PolicyDecision.Allowed, sink = recordingSink())

            val outcome = server.serve(callFrame())

            assertEquals(CommandOutcome.Dispatched, outcome)
            assertEquals(1, dispatched.size)
            assertEquals(RELAY_ID, dispatched.single().id)
            // The sink owns the reply on this path (RelayTransport correlates it), so the server
            // itself writes nothing.
            assertTrue(sent.isEmpty())
        }

    @Test
    fun `a call arriving before the MCP session exists is answered, never dropped`() =
        runTest {
            // THE REGRESSION. `transport?.dispatchCommand(frame)` made this branch silent: no
            // dispatch, no reply, no log — the gateway could only time out.
            val server = server(decision = PolicyDecision.Allowed, sink = null)

            val outcome = server.serve(callFrame())

            assertEquals(CommandOutcome.NotReady, outcome)
            assertTrue(dispatched.isEmpty())
            val reply = sent.single()
            assertEquals(FrameType.REPLY, reply.type)
            assertEquals(RELAY_ID, reply.id)
            assertEquals(RelayCommandServer.NOT_READY, reply.error)
        }

    @Test
    fun `a refused call is answered with the typed code and never reaches the sink`() =
        runTest {
            val refusal = PolicyDecision.Refused(error = "policy-paused", details = "paused by the platform")
            val server = server(decision = refusal, sink = recordingSink())

            val outcome = server.serve(callFrame())

            assertEquals(CommandOutcome.Refused("policy-paused"), outcome)
            assertTrue(dispatched.isEmpty())
            val reply = sent.single()
            assertEquals("policy-paused", reply.error)
            assertEquals("paused by the platform", reply.details)
        }

    @Test
    fun `policy is evaluated before the sink is consulted`() =
        runTest {
            // A refusal must not reach the MCP session at all — the device's own half of the
            // pause, which is what makes it hold when the dispatcher is wrong.
            var sinkAsked = false
            val server =
                RelayCommandServer(
                    evaluatePolicy = { PolicyDecision.Refused("policy-unavailable", "no snapshot") },
                    onCommandStarted = { indicator += "start" },
                    onCommandFinished = { indicator += "finish" },
                    sink = {
                        sinkAsked = true
                        recordingSink()
                    },
                    send = { sent += it },
                )

            server.serve(callFrame())

            assertTrue(!sinkAsked)
            assertTrue(indicator.isEmpty())
        }

    @Test
    fun `the activity indicator brackets a dispatch`() =
        runTest {
            val server = server(decision = PolicyDecision.Allowed, sink = recordingSink())

            server.serve(callFrame())

            assertEquals(listOf("start", "finish"), indicator)
        }

    @Test
    fun `the activity indicator is released when the sink throws`() =
        runTest {
            // Otherwise a failed command leaves the device showing the being-driven border
            // forever, which is the transparency signal lying in the dangerous direction.
            val server =
                server(
                    decision = PolicyDecision.Allowed,
                    sink = RelayCommandSink { error("dispatch blew up") },
                )

            runCatching { server.serve(callFrame()) }

            assertEquals(listOf("start", "finish"), indicator)
        }

    @Test
    fun `a cmd with no relay id is dropped, because there is nothing to answer`() =
        runTest {
            val server = server(decision = PolicyDecision.Allowed, sink = recordingSink())

            val outcome = server.serve(callFrame().copy(id = null))

            assertEquals(CommandOutcome.Unaddressable, outcome)
            assertTrue(dispatched.isEmpty())
            assertTrue(sent.isEmpty())
        }

    @Test
    fun `a non-tool payload still rides the same single-reply guarantee`() =
        runTest {
            // `initialize` and `tools/list` are Allowed by the enforcer without inspection; they
            // must reach the session exactly like a call does.
            val server = server(decision = PolicyDecision.Allowed, sink = recordingSink())

            val listPayload = payload("""{"jsonrpc":"2.0","id":9,"method":"tools/list"}""")
            val outcome = server.serve(callFrame(payload = listPayload))

            assertEquals(CommandOutcome.Dispatched, outcome)
            assertEquals(1, dispatched.size)
        }

    @Test
    fun `an unparseable payload is still answered rather than dropped`() =
        runTest {
            // The descriptor is total, so a payload it cannot read must not divert the frame out
            // of the reply guarantee — the SDK produces the JSON-RPC error downstream.
            val server = server(decision = PolicyDecision.Allowed, sink = null)

            val outcome = server.serve(callFrame(payload = null))

            assertEquals(CommandOutcome.NotReady, outcome)
            assertNull(sent.single().payload)
            assertEquals(RelayCommandServer.NOT_READY, sent.single().error)
        }

    private fun recordingSink() = RelayCommandSink { frame -> dispatched += frame }

    private fun server(
        decision: PolicyDecision,
        sink: RelayCommandSink?,
    ) = RelayCommandServer(
        evaluatePolicy = { decision },
        onCommandStarted = { indicator += "start" },
        onCommandFinished = { indicator += "finish" },
        sink = { sink },
        send = { sent += it },
    )

    private companion object {
        const val RELAY_ID = "relay-7f3a"

        val json = Json { ignoreUnknownKeys = true }

        fun payload(raw: String): JsonElement = json.parseToJsonElement(raw)

        fun callFrame(
            payload: JsonElement? =
                payload(
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"android_tap","arguments":{}}}""",
                ),
        ): Frame =
            Frame(
                type = FrameType.CMD,
                id = RELAY_ID,
                messageId = "msg-1",
                payload = payload,
            )
    }
}
