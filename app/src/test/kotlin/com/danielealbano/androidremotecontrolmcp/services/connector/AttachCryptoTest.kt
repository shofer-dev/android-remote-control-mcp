@file:Suppress("MaxLineLength")

package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.services.connector.crypto.AttachCrypto
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64

/**
 * Guards THE silent-failure trap of the whole protocol (wire spec §3.8 + Appendix A): the
 * attach signature is over the ASCII bytes of the hex nonce STRING, not the 32 bytes the hex
 * decodes to. Getting it wrong compiles and only ever surfaces as `unauthorized`.
 *
 * The device signs inside the AndroidKeyStore, which is unavailable on the JVM — but the
 * MESSAGE construction and encoding are pure ([AttachCrypto]) and are exactly what the device
 * uses, so signing a known nonce with a plain JDK Ed25519 key and checking which byte string
 * the signature validates over proves the contract without a device.
 */
class AttachCryptoTest {
    private fun freshNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) } // 64 lowercase hex chars
    }

    private fun hexDecode(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun `signing input is the ascii bytes of the hex nonce string, 64 bytes not 32`() {
        val nonce = freshNonce()
        val input = AttachCrypto.signingInput(nonce)

        assertEquals(64, input.size, "must be the 64-char hex STRING's bytes, not the 32 decoded bytes")
        assertArrayEquals(nonce.toByteArray(StandardCharsets.US_ASCII), input)
    }

    @Test
    fun `ed25519 signature validates over the hex string and NOT over the decoded bytes`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val nonce = freshNonce()

        // Sign exactly what the device signs.
        val rawSignature =
            Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(AttachCrypto.signingInput(nonce))
                sign()
            }

        fun verifyOver(message: ByteArray): Boolean =
            Signature.getInstance("Ed25519").run {
                initVerify(keyPair.public)
                update(message)
                verify(rawSignature)
            }

        // The platform verifies over []byte(nonce) — the hex STRING bytes.
        assertTrue(verifyOver(nonce.toByteArray(StandardCharsets.US_ASCII)))
        // The trap: verifying over the hex-DECODED 32 bytes must fail. If our signingInput ever
        // regressed to signing the decoded bytes, this assertion would flip and the handshake
        // would silently 'unauthorized' in production.
        assertFalse(verifyOver(hexDecode(nonce)))
    }

    @Test
    fun `signature base64 is standard padded and round-trips`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val rawSignature =
            Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(AttachCrypto.signingInput(freshNonce()))
                sign()
            }
        val encoded = AttachCrypto.encodeSignature(rawSignature)
        assertArrayEquals(rawSignature, Base64.getDecoder().decode(encoded))
    }

    @Test
    fun `raw public key is the trailing 32 bytes of the spki`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val raw = AttachCrypto.rawPublicKeyFromSpki(keyPair.public.encoded)

        assertEquals(AttachCrypto.RAW_ED25519_PUBLIC_KEY_SIZE, raw.size)
        val encoded = AttachCrypto.encodePublicKey(raw)
        assertArrayEquals(raw, Base64.getDecoder().decode(encoded))
    }
}
