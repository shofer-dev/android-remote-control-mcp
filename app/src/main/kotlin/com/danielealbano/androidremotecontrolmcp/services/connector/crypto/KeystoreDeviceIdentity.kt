package com.danielealbano.androidremotecontrolmcp.services.connector.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DeviceIdentity] backed by a hardware-backed, non-exportable ed25519 key in the
 * AndroidKeyStore, with a key-attestation chain.
 *
 * There is no pre-existing AndroidKeyStore usage in the app to collide with (fork map §6),
 * so the alias below is free. The private key never leaves the Keymint/StrongBox boundary —
 * it is generated `PURPOSE_SIGN` and read back only as a `PrivateKey` handle, never as raw
 * bytes — which is exactly what the platform's attestation is meant to prove.
 *
 * Generation prefers StrongBox and transparently falls back to the TEE when StrongBox is
 * unavailable (most devices, and the emulator, have no StrongBox). On an emulator the whole
 * chain degrades to software tier; that is expected and still enrols (the platform records
 * the tier, it does not require hardware — wire spec §3.1).
 *
 * ed25519 in AndroidKeyStore is available from API 33 (our minSdk), so no software-key
 * fallback is provided: a device that cannot mint a non-exportable ed25519 key cannot
 * satisfy the security contract and must fail loudly rather than silently downgrade to an
 * exportable software key.
 */
@Singleton
class KeystoreDeviceIdentity
    @Inject
    constructor() : DeviceIdentity {
        @Volatile private var attestationChallenge: ByteArray? = null

        override fun publicKeyBase64(): String {
            val publicKey = ensureKeyPair().first
            val raw = AttachCrypto.rawPublicKeyFromSpki(publicKey.encoded)
            return AttachCrypto.encodePublicKey(raw)
        }

        override fun attestation(): JsonElement {
            ensureKeyPair()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val chain = keyStore.getCertificateChain(KEY_ALIAS)
            if (chain == null || chain.isEmpty()) {
                Log.w(TAG, "No attestation chain available for key '$KEY_ALIAS' (software provider?)")
                return buildJsonObject { put("format", JsonPrimitive(ATTESTATION_FORMAT)) }
            }
            val encoder = Base64.getEncoder()
            val certs =
                chain.map { cert -> JsonPrimitive(encoder.encodeToString(cert.encoded)) }
            return buildJsonObject {
                put("format", JsonPrimitive(ATTESTATION_FORMAT))
                put("chain", JsonArray(certs))
            }
        }

        override fun signChallenge(nonce: String): String {
            val privateKey = ensureKeyPair().second
            val signature =
                Signature.getInstance(ED25519).apply {
                    initSign(privateKey)
                    update(AttachCrypto.signingInput(nonce))
                }
            return AttachCrypto.encodeSignature(signature.sign())
        }

        /**
         * Loads the existing keypair or generates it on first use. The public and private
         * handles are re-read from the keystore each call (cheap) so a rotation elsewhere is
         * observed; generation itself is idempotent via [KeyStore.containsAlias].
         */
        @Synchronized
        private fun ensureKeyPair(): Pair<PublicKey, java.security.PrivateKey> {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateKeyPair()
            }
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
            return entry.certificate.publicKey to entry.privateKey
        }

        private fun generateKeyPair() {
            // A stable-per-install attestation challenge. The platform does not currently
            // enforce challenge freshness (attestation is accepted, not verified — wire spec
            // §3.1), but a challenge is required to make the Keymint emit an attestation
            // extension at all, so we mint one once and keep it for the key's lifetime.
            val challenge =
                attestationChallenge ?: ByteArray(CHALLENGE_BYTES).also {
                    SecureRandom().nextBytes(it)
                    attestationChallenge = it
                }

            try {
                buildAndGenerate(challenge, strongBox = true)
                Log.i(TAG, "Generated StrongBox-backed ed25519 identity key")
            } catch (_: StrongBoxUnavailableException) {
                buildAndGenerate(challenge, strongBox = false)
                Log.i(TAG, "Generated TEE-backed ed25519 identity key (StrongBox unavailable)")
            }
        }

        private fun buildAndGenerate(
            challenge: ByteArray,
            strongBox: Boolean,
        ) {
            val spec =
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    // Ed25519 (EdDSA) hashes internally; no external digest is applied.
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .setAttestationChallenge(challenge)
                    .apply { if (strongBox) setIsStrongBoxBacked(true) }
                    .build()
            val generator = KeyPairGenerator.getInstance(ED25519, ANDROID_KEYSTORE)
            generator.initialize(spec)
            generator.generateKeyPair()
        }

        companion object {
            private const val TAG = "MCP:DeviceIdentity"
            private const val ANDROID_KEYSTORE = "AndroidKeyStore"
            private const val KEY_ALIAS = "platform_connector_ed25519_identity"
            private const val ED25519 = "Ed25519"
            private const val ATTESTATION_FORMAT = "android-key-attestation"
            private const val CHALLENGE_BYTES = 32
        }
    }
