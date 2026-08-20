package com.danielealbano.androidremotecontrolmcp.services.connector.protocol

import com.danielealbano.androidremotecontrolmcp.services.connector.policy.DevicePolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The single flat envelope of the `/ws/device` wire protocol.
 *
 * One JSON object per WebSocket message serves BOTH directions and every frame kind
 * (matching `device-gateway/internal/protocol/protocol.go` `Frame`). The [type] field is
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
 * - [payload]     opaque MCP JSON-RPC frame (relay leg) or an action_result body.
 * - [id]          transport-scoped exchange id, server-minted on `cmd`/`action`.
 * - [messageId]   sender-minted logical idempotency key (relay leg), may be absent.
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
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("terms_text") val termsText: String? = null,
    @SerialName("terms_hash") val termsHash: String? = null,
    val nonce: String? = null,
    val signature: String? = null,
    val payload: JsonElement? = null,
    val action: String? = null,
    val params: JsonElement? = null,
    val policy: DevicePolicy? = null,
    val error: String? = null,
    val details: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
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
     * resume on the `android-use` / `android-manage` plane.
     */
    const val POLICY = "policy"
    const val ERROR = "error"
}

/**
 * The six typed refusal codes that can actually arrive on the device socket (wire spec §5).
 * The other six declared codes (`device_offline`/`device_busy`/… and `action-unsupported`,
 * `not-applicable`) only ever reach HTTP callers, never a device, so they are not modelled
 * here — but a handler must branch defensively since they share the vocabulary.
 *
 * Note the spelling split, exactly as the Go constants read: these six are hyphenated.
 */
object WireError {
    const val BAD_FRAME = "bad-frame"
    const val ENROLMENT_UNAVAILABLE = "enrolment-unavailable"
    const val TERMS_UNAVAILABLE = "terms-unavailable"
    const val TERMS_REQUIRED = "terms-required"
    const val UPGRADE_REQUIRED = "upgrade-required"
    const val UNAUTHORIZED = "unauthorized"
}

/**
 * The four device-action names the gateway may push (wire spec §6.1, `protocol.go`
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
