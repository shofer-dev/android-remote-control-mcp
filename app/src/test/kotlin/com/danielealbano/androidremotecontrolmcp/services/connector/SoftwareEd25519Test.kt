@file:Suppress("MaxLineLength")

package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.services.connector.crypto.AttachCrypto
import com.danielealbano.androidremotecontrolmcp.services.connector.crypto.SoftwareEd25519
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature

/**
 * Verifies the SOFTWARE-tier identity path (used when AndroidKeyStore has no Ed25519 keygen):
 * a software key must sign the SAME bytes as the hardware path — the ASCII bytes of the hex
 * nonce string (wire spec §3.8) — and its persisted encoding must reload to the identical
 * signing identity so a reconnect re-attaches with the same key.
 */
class SoftwareEd25519Test {
    private fun freshNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hexDecode(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun verify(
        publicKey: PublicKey,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        Signature.getInstance("Ed25519", SoftwareEd25519.provider).run {
            initVerify(publicKey)
            update(message)
            verify(signature)
        }

    @Test
    fun `software key signs the hex STRING bytes, not the decoded bytes`() {
        val keyPair = SoftwareEd25519.generate()
        val nonce = freshNonce()

        val signature = SoftwareEd25519.sign(keyPair.private, AttachCrypto.signingInput(nonce))

        assertTrue(verify(keyPair.public, nonce.toByteArray(StandardCharsets.US_ASCII), signature))
        assertFalse(verify(keyPair.public, hexDecode(nonce), signature), "must NOT be the decoded 32 bytes")
    }

    @Test
    fun `raw public key is 32 bytes`() {
        val keyPair = SoftwareEd25519.generate()
        assertEquals(AttachCrypto.RAW_ED25519_PUBLIC_KEY_SIZE, SoftwareEd25519.rawPublicKey(keyPair.public).size)
    }

    @Test
    fun `persisted key reloads to the identical signing identity`() {
        val keyPair = SoftwareEd25519.generate()
        val nonce = freshNonce()

        // Simulate persistence: encode the key material and reload it (as KeystoreDeviceIdentity does).
        val reloaded = SoftwareEd25519.loadKeyPair(keyPair.private.encoded, keyPair.public.encoded)

        // Same raw public key ⇒ same on-wire identity across reconnects.
        assertArrayEquals(
            SoftwareEd25519.rawPublicKey(keyPair.public),
            SoftwareEd25519.rawPublicKey(reloaded.public),
        )
        // A signature from the reloaded private key validates against the original public key.
        val signature = SoftwareEd25519.sign(reloaded.private, AttachCrypto.signingInput(nonce))
        assertTrue(verify(keyPair.public, nonce.toByteArray(StandardCharsets.US_ASCII), signature))
    }

    @Test
    fun `verify accepts a genuine raw ed25519 signature and rejects tampering`() {
        val keyPair = SoftwareEd25519.generate()
        val raw = SoftwareEd25519.rawPublicKey(keyPair.public)
        val message = "attach-nonce".toByteArray(StandardCharsets.US_ASCII)
        val signature = SoftwareEd25519.sign(keyPair.private, message)

        assertTrue(SoftwareEd25519.verify(raw, message, signature))
        // A flipped bit in the signature, a different message, and a different key all fail.
        val tampered = signature.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(SoftwareEd25519.verify(raw, message, tampered))
        assertFalse(SoftwareEd25519.verify(raw, "other".toByteArray(StandardCharsets.US_ASCII), signature))
        assertFalse(SoftwareEd25519.verify(SoftwareEd25519.rawPublicKey(SoftwareEd25519.generate().public), message, signature))
    }

    @Test
    fun `verify returns false rather than throwing on malformed inputs`() {
        val keyPair = SoftwareEd25519.generate()
        val raw = SoftwareEd25519.rawPublicKey(keyPair.public)
        val message = "m".toByteArray(StandardCharsets.US_ASCII)

        assertFalse(SoftwareEd25519.verify(ByteArray(31), message, ByteArray(64)), "wrong pubkey length")
        assertFalse(SoftwareEd25519.verify(raw, message, ByteArray(0)), "empty signature")
        assertFalse(SoftwareEd25519.verify(raw, message, ByteArray(71)), "DER-length garbage signature")
    }

    @Test
    fun `signerProducesRawEd25519 accepts a software signer`() {
        val keyPair = SoftwareEd25519.generate()
        val raw = SoftwareEd25519.rawPublicKey(keyPair.public)

        assertTrue(
            SoftwareEd25519.signerProducesRawEd25519(raw) { message ->
                SoftwareEd25519.sign(keyPair.private, message)
            },
        )
    }

    @Test
    fun `signerProducesRawEd25519 rejects a DER-signing keystore quirk`() {
        val keyPair = SoftwareEd25519.generate()
        val raw = SoftwareEd25519.rawPublicKey(keyPair.public)

        // A signer that returns a 71-byte value (the DER/ASN.1 signature a HyperOS AndroidKeyStore
        // "Ed25519" key produced live) is the exact case this gate exists to reject: right length
        // class to fool a naive check, wrong shape to ever verify raw.
        assertFalse(
            SoftwareEd25519.signerProducesRawEd25519(raw) { _ ->
                ByteArray(71).also { SecureRandom().nextBytes(it) }
            },
        )
    }

    @Test
    fun `signerProducesRawEd25519 rejects a well-formed signature under the WRONG key`() {
        // 64 bytes and internally valid, but signed by a different key than the one enrolled — this
        // catches a bad public-key extraction (a 32-byte value that is not the signing key's point),
        // which a length-only check would pass.
        val enrolled = SoftwareEd25519.rawPublicKey(SoftwareEd25519.generate().public)
        val other = SoftwareEd25519.generate()

        assertFalse(
            SoftwareEd25519.signerProducesRawEd25519(enrolled) { message ->
                SoftwareEd25519.sign(other.private, message)
            },
        )
    }

    @Test
    fun `signerProducesRawEd25519 returns false when the signer throws`() {
        val raw = SoftwareEd25519.rawPublicKey(SoftwareEd25519.generate().public)

        assertFalse(
            SoftwareEd25519.signerProducesRawEd25519(raw) { _ ->
                throw java.security.SignatureException("keystore refused to sign")
            },
        )
    }
}
