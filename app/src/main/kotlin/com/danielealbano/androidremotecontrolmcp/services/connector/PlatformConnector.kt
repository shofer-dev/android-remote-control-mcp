@file:Suppress(
    "LongParameterList",
    "TooManyFunctions",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "LoopWithTooManyJumpStatements",
    "CyclomaticComplexMethod",
    "LongMethod",
)

package com.danielealbano.androidremotecontrolmcp.services.connector

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.services.connector.crypto.DeviceIdentity
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ActionName
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectorJson
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.WireError
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpToolServerFactory
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The outbound `/ws/device` client: dials the edge, runs the eight-frame enrol/attach
 * handshake, and bridges relay `cmd`/`action` frames onto the in-process MCP server and the
 * [DeviceActionHandler] seam. Replaces the removed public tunnel/MCP layer.
 *
 * The MCP session is built ONCE ([ensureSession]) and reused across every WebSocket reconnect;
 * see [RelayTransport] for the reasoning. Each socket is a disposable pipe: [runConnection]
 * opens it, drives the handshake, serves steady-state traffic, and returns a [ConnectionResult]
 * telling the outer loop whether to back off and retry or halt until reconfigured.
 *
 * Heartbeat, backoff and network-change awareness follow the wire spec's open questions Q1/Q9:
 * an application-level `ping` every [HEARTBEAT_INTERVAL_MS] (well inside the 90s server lapse),
 * exponential backoff with jitter, and an immediate wake on a new default network.
 */
class PlatformConnector(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val deviceIdentity: DeviceIdentity,
    private val actionHandler: DeviceActionHandler,
    private val termsBroker: TermsConsentBroker,
    private val serverFactory: McpToolServerFactory,
    private val policyEnforcer: ConnectorPolicyEnforcer,
    private val appVersion: String = BuildConfig.VERSION_NAME,
) {
    private val _status = MutableStateFlow<ConnectorStatus>(ConnectorStatus.NeedsConfig)
    val status: StateFlow<ConnectorStatus> = _status.asStateFlow()

    private val client =
        OkHttpClient
            .Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket; app-level ping is the keepalive
            .pingInterval(0, TimeUnit.MILLISECONDS) // no WS control-ping — the gateway tracks app frames only
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    private var transport: RelayTransport? = null
    private var session: ServerSession? = null

    @Volatile private var currentSocket: WebSocket? = null

    private var backoffMs = INITIAL_BACKOFF_MS
    private val wakeups = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Runs the connector until [scope] is cancelled. Reads config, ensures the MCP session,
     * and loops: connect → serve → (backoff | await reconfiguration).
     */
    suspend fun run() {
        registerNetworkCallback()
        try {
            while (scope.isActive) {
                val config = settingsRepository.getConnectorConfig()
                if (config.edgeHost.isBlank()) {
                    _status.value = ConnectorStatus.NeedsConfig
                    Log.i(TAG, "No edge host configured; waiting for configuration")
                    awaitConfigChange()
                    continue
                }
                if (!config.isEnrolled && config.enrolmentCode.isBlank()) {
                    _status.value = ConnectorStatus.NeedsConfig
                    Log.i(TAG, "Not enrolled and no enrolment code; waiting for configuration")
                    awaitConfigChange()
                    continue
                }

                ensureSession(config)

                when (val result = runConnection(config)) {
                    ConnectionResult.Reconnect -> {
                        _status.value = ConnectorStatus.Reconnecting
                        waitBeforeRetry()
                    }

                    is ConnectionResult.Halt -> {
                        _status.value = result.status
                        Log.w(TAG, "Halting reconnects: ${result.status}; awaiting reconfiguration")
                        awaitConfigChange()
                        resetBackoff()
                    }
                }
            }
        } finally {
            unregisterNetworkCallback()
            currentSocket?.cancel()
            _status.value = ConnectorStatus.Stopped
        }
    }

    /** Builds the long-lived MCP server + transport + session exactly once. */
    private suspend fun ensureSession(config: ConnectorConfig) {
        if (session != null) return
        val serverConfig = settingsRepository.getServerConfig()
        val server = serverFactory.create(serverConfig)
        val toolNamePrefix = McpToolUtils.buildToolNamePrefix(serverConfig.deviceSlug)
        // The last-hop policy gate (§6.4): every tools/call is checked against the on-device
        // DevicePolicy BEFORE it reaches the in-process MCP server.
        val gate =
            RelayTransport.CommandPolicy { toolName, params ->
                policyEnforcer.evaluate(toolName, toolNamePrefix, params)
            }
        val relay = RelayTransport(commandPolicy = gate) { /* replaced per-connection via rebind */ }
        transport = relay
        session = server.createSession(relay)
        Log.i(TAG, "MCP session established (device_id present=${config.isEnrolled})")
    }

    // ─────────────────────────────── one socket's lifetime ────────────────────────────────

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun runConnection(config: ConnectorConfig): ConnectionResult {
        _status.value = ConnectorStatus.Connecting
        val events = Channel<WsEvent>(Channel.UNLIMITED)
        val listener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    events.trySend(WsEvent.Open)
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    val frame =
                        try {
                            ConnectorJson.decodeFromString(Frame.serializer(), text)
                        } catch (e: Exception) {
                            Log.w(TAG, "Unparseable frame from gateway", e)
                            return
                        }
                    events.trySend(WsEvent.Message(frame))
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    Log.i(TAG, "Socket closing: $code $reason")
                    events.trySend(WsEvent.Closed(code, reason))
                    webSocket.close(NORMAL_CLOSURE, null)
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    events.trySend(WsEvent.Closed(code, reason))
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    Log.w(TAG, "Socket failure: ${t.message}")
                    events.trySend(WsEvent.Failure)
                }
            }

        val url = "wss://${config.edgeHost}/ws/device"
        Log.i(TAG, "Dialing $url")
        val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        currentSocket = ws
        transport?.rebind { frame -> ws.send(encode(frame)) }

        var heartbeat: Job? = null
        val machine = HandshakeMachine(config)
        try {
            for (event in events) {
                when (event) {
                    WsEvent.Open -> {
                        try {
                            machine.onOpen()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to build first handshake frame (identity error?)", e)
                            return ConnectionResult.Halt(ConnectorStatus.AttachRejected(e.message))
                        }
                    }

                    is WsEvent.Message -> {
                        val result = machine.onFrame(event.frame) { heartbeat = startHeartbeat(ws) }
                        if (result != null) return result
                    }

                    is WsEvent.Closed -> {
                        return ConnectionResult.Reconnect
                    }

                    WsEvent.Failure -> {
                        return ConnectionResult.Reconnect
                    }
                }
            }
            return ConnectionResult.Reconnect
        } finally {
            heartbeat?.cancel()
            ws.cancel()
            currentSocket = null
        }
    }

    private fun startHeartbeat(ws: WebSocket): Job =
        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                ws.send(encode(Frame(type = FrameType.PING)))
            }
        }

    // ─────────────────────────────── handshake state machine ──────────────────────────────

    private enum class HState { SENT_ENROLL, SENT_ACCEPT, SENT_ATTACH, SENT_ATTACH_SIG, ATTACHED }

    /**
     * Drives the eight-frame handshake and the steady-state routing for a single socket. The
     * result of [onFrame] is null to keep going, or a [ConnectionResult] to end the socket.
     */
    private inner class HandshakeMachine(
        private val config: ConnectorConfig,
    ) {
        private var state: HState = HState.SENT_ATTACH
        private var deviceId: String = config.deviceId
        private var pendingReAccept = false

        /**
         * The terms hash the holder just re-accepted, to be asserted in the NEXT `attach_sig`
         * (Gap B). Null on a normal attach; set when re-consenting after a `terms-required` at
         * attach; cleared once the re-attach succeeds.
         */
        private var acceptedTermsHash: String? = null

        fun onOpen() {
            if (config.isEnrolled) {
                _status.value = ConnectorStatus.Attaching
                send(Frame(type = FrameType.ATTACH, deviceId = deviceId, appVersion = appVersion))
                state = HState.SENT_ATTACH
            } else {
                _status.value = ConnectorStatus.Enrolling
                Log.i(TAG, "Enrolling with attestation tier=${deviceIdentity.attestationTier()}")
                send(
                    Frame(
                        type = FrameType.ENROLL,
                        code = config.enrolmentCode,
                        pubkey = deviceIdentity.publicKeyBase64(),
                        attestation = deviceIdentity.attestation(),
                        appVersion = appVersion,
                    ),
                )
                state = HState.SENT_ENROLL
            }
        }

        suspend fun onFrame(
            frame: Frame,
            onAttached: () -> Unit,
        ): ConnectionResult? =
            when (frame.type) {
                FrameType.PONG -> {
                    null
                }

                FrameType.TERMS -> {
                    onTerms(frame)
                }

                FrameType.ENROLLED -> {
                    onEnrolled(frame)
                }

                FrameType.CHALLENGE -> {
                    onChallenge(frame)
                }

                FrameType.ATTACHED -> {
                    onAttached(onAttached)
                }

                FrameType.CMD -> {
                    scope.launch { transport?.dispatchCommand(frame) }
                    null
                }

                FrameType.ACTION -> {
                    scope.launch { handleAction(frame) }
                    null
                }

                FrameType.ERROR -> {
                    onError(frame)
                }

                FrameType.PING -> {
                    null
                }

                // unexpected from server; harmless
                else -> {
                    Log.w(TAG, "Unknown frame type '${frame.type}'")
                    null
                }
            }

        private suspend fun onTerms(frame: Frame): ConnectionResult? {
            val hash = frame.termsHash
            if (hash == null) {
                Log.w(TAG, "terms frame without terms_hash")
                return ConnectionResult.Reconnect
            }
            if (pendingReAccept) {
                // The fresh terms that accompany a `terms-required` at attach (Gap B). Present
                // them, and on acceptance RE-ATTACH: send `attach` again to get a fresh challenge,
                // then `attach_sig` carrying the accepted `terms_hash` (see [onChallenge]). No
                // pairing code is involved — the device id is durable and the nonce signature
                // proves the live device, exactly as before; the authenticated device then asserts
                // acceptance of the current terms.
                pendingReAccept = false
                _status.value = ConnectorStatus.ReConsenting
                Log.i(TAG, "terms-required at attach; presenting fresh terms for re-consent")
                val accepted =
                    termsBroker
                        .request(TermsConsentBroker.PendingTerms(frame.termsText.orEmpty(), hash, reAcceptance = true))
                        .await()
                if (!accepted) {
                    Log.w(TAG, "User declined republished terms; halting")
                    return ConnectionResult.Halt(ConnectorStatus.TermsDeclined("republished terms declined"))
                }
                acceptedTermsHash = hash
                _status.value = ConnectorStatus.Attaching
                Log.i(TAG, "Terms re-accepted; re-attaching with the accepted terms_hash")
                send(Frame(type = FrameType.ATTACH, deviceId = deviceId, appVersion = appVersion))
                state = HState.SENT_ATTACH
                return null
            }
            _status.value = ConnectorStatus.AwaitingTermsConsent
            Log.i(TAG, "Terms received; awaiting user consent")
            val accepted =
                termsBroker
                    .request(TermsConsentBroker.PendingTerms(frame.termsText.orEmpty(), hash))
                    .await()
            if (!accepted) {
                Log.w(TAG, "User declined enrolment terms")
                return ConnectionResult.Halt(ConnectorStatus.EnrolmentRejected("enrolment terms declined"))
            }
            send(Frame(type = FrameType.ACCEPT, code = config.enrolmentCode, termsHash = hash))
            state = HState.SENT_ACCEPT
            return null
        }

        private suspend fun onEnrolled(frame: Frame): ConnectionResult? {
            val id = frame.deviceId
            if (id.isNullOrBlank()) {
                Log.w(TAG, "enrolled frame without device_id")
                return ConnectionResult.Reconnect
            }
            deviceId = id
            settingsRepository.updateConnectorEnrolled(id)
            Log.i(TAG, "Enrolled; device_id persisted. Attaching.")
            _status.value = ConnectorStatus.Attaching
            send(Frame(type = FrameType.ATTACH, deviceId = id, appVersion = appVersion))
            state = HState.SENT_ATTACH
            return null
        }

        private fun onChallenge(frame: Frame): ConnectionResult? {
            val nonce = frame.nonce
            if (nonce.isNullOrBlank()) {
                Log.w(TAG, "challenge frame without nonce")
                return ConnectionResult.Reconnect
            }
            val signature =
                try {
                    deviceIdentity.signChallenge(nonce)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sign challenge", e)
                    return ConnectionResult.Halt(ConnectorStatus.AttachRejected(e.message))
                }
            // The signing input is UNCHANGED (the nonce); `terms_hash` is an additional assertion
            // present only on a re-consent re-attach (Gap B), dropped from the wire when null.
            send(buildAttachSig(signature, acceptedTermsHash))
            state = HState.SENT_ATTACH_SIG
            return null
        }

        private fun onAttached(onAttached: () -> Unit): ConnectionResult? {
            state = HState.ATTACHED
            acceptedTermsHash = null // consumed by the successful attach; a later attach is normal
            _status.value = ConnectorStatus.Connected
            resetBackoff()
            onAttached()
            Log.i(TAG, "Attached; serving relay commands")
            return null
        }

        private fun onError(frame: Frame): ConnectionResult? {
            val code = frame.error.orEmpty()
            val details = frame.details
            Log.w(TAG, "Refusal from gateway: $code (${details ?: "no details"})")
            return when (code) {
                WireError.UPGRADE_REQUIRED -> {
                    ConnectionResult.Halt(ConnectorStatus.UpgradeRequired)
                }

                WireError.UNAUTHORIZED -> {
                    if (state == HState.SENT_ENROLL || state == HState.SENT_ACCEPT) {
                        ConnectionResult.Halt(ConnectorStatus.EnrolmentRejected(details))
                    } else {
                        ConnectionResult.Halt(ConnectorStatus.AttachRejected(details))
                    }
                }

                WireError.TERMS_REQUIRED -> {
                    if (state == HState.SENT_ATTACH_SIG || state == HState.ATTACHED) {
                        // Attach-time (Gap B): a fresh `terms` frame follows this refusal; let
                        // onTerms present it and re-attach with the accepted hash.
                        pendingReAccept = true
                        _status.value = ConnectorStatus.ReConsenting
                        null
                    } else {
                        // Accept-time mismatch (terms republished mid-ceremony): re-enrol fresh.
                        ConnectionResult.Reconnect
                    }
                }

                WireError.TERMS_UNAVAILABLE, WireError.ENROLMENT_UNAVAILABLE -> {
                    ConnectionResult.Reconnect
                }

                // transient platform state; back off and retry

                WireError.BAD_FRAME -> {
                    Log.e(TAG, "Gateway rejected a frame as bad-frame — connector protocol bug")
                    ConnectionResult.Reconnect
                }

                else -> {
                    Log.w(TAG, "Unhandled refusal code '$code'")
                    ConnectionResult.Reconnect
                }
            }
        }
    }

    private suspend fun handleAction(frame: Frame) {
        val action = frame.action.orEmpty()
        val result =
            when (val outcome = actionHandler.execute(action, frame.params)) {
                is ActionOutcome.Success -> {
                    Frame(type = FrameType.ACTION_RESULT, id = frame.id, action = action, payload = outcome.payload)
                }

                is ActionOutcome.Failure -> {
                    Frame(
                        type = FrameType.ACTION_RESULT,
                        id = frame.id,
                        action = action,
                        error = outcome.error,
                        details = outcome.details,
                    )
                }
            }
        send(result)
    }

    private fun send(frame: Frame) {
        val ws = currentSocket
        if (ws == null) {
            Log.w(TAG, "No socket to send ${frame.type}")
            return
        }
        ws.send(encode(frame))
    }

    private fun encode(frame: Frame): String = ConnectorJson.encodeToString(Frame.serializer(), frame)

    // ─────────────────────────────── backoff & network ────────────────────────────────────

    private suspend fun waitBeforeRetry() {
        val jitter = (backoffMs * JITTER_FRACTION * (Random.nextDouble() * 2 - 1)).toLong()
        val wait = (backoffMs + jitter).coerceAtLeast(0)
        Log.i(TAG, "Reconnecting in ${wait}ms")
        withTimeoutOrNull(wait) { wakeups.first() } // wake early on a network change
        backoffMs = (backoffMs * BACKOFF_FACTOR).toLong().coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun resetBackoff() {
        backoffMs = INITIAL_BACKOFF_MS
    }

    private suspend fun awaitConfigChange() {
        settingsRepository.connectorConfig.drop(1).first()
    }

    private fun registerNetworkCallback() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network available; nudging reconnect")
                    resetBackoff()
                    wakeups.tryEmit(Unit)
                }
            }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Could not unregister network callback", e)
            }
        }
        networkCallback = null
    }

    private sealed interface WsEvent {
        data object Open : WsEvent

        data class Message(
            val frame: Frame,
        ) : WsEvent

        data class Closed(
            val code: Int,
            val reason: String,
        ) : WsEvent

        data object Failure : WsEvent
    }

    private sealed interface ConnectionResult {
        data object Reconnect : ConnectionResult

        data class Halt(
            val status: ConnectorStatus,
        ) : ConnectionResult
    }

    companion object {
        private const val TAG = "MCP:Connector"

        /**
         * Builds the `attach_sig` frame. The [signature] (ed25519 over the nonce) is always
         * present; [acceptedTermsHash] is included ONLY on a re-consent re-attach (Gap B) and
         * dropped from the wire when null (ConnectorJson omits nulls). Extracted so the re-attach
         * contract — that a re-consent emits `terms_hash` and a normal attach does not — is
         * unit-testable without a live socket.
         */
        internal fun buildAttachSig(
            signature: String,
            acceptedTermsHash: String?,
        ): Frame =
            Frame(
                type = FrameType.ATTACH_SIG,
                signature = signature,
                termsHash = acceptedTermsHash,
            )

        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val NORMAL_CLOSURE = 1000
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val BACKOFF_FACTOR = 2.0
        private const val JITTER_FRACTION = 0.2
    }
}
