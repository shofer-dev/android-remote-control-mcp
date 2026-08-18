package com.danielealbano.androidremotecontrolmcp.services.connector.crypto

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The wire-crypto contract of the attach challenge–response, isolated into pure functions
 * so the one part of the protocol that fails SILENTLY when guessed wrong can be unit-tested
 * without a device, an emulator, or the AndroidKeyStore.
 *
 * THE TRAP (wire spec §3.8 + Appendix A, `credential-service/.../androiddevices.go:468`):
 * the gateway sends `challenge{nonce}` where `nonce` is 64 lowercase hex characters. The
 * platform verifies the ed25519 signature over **the ASCII/UTF-8 bytes of that hex STRING**
 * — i.e. `[]byte(req.Nonce)`, 64 bytes — **NOT** the 32 bytes the hex decodes to. Signing
 * the decoded bytes compiles, runs, and only ever manifests as an `unauthorized` refusal.
 * [signingInput] encodes that decision in one place; [KeystoreDeviceIdentity] and the tests
 * both go through it.
 *
 * Base64 uses [java.util.Base64] rather than `android.util.Base64` for two reasons: it is
 * available on this minSdk (33 ≥ 26) AND it is available in plain JVM unit tests, so the
 * encoding path the device runs is the exact path the tests exercise. The standard alphabet
 * WITH padding is chosen deliberately (wire spec Q7: credential-service accepts four base64
 * dialects, so any is legal, but padded-standard is the safe canonical form).
 */
object AttachCrypto {
    /**
     * The exact bytes to sign for an attach challenge: the ASCII bytes of the hex nonce
     * STRING as received, never the hex-decoded 32 bytes. Returns 64 bytes for a 64-char
     * nonce.
     */
    fun signingInput(nonce: String): ByteArray = nonce.toByteArray(StandardCharsets.US_ASCII)

    /** Base64 (standard, padded) of a raw ed25519 signature, as the `signature` field wants. */
    fun encodeSignature(rawSignature: ByteArray): String = Base64.getEncoder().encodeToString(rawSignature)

    /** Base64 (standard, padded) of the raw 32-byte ed25519 public key, as `pubkey` wants. */
    fun encodePublicKey(rawPublicKey: ByteArray): String = Base64.getEncoder().encodeToString(rawPublicKey)

    /**
     * Extracts the raw 32-byte ed25519 public key from a JCA X.509 SubjectPublicKeyInfo
     * encoding. For Ed25519 the SPKI is a fixed 44-byte structure whose trailing 32 bytes
     * are the raw key (the BIT STRING content), so taking the last 32 bytes is exact and
     * avoids pulling in an ASN.1 parser. credential-service enforces exactly 32 bytes
     * (`androiddevices.go:302-305`).
     */
    fun rawPublicKeyFromSpki(spki: ByteArray): ByteArray {
        require(spki.size >= RAW_ED25519_PUBLIC_KEY_SIZE) {
            "ed25519 SPKI too short: ${spki.size} bytes"
        }
        return spki.copyOfRange(spki.size - RAW_ED25519_PUBLIC_KEY_SIZE, spki.size)
    }

    const val RAW_ED25519_PUBLIC_KEY_SIZE = 32
}
