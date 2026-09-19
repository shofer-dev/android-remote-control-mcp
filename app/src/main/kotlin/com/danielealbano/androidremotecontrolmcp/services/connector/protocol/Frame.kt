package com.danielealbano.androidremotecontrolmcp.services.connector.protocol

import com.danielealbano.androidremotecontrolmcp.services.connector.policy.DevicePolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The single flat envelope of the `/ws/phone` wire protocol.
 *
 * One JSON object per WebSocket message serves BOTH directions and every frame kind
 * (matching `phone-gateway/internal/protocol/protocol.go` `Frame`). The [type] field is
 * the discriminator; every other field is optional (`omitempty` on the Go side). On the
 * Kotlin side that maps to nullable properties encoded with [ConnectorJson], whose
 * `explicitNulls = false` + `encodeDefaults = false` config drops any null/absent field
 * from the wire — so a frame we build carries exactly the fields the gateway expects and
 * no spurious `"field": null` that a stricter server might reject.
 *
 * Unknown INBOUND fields are ignored (`ignoreUnknownKeys = true`): the envelope is shared
 * with the host leg's evolution and the gateway may add fields we do not model.
 *
 * Field semantics and encodings are pinned to the wire spec (§2):
 * - [pubkey]      base64 of the raw 32-byte ed25519 public key.
 * - [attestation] any JSON value; Android key-attestation material, stored verbatim.
 * - [nonce]       attach challenge, 64 lowercase hex characters.
 * - [signature]   base64 ed25519 signature over the ASCII bytes of the [nonce] STRING.
 * - [payload]     opaque MCP JSON-RPC frame (relay leg), an action_result body, or the
 *                 per-category body of an [FrameType.EVENT] report.
 * - [category]    the device-event category ([DeviceEventCategory]) an `event` frame reports.
 * - [occurredAt]  when that event happened, RFC3339, on the HANDSET's own clock.
 * - [id]          transport-scoped exchange id, server-minted on `cmd`/`action`.
 * - [messageId]   sender-minted logical idempotency key (relay leg), may be absent.
 * - [error]       the gateway's typed refusal code ([WireError]).
 * - [reason]      the platform's machine-readable refusal reason UNDERNEATH that code
 *                 ([RefusalReason]); absent when the gateway had none to forward.
 * - [details]     prose for a human. Nothing branches on it.
 * - [capabilities] the SET of optional things this build can do on THIS phone ([Capability]),
 *                  restated on every `attach` and again whenever the answer changes. A set with no
 *                  negative form: absent and "cannot" must decide the same way, so an app that
 *                  advertises nothing is refused the capability rather than discovered to lack it
 *                  halfway through using it.
 */
@Serializable
data class Frame(
    val type: String,
    val id: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    val code: String? = null,
    val pubkey: String? = null,
    val attestation: JsonElement? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("phone_id") val phoneId: String? = null,
    @SerialName("terms_text") val termsText: String? = null,
    @SerialName("terms_hash") val termsHash: String? = null,
    val nonce: String? = null,
    val signature: String? = null,
    val payload: JsonElement? = null,
    /**
     * The category of an [FrameType.EVENT] report — one of [DeviceEventCategory]'s wire spellings.
     *
     * Absent on every other frame type. The gateway gates it against its own vocabulary and answers
     * [WireError.UNKNOWN_EVENT_CATEGORY] to a spelling it does not know, rather than dropping the
     * frame, so a mis-spelled category is a bug this connector's author can see.
     */
    val category: String? = null,
    /**
     * When the reported event happened, RFC3339, read from the HANDSET's own wall clock.
     *
     * The phone's clock rather than the gateway's arrival time, because an event is a statement
     * about the device's world: a report delayed by a reconnect must still say when it happened,
     * not when it was finally delivered.
     */
    @SerialName("occurred_at") val occurredAt: String? = null,
    val action: String? = null,
    val params: JsonElement? = null,
    val policy: DevicePolicy? = null,
    val error: String? = null,
    val reason: String? = null,
    val details: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    val capabilities: List<String>? = null,
    /**
     * This phone's keyguard, on the attach frame and on every [FrameType.SCREEN_STATE] frame
     * (`protocol.go` `Frame.ScreenLocked`). NULL is a third state and is sent as an absent field:
     * it means the OS could not be asked, and the platform then falls back to inferring lockedness
     * from `policy-screen-locked` refusals. Never guess `false` here — the gateway treats a
     * reported `false` as authoritative and stops inferring.
     */
    @SerialName("screen_locked") val screenLocked: Boolean? = null,
)

/**
 * Frame `type` discriminator values, verbatim from `protocol.go:14-35`.
 *
 * Device → gateway are the ones this connector SENDS; gateway → device are the ones it
 * receives. Spelling is load-bearing — an unknown `type` is answered `bad-frame`.
 */
object FrameType {
    // Device → gateway
    const val ENROLL = "enroll"
    const val ACCEPT = "accept"
    const val ATTACH = "attach"
    const val ATTACH_SIG = "attach_sig"
    const val PING = "ping"
    const val REPLY = "reply"
    const val ACTION_RESULT = "action_result"

    /** Screen capture is live and the media is flowing: `id` is the stream id. */
    const val STREAM_READY = "stream_ready"

    /**
     * Screen capture stopped: `id` is the stream id, and `error`/`details` say why when it was a
     * failure rather than a requested stop. It is what the platform hears when the OS ends a
     * projection under us — the screen locked, another app started projecting — so a viewer is torn
     * down and told, instead of watching a channel that quietly stopped producing.
     */
    const val STREAM_ENDED = "stream_ended"

    /**
     * A capability set that changed while attached ([Frame.capabilities]). The connector sends it
     * so a phone armed (or disarmed) by its holder mid-session starts, or stops, being offered
     * video without waiting for a reconnect that may be hours away.
     */
    const val CAPABILITIES = "capabilities"

    /**
     * The keyguard changed under a live socket ([Frame.screenLocked]). The connector sends it so a
     * handset locked or unlocked mid-session is shown as such AT ONCE — without it the platform
     * can only infer lockedness from refusals, which never expresses "unlocked" and clears only
     * on the next command that succeeds.
     */
    const val SCREEN_STATE = "screen_state"

    /**
     * "What build do you publish for this device?" — sent once per attach and again whenever the
     * holder asks the connector card to refresh. `id` is APP-minted here, which is the one place
     * this leg mints one: every other correlated exchange is started by the gateway.
     *
     * An older gateway that does not know the type simply ignores it, so the answer may never
     * come. That is a timeout, not a fault ([SelfUpdater][com.danielealbano.androidremotecontrolmcp.services.selfupdate.SelfUpdater]
     * reports it as "unknown"), and nothing on the device waits on it.
     */
    const val UPDATE_CHECK = "update_check"

    /**
     * A device-event report (`docs/phone/device_events.md` §3): `{category, occurred_at, payload}`.
     *
     * The phone leg's ONE unsolicited frame — the twin of the host leg's `host_telephony_event` —
     * and the only one this connector sends that answers no question. Nothing waits on it and it is
     * never acknowledged: an event is ADVISORY, so a report the gateway sheds is lost rather than
     * queued, and the device is deliberately not told.
     *
     * Forwarding is decided BEFORE the frame is built, by the intersection of the holder's
     * per-category toggles and the platform's policy
     * ([EventGate][com.danielealbano.androidremotecontrolmcp.services.connector.events.EventGate]).
     */
    const val EVENT = "event"

    // Gateway → device
    const val TERMS = "terms"
    const val ENROLLED = "enrolled"
    const val CHALLENGE = "challenge"
    const val ATTACHED = "attached"
    const val PONG = "pong"
    const val CMD = "cmd"
    const val ACTION = "action"

    /**
     * The device-enforced policy snapshot (`protocol.go` `TypePolicy`). Sent immediately
     * after [ATTACHED], and again whenever the state it carries changes — today a pause or a
     * resume on the `phone-use` / `phone-manage` plane.
     */
    const val POLICY = "policy"
    const val ERROR = "error"

    /**
     * The answer to an [UPDATE_CHECK], carrying the same `id` back and the published build in
     * `params` — `{url, sha256, version}`, the identical triple an [ActionName.UPDATE_APP] action
     * carries, so one parser serves both legs.
     *
     * An absent or empty `version` means nothing is published, which is a real answer and not an
     * error: it is how a platform with no build for this device says so.
     */
    const val UPDATE_INFO = "update_info"

    /** Begin screen capture: `id` is the stream id every later frame must name. */
    const val STREAM_START = "stream_start"

    /** End screen capture: `id` is the stream id. */
    const val STREAM_STOP = "stream_stop"
}

/**
 * The optional capabilities a device may advertise, verbatim from
 * `phone-gateway/internal/protocol/host.go`.
 *
 * The vocabulary is shared with the device-HOST leg on purpose: "can this thing put a screen on the
 * wire" is one question the platform asks of three different device classes, and one spelling is
 * what lets a single gateway read answer it.
 */
object Capability {
    /**
     * This phone can serve a live screen stream RIGHT NOW without anybody touching it.
     *
     * It is not a statement about the build. On Android 14 a capture session needs fresh user
     * consent unless a live projection is already held, so this is advertised exactly while one IS
     * held — which is the only form of the claim the console can safely act on, since it chooses
     * the viewer's transport before any stream is attempted.
     */
    const val SCREEN_STREAM = "screen_stream"
}

/**
 * The typed codes this connector may put in a [FrameType.STREAM_ENDED] frame's `error`, matching
 * `phone-gateway/internal/protocol/protocol.go`.
 */
object StreamError {
    /** This phone cannot capture: no live consent, no usable encoder, or the OS ended the grant. */
    const val UNSUPPORTED = "stream-unsupported"

    /** A second stream was asked for while one was running. One screen, one encoder. */
    const val BUSY = "stream_busy"
}

/**
 * The seven typed refusal codes that can actually arrive on the device socket (wire spec §5).
 * The other six declared codes (`phone_offline`/`phone_busy`/… and `action-unsupported`,
 * `not-applicable`) only ever reach HTTP callers, never a device, so they are not modelled
 * here — but a handler must branch defensively since they share the vocabulary.
 *
 * Note the spelling split, exactly as the Go constants read: these seven are hyphenated.
 */
object WireError {
    const val BAD_FRAME = "bad-frame"
    const val ENROLMENT_UNAVAILABLE = "enrolment-unavailable"
    const val TERMS_UNAVAILABLE = "terms-unavailable"
    const val TERMS_REQUIRED = "terms-required"
    const val UPGRADE_REQUIRED = "upgrade-required"
    const val UNAUTHORIZED = "unauthorized"

    /**
     * An [FrameType.EVENT] whose `category` this gateway does not know; `details` names the
     * spelling that was rejected (`protocol.ErrUnknownEventCategory`).
     *
     * It is a CONNECTOR bug rather than version skew, which is why the gateway answers it instead
     * of dropping the frame quietly: the platform distributes the APK, so a gateway is always
     * upgraded before the fleet it serves and an app can never legitimately be ahead of it. So the
     * connector logs it DISTINCTLY — at error, naming the category — and carries on: nothing on the
     * device waits on an event frame, so there is no exchange to fail and no reconnect to trigger.
     */
    const val UNKNOWN_EVENT_CATEGORY = "unknown-event-category"
}

/**
 * The platform's refusal REASONS, carried in [Frame.reason] underneath a [WireError] code.
 *
 * They are minted by credential-service (the device-identity authority) and forwarded verbatim
 * by the gateway, which never authors or interprets one. They exist because [WireError] alone is
 * too coarse to act on: EVERY attach verdict arrives as [WireError.UNAUTHORIZED], so without the
 * reason a device cannot tell an identity the platform has ERASED from one an administrator has
 * deliberately REVOKED — and the correct responses to those are opposites.
 *
 * Only [UNKNOWN_DEVICE] changes what this app does; the rest are declared because a reader of the
 * refusal branch needs to see what was CONSIDERED and deliberately excluded, not just what was
 * matched. A reason outside this vocabulary is treated as "not unknown-device" — the safe side,
 * since the only behaviour keyed on it destroys the device's identity.
 */
object RefusalReason {
    /**
     * The platform holds NO record of this phone id — a deleted device record, or an identity
     * from a platform this phone is no longer paired with. The stored identity is void by
     * definition: nothing the device can send under it will ever be accepted, so the app discards
     * it and provisions again ([com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorProvisioning]).
     */
    const val UNKNOWN_DEVICE = "unknown-device"

    /**
     * An administrator revoked this device. The record EXISTS and the refusal is deliberate, so
     * the identity is kept: discarding it would let the phone silently re-enrol as a brand-new
     * device and undo the revocation.
     */
    const val REVOKED = "revoked"

    /**
     * The nonce signature did not verify against the enrolled public key. The record exists and is
     * trusted; the fault is local (a key that rotated, an OEM signer producing non-raw ed25519).
     * Discarding the identity would convert a signing regression into a fleet-wide re-pairing.
     */
    const val BAD_SIGNATURE = "bad-signature"

    /** A re-consent whose accepted terms hash is not the org's published one. Consent, not identity. */
    const val TERMS_MISMATCH = "terms-mismatch"

    /** The attach frame reached the authority without a nonce — a protocol fault, not an identity one. */
    const val MISSING_NONCE = "missing-nonce"

    /** The attach frame reached the authority without a signature — likewise a protocol fault. */
    const val MISSING_SIGNATURE = "missing-signature"
}

/**
 * The device-action names the gateway may push (wire spec §6.1, `protocol.go`
 * `SupportedActions`).
 *
 * `pause`/`resume` are NOT here, and that is the platform's shape rather than an omission:
 * they are gateway-local state, so they never arrive as an `action` frame. The device learns
 * about a pause through [FrameType.POLICY] instead — the gateway re-sends the snapshot with
 * `paused` set, and the connector's own refusal is what makes a pause hold even if the
 * dispatcher that is supposed to honour it is wrong.
 */
object ActionName {
    const val LOCK = "lock"
    const val WIPE = "wipe"
    const val LOCATE = "locate"
    const val RING = "ring"

    /**
     * Apply a published build to this device. The ONE action with a defined `params` schema —
     * `{url, sha256, version}`, read as an
     * [UpdateSpec][com.danielealbano.androidremotecontrolmcp.services.selfupdate.UpdateSpec].
     *
     * Its `action_result` is also the one that cannot report completion: a successful install
     * replaces this process, so the success payload `{"accepted": true}` is sent as soon as the
     * APK is fetched, verified and handed to the OS installer. The platform observes the rest by
     * this device re-attaching with a new `app_version`.
     */
    const val UPDATE_APP = "update_app"
}

/**
 * The JSON configuration used for every frame (de)serialization on this leg.
 *
 * `explicitNulls = false` and `encodeDefaults = false` are BOTH required so an outbound
 * frame emits only its populated fields (the `omitempty` contract); `ignoreUnknownKeys`
 * keeps us forward-compatible with fields the gateway may add.
 */
val ConnectorJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = false
    }
