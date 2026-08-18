package com.danielealbano.androidremotecontrolmcp.services.connector.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
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
}
