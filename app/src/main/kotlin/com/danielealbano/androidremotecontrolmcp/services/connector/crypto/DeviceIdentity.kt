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
}
