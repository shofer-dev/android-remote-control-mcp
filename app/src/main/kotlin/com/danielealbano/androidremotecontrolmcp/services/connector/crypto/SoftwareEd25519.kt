package com.danielealbano.androidremotecontrolmcp.services.connector.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * The software-tier Ed25519 provider, used when the AndroidKeyStore cannot mint an Ed25519
 * key (an emulator, an old device, or a de-Googled ROM). It is deliberately backed by the
 * BUNDLED BouncyCastle provider rather than the platform's crypto providers, so it resolves
 * identically on every Android runtime AND in plain JVM unit tests — the platform default
 * providers do not offer Ed25519 keygen on API 33 (that is exactly why the fallback exists).
 *
 * This class holds NO Android dependency and NO persistence: it only generates, signs and
 * re-materialises keys. The persistence + at-rest wrapping and the hardware/software choice
 * live in [KeystoreDeviceIdentity]. Signing goes through the same [AttachCrypto] contract as
 * the hardware path, so a software key signs the identical bytes (the hex-string-ASCII trap
 * is honoured on both paths).
 */
object SoftwareEd25519 {
    private const val ALGORITHM = "Ed25519"

    /** A single BouncyCastle provider instance, not registered globally (used by name-with-provider). */
    val provider: BouncyCastleProvider = BouncyCastleProvider()

    /** Generates a fresh software Ed25519 keypair. */
    fun generate(): KeyPair {
        val generator = java.security.KeyPairGenerator.getInstance(ALGORITHM, provider)
        return generator.generateKeyPair()
    }

    /** Signs [message] (already the exact bytes to sign) with a software private key. */
    fun sign(
        privateKey: PrivateKey,
        message: ByteArray,
    ): ByteArray =
        Signature.getInstance(ALGORITHM, provider).run {
            initSign(privateKey)
            update(message)
            sign()
        }

    /** Rebuilds a keypair from its persisted PKCS#8 (private) and X.509 (public) encodings. */
    fun loadKeyPair(
        privatePkcs8: ByteArray,
        publicX509: ByteArray,
    ): KeyPair {
        val factory = KeyFactory.getInstance(ALGORITHM, provider)
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privatePkcs8))
        val publicKey = factory.generatePublic(X509EncodedKeySpec(publicX509))
        return KeyPair(publicKey, privateKey)
    }

    /** The raw 32-byte public key, for the enroll frame's `pubkey` field. */
    fun rawPublicKey(publicKey: PublicKey): ByteArray = AttachCrypto.rawPublicKeyFromSpki(publicKey.encoded)

    /**
     * The fixed SubjectPublicKeyInfo prefix for an Ed25519 key (RFC 8410): the inverse of
     * [AttachCrypto.rawPublicKeyFromSpki]. Prepending it to a raw 32-byte key yields the X.509
     * encoding [KeyFactory] needs to rebuild the public key. It is DERIVED once from a throwaway
     * key's own encoding rather than hardcoded, so there is no opaque byte literal to get wrong —
     * the prefix is whatever the provider emits minus the trailing raw key.
     */
    private val ED25519_SPKI_PREFIX: ByteArray by lazy {
        val spki = generate().public.encoded
        spki.copyOfRange(0, spki.size - AttachCrypto.RAW_ED25519_PUBLIC_KEY_SIZE)
    }

    /** A raw ed25519 signature is exactly 64 bytes (RFC 8032); anything else is not one. */
    const val RAW_ED25519_SIGNATURE_SIZE = 64

    /** Bytes of the random message the self-test signs — enough to be unforgeable, no more. */
    private const val SELF_TEST_PROBE_SIZE = 32

    /**
     * Verifies [signature] over [message] against a RAW 32-byte ed25519 public key, using the
     * bundled BouncyCastle provider so it resolves identically on device and in JVM tests. This
     * is the same check credential-service performs server-side (`crypto/ed25519.Verify`), so a
     * signature this rejects is one the platform will reject too. Returns false — never throws —
     * on a malformed key or signature, so a caller can treat it as a plain predicate.
     */
    fun verify(
        rawPublicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (rawPublicKey.size != AttachCrypto.RAW_ED25519_PUBLIC_KEY_SIZE) return false
        return try {
            val publicKey =
                KeyFactory
                    .getInstance(ALGORITHM, provider)
                    .generatePublic(X509EncodedKeySpec(ED25519_SPKI_PREFIX + rawPublicKey))
            Signature.getInstance(ALGORITHM, provider).run {
                initVerify(publicKey)
                update(message)
                verify(signature)
            }
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    /**
     * Proves a signer really emits a RAW 64-byte ed25519 signature that verifies against
     * [rawPublicKey]. The hardware identity path uses this as its acceptance gate: an
     * AndroidKeyStore "Ed25519" key can pass keygen yet sign in DER/ASN.1 (a real OEM quirk —
     * observed as a 71-byte signature on a HyperOS device), which no raw-ed25519 verifier will
     * ever accept, so the enrolled device would be permanently unable to attach. A signer that
     * fails this test must be rejected in favour of the software key. A software key always
     * passes. The probe is random so a signer cannot special-case a fixed input.
     */
    fun signerProducesRawEd25519(
        rawPublicKey: ByteArray,
        sign: (ByteArray) -> ByteArray,
    ): Boolean {
        val probe = ByteArray(SELF_TEST_PROBE_SIZE).also { SecureRandom().nextBytes(it) }
        val signature =
            try {
                sign(probe)
            } catch (_: GeneralSecurityException) {
                return false
            }
        return signature.size == RAW_ED25519_SIGNATURE_SIZE && verify(rawPublicKey, probe, signature)
    }
}
