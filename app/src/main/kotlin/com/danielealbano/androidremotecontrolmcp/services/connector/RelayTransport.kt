@file:Suppress("TooGenericExceptionCaught", "ReturnCount", "MaxLineLength")

package com.danielealbano.androidremotecontrolmcp.services.connector

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The bridge between the platform relay envelope and the app's in-process MCP [Server].
 *
 * It is an [AbstractTransport], so `Server.createSession(this)` gives it the full MCP surface
 * (`initialize`, `tools/list`, `tools/call`, notifications) with no port bound and no auth
 * layer — the exact seam the fork map §3.2 identifies. Two directions:
 *
 * - INBOUND (edge → device): the connector hands each relay `cmd{id, message_id, payload}`
 *   to [dispatchCommand]. The `payload` is an opaque MCP JSON-RPC message; we feed it to the
 *   SDK by invoking `_onMessage`, and remember which relay exchange it belongs to.
 * - OUTBOUND (device → edge): the SDK calls [send] with the JSON-RPC response; we correlate
 *   it back to the originating relay exchange and write a `reply{id, payload}` frame.
 *
 * ── Lifecycle across reconnects (fork map §11 decision) ────────────────────────────────
 * ONE long-lived [RelayTransport] + ONE [io.modelcontextprotocol.kotlin.sdk.server.ServerSession]
 * span the connector's whole life, across any number of WebSocket reconnects. The socket is a
 * REPLACEABLE pipe under a STABLE transport: [rebind] swaps the outbound writer when a new
 * socket attaches, but [close] (which tears the MCP session down) is invoked only on final
 * connector shutdown, never on a socket blip.
 *
 * This is the correct MCP semantics, not a convenience. `initialize` negotiates the protocol
 * version and capabilities ONCE for a session; the platform relay caller (user-console's L2)
 * holds a single continuous MCP session whose liveness is independent of the phone's radio.
 * Re-`createSession` on every reconnect would hand the caller a fresh, UNINITIALIZED session,
 * so its next `tools/call` — sent on the belief the session is initialized — would fail. The
 * gateway reinforces this: it holds an in-flight `cmd` and redelivers it under the SAME `id`
 * on the new socket (wire spec §4.5), which only lines up if the session that will answer is
 * the same one that received it.
 *
 * ── Idempotency (wire spec §4.2) ───────────────────────────────────────────────────────
 * A redelivered `cmd` after a reconnect arrives under the same `id`/`message_id`. Re-feeding
 * its payload would run the MCP request twice. Instead [dispatchCommand] serves a completed
 * exchange from [replyCache] (bounded, FIFO — the same 32-entry bound the gateway uses),
 * re-sending the identical `reply` on the new socket, and never re-invokes the tool.
 *
 * ── Last-hop policy enforcement (§6.4) ─────────────────────────────────────────────────
 * Before an inbound `tools/call` reaches the MCP session, [commandPolicy] (when supplied)
 * evaluates it against the on-device [DevicePolicy] — the layer closest to the act, which a
 * dispatcher bug or a prompt-injected agent cannot route around. A refusal never reaches the
 * tool: the exchange completes with an isError MCP result carrying the typed policy code, and
 * `_onMessage` is never invoked. Non-`tools/call` messages and an absent policy pass straight
 * through.
 *
 * @param commandPolicy optional last-hop gate; null disables on-device enforcement (e.g. tests).
 * @param outbound writes a frame to the CURRENT socket; suspends; may no-op if disconnected.
 */
class RelayTransport(
    private val commandPolicy: CommandPolicy? = null,
    @Volatile private var outbound: suspend (Frame) -> Unit,
) : AbstractTransport() {
    private val lock = Any()

    /** The last-hop gate: evaluates a `tools/call` (un-prefixing handled by the impl). */
    fun interface CommandPolicy {
        suspend fun evaluate(
            toolName: String,
            params: JsonElement?,
        ): ConnectorPolicyEnforcer.Decision
    }

    /** jsonrpc-id → the relay exchange that carried the request, awaiting its response. */
    private val inflight = HashMap<String, RelayExchange>()

    /** Fallback for a response whose jsonrpc id we could not correlate (single in-flight). */
    private var lastExchange: RelayExchange? = null

    /** dedupe key (message_id, else relay id) → the completed reply frame to replay. */
    private val replyCache =
        object : LinkedHashMap<String, Frame>(REPLAY_CACHE_INITIAL, REPLAY_CACHE_LOAD, false) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Frame>?): Boolean = size > REPLAY_CACHE_MAX
        }

    override suspend fun start() {
        // No-op: the WebSocket lifecycle is owned by PlatformConnector. The transport is a
        // stable pipe that outlives any single socket, so there is nothing to open here.
    }

    /** Swaps the outbound writer to the newly-attached socket. Called on every (re)attach. */
    fun rebind(newOutbound: suspend (Frame) -> Unit) {
        outbound = newOutbound
    }

    /**
     * Routes an inbound relay `cmd` into the MCP session, or serves it from the replay cache
     * if it is a redelivery of an already-completed exchange.
     */
    suspend fun dispatchCommand(frame: Frame) {
        val relayId = frame.id
        if (relayId == null) {
            Log.w(TAG, "cmd without id; dropping")
            return
        }
        val dedupeKey = frame.messageId ?: relayId
        val payload = frame.payload
        if (payload == null) {
            Log.w(TAG, "cmd $relayId without payload; dropping")
            return
        }

        val cached = synchronized(lock) { replyCache[dedupeKey] }
        if (cached != null) {
            // Redelivery after a reconnect: re-send the SAME reply on the current socket
            // rather than re-running the MCP request (idempotency, wire spec §4.2).
            Log.i(TAG, "cmd $relayId is a redelivery of $dedupeKey; replaying cached reply")
            outbound(cached.copy(id = relayId))
            return
        }

        val jsonRpcId = jsonRpcId(payload)
        val exchange = RelayExchange(relayId = relayId, dedupeKey = dedupeKey)
        synchronized(lock) {
            if (jsonRpcId != null) inflight[jsonRpcId] = exchange
            lastExchange = exchange
        }

        val message =
            try {
                McpJson.decodeFromString<JSONRPCMessage>(payload.toString())
            } catch (e: Exception) {
                Log.w(TAG, "cmd $relayId payload is not a JSON-RPC message", e)
                completeExchange(exchange, Frame(type = FrameType.REPLY, id = relayId, error = "bad-payload", details = e.message))
                return
            }

        // _onMessage is set by Server.createSession before any command can be dispatched (the
        // session is built once at connector startup). Typed nullable so an early call — before
        // the session wires it up — fails soft rather than NPEs.
        val onMessage: (suspend (JSONRPCMessage) -> Unit)? = _onMessage
        if (onMessage == null) {
            Log.w(TAG, "MCP session not connected yet; cannot dispatch cmd $relayId")
            completeExchange(exchange, Frame(type = FrameType.REPLY, id = relayId, error = "not-ready"))
            return
        }

        // Last-hop policy enforcement (§6.4): refuse a disallowed tools/call BEFORE it reaches
        // the in-process MCP server, returning a typed error as an isError tool result.
        val deny = evaluatePolicy(payload)
        if (deny != null) {
            Log.i(TAG, "cmd $relayId refused by device policy: ${deny.code}")
            completeExchange(exchange, Frame(type = FrameType.REPLY, id = relayId, payload = denyResult(payload, deny)))
            return
        }

        onMessage.invoke(message)
    }

    /**
     * Runs [commandPolicy] against an inbound message iff it is a `tools/call`. Returns the
     * refusal to enforce, or null to let the message through (allowed, not a tool call, or no
     * policy configured).
     */
    private suspend fun evaluatePolicy(payload: JsonElement): ConnectorPolicyEnforcer.Decision.Deny? {
        val policy = commandPolicy ?: return null
        val obj = runCatching { payload.jsonObject }.getOrNull() ?: return null
        if (obj["method"]?.jsonPrimitive?.contentOrNull != TOOLS_CALL_METHOD) return null
        val params = obj["params"]
        val toolName =
            runCatching {
                params
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
        if (toolName == null) return null
        val args = runCatching { params?.jsonObject?.get("arguments") }.getOrNull()
        return when (val decision = policy.evaluate(toolName, args)) {
            is ConnectorPolicyEnforcer.Decision.Deny -> decision
            ConnectorPolicyEnforcer.Decision.Allow -> null
        }
    }

    /** Builds an isError MCP `tools/call` result echoing the request id, for a policy refusal. */
    private fun denyResult(
        requestPayload: JsonElement,
        deny: ConnectorPolicyEnforcer.Decision.Deny,
    ): JsonElement {
        val idElement = runCatching { requestPayload.jsonObject["id"] }.getOrNull() ?: JsonNull
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", idElement)
            putJsonObject("result") {
                putJsonArray("content") {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "${deny.code}: ${deny.message}")
                        },
                    )
                }
                put("isError", true)
            }
        }
    }

    override suspend fun send(
        message: JSONRPCMessage,
        options: TransportSendOptions?,
    ) {
        val element = McpJson.encodeToJsonElement(JSONRPCMessage.serializer(), message)
        val jsonRpcId = jsonRpcId(element)

        val exchange =
            synchronized(lock) {
                (jsonRpcId?.let { inflight.remove(it) } ?: lastExchange).also { lastExchange = null }
            }
        if (exchange == null) {
            // A server-initiated notification (no id) has no relay exchange to ride back on;
            // the relay is strictly request/response, so there is nowhere to deliver it.
            Log.d(TAG, "outbound MCP message with no correlating relay exchange (id=$jsonRpcId); dropping")
            return
        }

        val reply = Frame(type = FrameType.REPLY, id = exchange.relayId, payload = element)
        completeExchange(exchange, reply)
    }

    override suspend fun close() {
        // Final connector shutdown only. Tears down the MCP session via _onClose (which the
        // AbstractTransport base initialises to a non-null no-op, so this is always safe).
        val onClose: (() -> Unit)? = _onClose
        onClose?.invoke()
    }

    private suspend fun completeExchange(
        exchange: RelayExchange,
        reply: Frame,
    ) {
        synchronized(lock) { replyCache[exchange.dedupeKey] = reply }
        outbound(reply)
    }

    /** Reads the JSON-RPC `id` from a frame body, as a stable string key (id may be str/num). */
    private fun jsonRpcId(element: kotlinx.serialization.json.JsonElement): String? =
        try {
            element.jsonObject["id"]?.toString()
        } catch (_: Exception) {
            null
        }

    private data class RelayExchange(
        val relayId: String,
        val dedupeKey: String,
    )

    companion object {
        private const val TAG = "MCP:RelayTransport"
        private const val TOOLS_CALL_METHOD = "tools/call"
        private const val REPLAY_CACHE_MAX = 32
        private const val REPLAY_CACHE_INITIAL = 16
        private const val REPLAY_CACHE_LOAD = 0.75f
    }
}
