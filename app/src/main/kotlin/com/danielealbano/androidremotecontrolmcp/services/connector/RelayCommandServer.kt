package com.danielealbano.androidremotecontrolmcp.services.connector

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.CommandDescriptor
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import kotlinx.serialization.json.JsonElement

/** Where a permitted command goes. [RelayTransport] is the only production implementation. */
fun interface RelayCommandSink {
    suspend fun dispatchCommand(frame: Frame)
}

/**
 * What serving one relayed `cmd` did. Returned rather than logged-and-forgotten so the outcome
 * is assertable in a test — the seam whose absence let a silent drop ship (see [RelayCommandServer]).
 */
sealed interface CommandOutcome {
    /** The device's own policy refused it; a typed `reply` carrying [error] was written back. */
    data class Refused(
        val error: String,
    ) : CommandOutcome

    /** Handed to the MCP session. */
    data object Dispatched : CommandOutcome

    /** No MCP session yet; a `not-ready` reply was written back rather than nothing. */
    data object NotReady : CommandOutcome

    /** No relay id, so there is nothing to correlate a reply to. Logged and dropped. */
    data object Unaddressable : CommandOutcome
}

/**
 * Serves one relayed `cmd`: evaluate the platform's policy FIRST, and only then hand the payload
 * to the loopback MCP hop.
 *
 * ## Why this is its own class
 *
 * It was `PlatformConnector.serveCommand`, a private method reachable only through a live
 * WebSocket, an eight-frame handshake and a real MCP session — which meant the single most
 * important guarantee in the relay path had no test and no seam to write one at. The guarantee is:
 *
 * **every `cmd` carrying a relay id produces exactly one `reply`.** The caller two hops away is
 * blocked on it; a command that neither runs nor answers is indistinguishable, from the console,
 * from a device that has stopped existing — and it burns the caller's whole timeout before saying
 * so.
 *
 * The extracted version closes the one path that broke that guarantee. Dispatch used to read
 * `transport?.dispatchCommand(frame)`: when the session had not been built the safe-call operator
 * swallowed the command whole — no dispatch, no reply, no log, not even at verbose. [NotReady]
 * now answers with the same typed code [RelayTransport] already uses for its own version of this
 * race, so the caller learns why instead of timing out.
 *
 * ## Logging
 *
 * One line when a command ARRIVES (naming the tool) and one when it LEAVES (dispatched, refused
 * with its code, or not-ready). Before this, a healthy `tools/call` produced no log output at all
 * on the happy path, so "the frame never arrived" and "the frame arrived and vanished" looked
 * identical in logcat — which is most of why the silent drop above was hard to see. These two
 * lines are the difference between an hour of guessing and one `adb logcat` grep.
 *
 * Collaborators arrive as function references rather than as their concrete classes: this needs a
 * policy DECISION and two indicator callbacks, not a whole [
 * com.danielealbano.androidremotecontrolmcp.services.connector.policy.PolicyEnforcer] and its
 * device environment — so the test needs no mocking framework and cannot drift from what the
 * production wiring actually passes.
 */
class RelayCommandServer(
    private val evaluatePolicy: (JsonElement?) -> PolicyDecision,
    private val onCommandStarted: () -> Unit,
    private val onCommandFinished: () -> Unit,
    private val sink: () -> RelayCommandSink?,
    private val send: suspend (Frame) -> Unit,
) {
    suspend fun serve(frame: Frame): CommandOutcome {
        val label = describe(frame)
        val relayId = frame.id
        return if (relayId == null) {
            Log.w(TAG, "cmd ($label) arrived without a relay id; dropping — nothing to reply to")
            CommandOutcome.Unaddressable
        } else {
            Log.i(TAG, "cmd $relayId arrived: $label")
            serveAddressed(frame, relayId, label)
        }
    }

    /**
     * The three outcomes an addressable command can have. Written as one exhaustive `when` so
     * that adding a branch cannot accidentally add a path which returns without replying — the
     * defect this class was extracted to make impossible.
     */
    private suspend fun serveAddressed(
        frame: Frame,
        relayId: String,
        label: String,
    ): CommandOutcome {
        val decision = evaluatePolicy(frame.payload)
        val target = if (decision is PolicyDecision.Allowed) sink() else null
        return when {
            decision is PolicyDecision.Refused -> refuse(relayId, label, decision)
            target == null -> notReady(relayId, label)
            else -> dispatch(frame, relayId, label, target)
        }
    }

    private suspend fun refuse(
        relayId: String,
        label: String,
        decision: PolicyDecision.Refused,
    ): CommandOutcome {
        Log.i(TAG, "cmd $relayId ($label) refused on-device: ${decision.error}")
        send(
            Frame(
                type = FrameType.REPLY,
                id = relayId,
                error = decision.error,
                details = decision.details,
            ),
        )
        return CommandOutcome.Refused(decision.error)
    }

    private suspend fun notReady(
        relayId: String,
        label: String,
    ): CommandOutcome {
        Log.e(TAG, "cmd $relayId ($label) arrived before the MCP session was built; replying $NOT_READY")
        send(Frame(type = FrameType.REPLY, id = relayId, error = NOT_READY, details = NOT_READY_DETAILS))
        return CommandOutcome.NotReady
    }

    private suspend fun dispatch(
        frame: Frame,
        relayId: String,
        label: String,
        target: RelayCommandSink,
    ): CommandOutcome {
        onCommandStarted()
        try {
            target.dispatchCommand(frame)
        } finally {
            onCommandFinished()
        }
        Log.i(TAG, "cmd $relayId ($label) dispatched to the MCP session")
        return CommandOutcome.Dispatched
    }

    /** The tool name when there is one, else the JSON-RPC method — enough to grep logcat by. */
    private fun describe(frame: Frame): String {
        val descriptor = CommandDescriptor.parse(frame.payload)
        return descriptor.toolName ?: descriptor.method ?: "unparseable-payload"
    }

    companion object {
        private const val TAG = "MCP:RelayCommand"

        /**
         * The typed code for "the device is up but its MCP session is not". Declared here and
         * used by [RelayTransport] too, so the spelling the gateway propagates exists once.
         */
        const val NOT_READY = "not-ready"

        private const val NOT_READY_DETAILS =
            "the device is attached but its MCP session is not built yet; retry shortly"
    }
}
