package com.danielealbano.androidremotecontrolmcp.services.connector.crypto

import kotlinx.serialization.json.JsonElement

/**
 * The device's cryptographic identity for the `/ws/device` handshake.
 *
 * Implementations own an ed25519 keypair whose private half is non-exportable and,
 * where the hardware allows, generated inside the TEE/StrongBox. The public key, the
 * key-attestation chain, and the challenge signature are the three things the enrol/attach
 * ceremony puts on the wire (wire spec §3.1, §3.8, §4 of Appendix A).
 *
 * This is an interface so the WebSocket/handshake orchestration is testable against a
 * fake, and so the AndroidKeyStore-bound implementation ([KeystoreDeviceIdentity]) is the
 * only class that touches `android.security.keystore`.
 */
interface DeviceIdentity {
    /**
     * Base64 (standard, padded) of the raw 32-byte ed25519 public key, for the enroll
     * frame's `pubkey` field. Generates the keypair on first use.
     */
    fun publicKeyBase64(): String

    /**
     * The key-attestation certificate chain, as a JSON value for the enroll frame's
     * `attestation` field. The gateway stores it verbatim; the platform verifies the
     * package name + signing digest embedded in the leaf's attestation extension. On an
     * emulator the chain is software-tier, which is expected. Returns a JSON value even
     * when no chain is available (so the field can still be sent or omitted by the caller).
     */
    fun attestation(): JsonElement

    /**
     * Signs the attach challenge and returns the base64 signature for the `attach_sig`
     * frame's `signature` field. The signed message is the ASCII bytes of the hex [nonce]
     * STRING — see [AttachCrypto.signingInput] for the trap this guards.
     */
    fun signChallenge(nonce: String): String

    /**
     * The attestation tier of the key backing this identity: [TIER_HARDWARE] when the key
     * lives in the AndroidKeyStore (TEE/StrongBox), [TIER_SOFTWARE] when it is a bundled
     * software key. Reported honestly — the platform records the tier and org policy decides
     * whether software is acceptable; it does not refuse enrolment on tier
     * (`docs/phones/android_support.md` §3/§6.2).
     */
    fun attestationTier(): String

    /**
     * DESTROYS the current keypair so the next use mints a fresh one.
     *
     * Called only when the identity this key backs has ceased to exist — the platform erased the
     * device record, or the holder unprovisioned the phone
     * ([com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorProvisioning]). It
     * is deliberately destructive and deliberately NOT part of the ordinary reconnect path: the
     * `device_id`↔pubkey binding established at enrolment must survive every transient failure,
     * so a key that outlives its device id would enrol the phone again under a public key the
     * platform has already seen bound to a deleted device.
     *
     * Implementations must leave the identity in the same state a fresh install has: no keystore
     * alias, no persisted software key, no cached material.
     */
    fun reset()

    companion object {
        const val TIER_HARDWARE = "hardware"
        const val TIER_SOFTWARE = "software"
    }
}
