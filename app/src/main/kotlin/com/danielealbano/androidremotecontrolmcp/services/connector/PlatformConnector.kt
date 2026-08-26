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
import android.os.SystemClock
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.crypto.DeviceIdentity
import com.danielealbano.androidremotecontrolmcp.services.connector.indicator.RemoteActivityIndicator
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.PolicyEnforcer
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ActionName
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectorJson
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.WireError
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpToolServerFactory
import com.danielealbano.androidremotecontrolmcp.utils.MonotonicClock
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The outbound `/ws/device` client: dials the gateway, runs the eight-frame enrol/attach
 * handshake, and bridges relay `cmd`/`action` frames onto the in-process MCP server and the
 * [DeviceActionHandler] seam. Replaces the removed public tunnel/MCP layer.
 *
 * The dial target is resolved by [resolveDialUrl] from [ConnectorConfig] (precedence: a full
 * `gatewayUrl` used verbatim, else `wss://<edgeHost>/ws/device`). The verbatim path is how an
 * emulated in-cluster device reaches its internal gateway service — a plain `ws://` URL with an
 * explicit port, which the public-edge `wss://<host>` form cannot express; physical/tethered
 * devices take the `edgeHost` fallback. OkHttp handles the `ws://` scheme and the explicit port
 * with no client-side change.
 *
 * The MCP session is built ONCE ([ensureSession]) and reused across every WebSocket reconnect;
 * see [RelayTransport] for the reasoning. Each socket is a disposable pipe: [runConnection]
 * opens it, drives the handshake, serves steady-state traffic, and returns a [ConnectionResult]
 * telling the outer loop whether to back off and retry or halt until reconfigured.
 *
 * Heartbeat, backoff and network-change awareness follow the wire spec's open questions Q1/Q9:
 * an application-level `ping` every [ConnectorLiveness.HEARTBEAT_INTERVAL_MS] (well inside the
 * 90s server lapse), exponential backoff with jitter, and an immediate wake on a new default
 * network.
 *
 * ── Status is grounded in what the SERVER confirms ────────────────────────────────────
 * The published [status] is never "we hold a socket object". [ConnectorStatus.Connected] is
 * entered only on the gateway's `attached` frame, and it carries the moment the gateway last
 * ANSWERED — the `attached` frame itself, then each `pong`. A watchdog rearmed on every answer
 * ends the connection once [ConnectorLiveness.STALE_AFTER_MS] passes with silence, so a half-open
 * socket becomes [ConnectorStatus.Reconnecting] and a fresh dial, rather than a notification that
 * goes on claiming the device is reachable.
 *
 * ── Policy enforcement (`docs/phones/android_remote_control.md` §6.4) ──────────────────
 * The gateway sends a `policy` frame immediately after `attached` and again whenever the
 * state it carries changes. Two things follow, and both are enforcement rather than display:
 *
 * - **Every `cmd` is evaluated by [PolicyEnforcer] BEFORE it reaches [RelayTransport]**, so a
 *   refusal never touches the MCP session and never touches the device. The refusal rides
 *   back as `reply{id, error, details}`, which the gateway propagates to the caller
 *   unchanged. The enforcer is cleared when a socket ends, so a reconnect drives nothing
 *   until a fresh snapshot arrives — "attached but unpoliced" is a refusing state, not a
 *   permissive one.
 * - **Outside the active-hours window the connector DETACHES**, on arrival of a snapshot that
 *   is already closed and on the watchdog that fires when an open window closes mid-session.
 *   The server refusing is policy; the device refusing is the guarantee, and a device that
 *   holds no socket cannot be commanded by a dispatcher bug at all. It then sleeps until the
 *   window reopens rather than dialling on a backoff that would be refused all night.
 */
class PlatformConnector(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val deviceIdentity: DeviceIdentity,
    private val actionHandler: DeviceActionHandler,
    private val termsBroker: TermsConsentBroker,
    private val serverFactory: McpToolServerFactory,
    private val policyEnforcer: PolicyEnforcer,
    private val activityIndicator: RemoteActivityIndicator,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val clock: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() },
) {
    private val _status = MutableStateFlow<ConnectorStatus>(ConnectorStatus.NeedsConfig)
    val status: StateFlow<ConnectorStatus> = _status.asStateFlow()

    private val client =
        OkHttpClient
            .Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket; app-level ping is the keepalive
            .pingInterval(0, TimeUnit.MILLISECONDS) // no WS control-ping — the gateway tracks app frames only
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // The platform socket must NEVER traverse a device-configured HTTP
            // proxy. On a managed device the gateway URL is a loopback tunnel
            // (`ws://127.0.0.1:…`), and a global proxy that captures it hands the
            // dial to a proxy that cannot reach the tunnel — observed live as the
            // proxy answering 403 and the device never attaching (the platform
            // sets a global proxy for governed internet, and Android routed even
            // loopback through it despite the exclusion list). A direct socket is
            // also the predictable posture on a phone with a corporate proxy.
            .proxy(Proxy.NO_PROXY)
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
                if (!hasDialTarget(config)) {
                    _status.value = ConnectorStatus.NeedsConfig
                    Log.i(TAG, "No gateway URL or edge host configured; waiting for configuration")
                    awaitConfigChange()
                    continue
                }
                if (!config.isEnrolled && config.enrolmentCode.isBlank()) {
                    _status.value = ConnectorStatus.NotEnrolled
                    Log.i(TAG, "Not enrolled and no enrolment code; waiting for configuration")
                    awaitConfigChange()
                    continue
                }

                ensureSession(config)

                when (val result = runConnection(config)) {
                    ConnectionResult.Reconnect -> {
                        val wait = nextRetryDelayMs()
                        _status.value = ConnectorStatus.Reconnecting(nextRetryAtMillis = clock.nowMillis() + wait)
                        waitBeforeRetry(wait)
                    }

                    is ConnectionResult.OutsideActiveHours -> {
                        // Deliberately NOT interruptible by a network wakeup: the window is
                        // a wall-clock fact, and a new radio does not reopen it. Sleeping the
                        // closed stretch is what keeps an overnight window from being an
                        // all-night reconnect storm.
                        _status.value =
                            ConnectorStatus.OutsideActiveHours(reopensAtMillis = clock.nowMillis() + result.sleepMillis)
                        Log.i(TAG, "Outside the active-hours window; detached for ${result.sleepMillis}ms")
                        delay(result.sleepMillis)
                        resetBackoff()
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
        val relay = RelayTransport { /* replaced per-connection via rebind */ }
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

        val url =
            when (val resolved = resolveDialUrl(config)) {
                is DialResolution.Ok -> {
                    resolved.url
                }

                is DialResolution.Invalid -> {
                    Log.e(TAG, "Resolved dial URL has an unsupported scheme; halting: ${resolved.url}")
                    return ConnectionResult.Halt(ConnectorStatus.Misconfigured("unsupported gateway URL scheme"))
                }
            }
        Log.i(TAG, "Dialing $url")
        val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        currentSocket = ws
        transport?.rebind { frame -> ws.send(encode(frame)) }

        var heartbeat: Job? = null
        var windowWatchdog: Job? = null
        var staleWatchdog: Job? = null
        // Every server answer rearms the watchdog, so silence — not a missing FIN — is what ends
        // a half-open socket.
        val onServerAnswer = {
            markServerHeartbeat()
            staleWatchdog?.cancel()
            staleWatchdog = armStaleWatchdog(events)
        }
        val machine = HandshakeMachine(config)
        try {
            for (event in events) {
                when (event) {
                    WsEvent.HeartbeatLapsed -> {
                        Log.w(TAG, "Gateway silent for ${ConnectorLiveness.STALE_AFTER_MS}ms; the link is lost")
                        return ConnectionResult.Reconnect
                    }

                    WsEvent.ActiveHoursClosed -> {
                        // The watchdog fired: an open window closed while the socket was up.
                        return ConnectionResult.OutsideActiveHours(sleepUntilWindowOpens())
                    }

                    WsEvent.Open -> {
                        try {
                            machine.onOpen()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to build first handshake frame (identity error?)", e)
                            return ConnectionResult.Halt(ConnectorStatus.AttachRejected(e.message))
                        }
                    }

                    is WsEvent.Message -> {
                        val result =
                            if (event.frame.type == FrameType.PONG) {
                                onServerAnswer()
                                null
                            } else {
                                machine.onFrame(
                                    event.frame,
                                    onAttached = {
                                        heartbeat = startHeartbeat(ws)
                                        staleWatchdog = armStaleWatchdog(events)
                                    },
                                ) {
                                    windowWatchdog?.cancel()
                                    windowWatchdog = startWindowWatchdog(events)
                                }
                            }
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
            windowWatchdog?.cancel()
            staleWatchdog?.cancel()
            // The policy dies with the socket that delivered it: a reconnect refuses every
            // command until a fresh snapshot arrives. A policy that outlived its connection
            // is a policy the platform may already have changed.
            policyEnforcer.clear()
            activityIndicator.reset()
            ws.cancel()
            currentSocket = null
        }
    }

    private fun startHeartbeat(ws: WebSocket): Job =
        scope.launch {
            while (isActive) {
                delay(ConnectorLiveness.HEARTBEAT_INTERVAL_MS)
                ws.send(encode(Frame(type = FrameType.PING)))
            }
        }

    /**
     * Arms the liveness watchdog. It is rearmed on every server answer, so it only ever fires
     * after a whole [ConnectorLiveness.STALE_AFTER_MS] of silence — at which point the socket is
     * ended rather than merely relabelled, because a link the platform has stopped answering on
     * cannot be recovered by waiting on it.
     */
    private fun armStaleWatchdog(events: Channel<WsEvent>): Job =
        scope.launch {
            delay(ConnectorLiveness.STALE_AFTER_MS)
            events.trySend(WsEvent.HeartbeatLapsed)
        }

    /** Records that the gateway answered, refreshing the timestamp the UI reads freshness from. */
    private fun markServerHeartbeat() {
        val now = clock.nowMillis()
        _status.update { current ->
            if (current is ConnectorStatus.Attached) current.withHeartbeat(now) else current
        }
    }

    /**
     * Publishes the attached status with [paused] applied, preserving the link's own timestamps
     * so a policy snapshot never resets the uptime the holder is reading.
     */
    private fun publishAttached(paused: Boolean) {
        _status.update { current ->
            val attached = current as? ConnectorStatus.Attached
            val since = attached?.attachedSinceMillis ?: clock.nowMillis()
            val beat = attached?.lastServerHeartbeatMillis ?: clock.nowMillis()
            if (paused) {
                ConnectorStatus.Paused(since, beat)
            } else {
                ConnectorStatus.Connected(since, beat)
            }
        }
    }

    /**
     * Arms the mid-session active-hours watchdog. A socket attached at 21:59 under an
     * `08:00-22:00` window has to detach itself a minute later WITHOUT a command arriving to
     * trigger the check — otherwise a device that nobody drives at 21:59 stays attached and
     * drivable all night, and the window would only be enforced by the commands it refuses.
     */
    private fun startWindowWatchdog(events: Channel<WsEvent>): Job? {
        val closesIn = policyEnforcer.millisUntilWindowCloses()
        if (closesIn <= 0) return null
        return scope.launch {
            delay(closesIn)
            events.trySend(WsEvent.ActiveHoursClosed)
        }
    }

    /**
     * How long to stay detached once the window has closed, floored so a policy this app
     * could not make sense of can never turn into a dial-refuse-dial spin.
     */
    private fun sleepUntilWindowOpens(): Long {
        val untilOpen = policyEnforcer.millisUntilWindowOpens()
        return untilOpen.coerceAtLeast(MIN_OUT_OF_HOURS_SLEEP_MS)
    }

    /**
     * Serves one relayed `cmd`. The rule — policy first, then the loopback MCP hop, and always
     * exactly one reply — lives in [RelayCommandServer], which is testable without a socket.
     * `sink` reads [transport] lazily because the session is built on the first pass of [run],
     * after this object is constructed.
     */
    private val commandServer =
        RelayCommandServer(
            evaluatePolicy = policyEnforcer::evaluate,
            onCommandStarted = activityIndicator::onCommandStarted,
            onCommandFinished = activityIndicator::onCommandFinished,
            sink = { transport },
            send = { frame -> send(frame) },
        )

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
            onPolicyApplied: () -> Unit,
        ): ConnectionResult? =
            when (frame.type) {
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

                FrameType.POLICY -> {
                    onPolicy(frame, onPolicyApplied)
                }

                FrameType.CMD -> {
                    scope.launch { commandServer.serve(frame) }
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

        /**
         * Applies a policy snapshot. Two outcomes: the window is open, so the snapshot takes
         * effect and the mid-session watchdog is (re)armed; or the window is already closed,
         * in which case the connector DETACHES rather than sitting attached refusing every
         * command — the server refusing is policy, holding no socket is the guarantee.
         */
        private fun onPolicy(
            frame: Frame,
            onPolicyApplied: () -> Unit,
        ): ConnectionResult? {
            val snapshot = frame.policy
            if (snapshot == null) {
                // A policy frame with no snapshot leaves the device unable to say what it may
                // do. Reconnecting is the honest answer: the enforcer is cleared on the way
                // out, so nothing is driven until a well-formed snapshot arrives.
                Log.w(TAG, "policy frame without a snapshot; reconnecting for a fresh one")
                return ConnectionResult.Reconnect
            }
            policyEnforcer.apply(snapshot)
            if (policyEnforcer.isOutsideActiveHours()) {
                return ConnectionResult.OutsideActiveHours(sleepUntilWindowOpens())
            }
            publishAttached(paused = snapshot.paused)
            onPolicyApplied()
            return null
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
            // The `attached` frame is itself a server answer, so it seeds the liveness clock:
            // the link starts fresh and the watchdog has a base even before the first pong.
            val now = clock.nowMillis()
            _status.value = ConnectorStatus.Connected(attachedSinceMillis = now, lastServerHeartbeatMillis = now)
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

    /**
     * The jittered wait before the next dial. Computed separately from [waitBeforeRetry] so the
     * published [ConnectorStatus.Reconnecting] can name the deadline the UI counts down to.
     */
    private fun nextRetryDelayMs(): Long {
        val jitter = (backoffMs * JITTER_FRACTION * (Random.nextDouble() * 2 - 1)).toLong()
        return (backoffMs + jitter).coerceAtLeast(0)
    }

    private suspend fun waitBeforeRetry(wait: Long) {
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

        /** The active-hours watchdog fired: an open window closed while the socket was up. */
        data object ActiveHoursClosed : WsEvent

        /** The liveness watchdog fired: the gateway stopped answering on a socket still held. */
        data object HeartbeatLapsed : WsEvent
    }

    private sealed interface ConnectionResult {
        data object Reconnect : ConnectionResult

        /**
         * The active-hours window is closed. The connector detaches and stays detached for
         * [sleepMillis] — not a backoff, a wall-clock wait for the window to reopen.
         */
        data class OutsideActiveHours(
            val sleepMillis: Long,
        ) : ConnectionResult

        data class Halt(
            val status: ConnectorStatus.Halted,
        ) : ConnectionResult
    }

    /**
     * The outcome of resolving a [ConnectorConfig] into a dial URL: either a usable WebSocket URL
     * or an unsupported scheme that must halt the loop rather than dial garbage.
     */
    internal sealed interface DialResolution {
        /** A valid `ws://` or `wss://` URL to dial. */
        data class Ok(
            val url: String,
        ) : DialResolution

        /** The resolved URL has a scheme that is neither `ws://` nor `wss://`. */
        data class Invalid(
            val url: String,
        ) : DialResolution
    }

    companion object {
        private const val TAG = "MCP:Connector"

        /**
         * True when [config] carries at least one dial target — a full `gatewayUrl` or an
         * `edgeHost`. When both are blank there is nothing to dial and the loop waits for
         * configuration (the [ConnectorStatus.NeedsConfig] guard in [run]).
         */
        internal fun hasDialTarget(config: ConnectorConfig): Boolean {
            val hasGateway = config.gatewayUrl.isNotBlank()
            return hasGateway || config.edgeHost.isNotBlank()
        }

        /**
         * Resolves the dial URL from [config]. Precedence: a non-blank [ConnectorConfig.gatewayUrl]
         * is used VERBATIM (the in-cluster `ws://host:port/ws/device` path); otherwise the
         * [ConnectorConfig.edgeHost] fallback yields `wss://<edgeHost>/ws/device` (the
         * physical/tethered path). The resolved URL must carry a WebSocket scheme — a value whose
         * scheme is neither `ws://` nor `wss://` is reported as [DialResolution.Invalid] so the
         * caller halts with [ConnectorStatus.Misconfigured] instead of handing OkHttp a bad URL.
         * Extracted so the precedence and validation are unit-testable without a live socket.
         */
        internal fun resolveDialUrl(config: ConnectorConfig): DialResolution {
            val url =
                if (config.gatewayUrl.isNotBlank()) {
                    config.gatewayUrl
                } else {
                    "wss://${config.edgeHost}/ws/device"
                }
            return if (url.startsWith("ws://") || url.startsWith("wss://")) {
                DialResolution.Ok(url)
            } else {
                DialResolution.Invalid(url)
            }
        }

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

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val NORMAL_CLOSURE = 1000
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val BACKOFF_FACTOR = 2.0
        private const val JITTER_FRACTION = 0.2

        /**
         * The floor on an out-of-hours sleep. A window the app could not make sense of, or a
         * clock that moves under us, must never turn the detach into a dial-refuse-dial spin.
         */
        private const val MIN_OUT_OF_HOURS_SLEEP_MS = 60_000L
    }
}
