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
}
