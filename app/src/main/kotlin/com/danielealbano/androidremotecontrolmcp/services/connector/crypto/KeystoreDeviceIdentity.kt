@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught", "ReturnCount", "MaxLineLength")

package com.danielealbano.androidremotecontrolmcp.services.connector.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DeviceIdentity] that is HARDWARE-FIRST WITH AN HONEST SOFTWARE FALLBACK.
 *
 * The private key is a non-exportable ed25519 key in the AndroidKeyStore (TEE/StrongBox)
 * whenever the device can mint one, carrying a key-attestation chain. But AndroidKeyStore
 * ed25519 keygen is NOT universal — an emulator, an old device, or a de-Googled ROM throws
 * `NoSuchAlgorithmException: no such algorithm: Ed25519 for provider AndroidKeyStore`. That
 * must NOT be a hard failure: the platform does not verify the attestation chain at redeem
 * (it is `omitempty` and unchecked) and it models a per-device attestation TIER
 * (`docs/phones/phone_support.md` §3/§6.2), so a software key with `tier=software` is the
 * intended path for such devices — org policy, not a crash, decides if that is acceptable.
 *
 * ── Selection (deterministic, stable across runs) ──────────────────────────────────────
 * 1. AndroidKeyStore already holds the ed25519 alias  → HARDWARE (reuse it).
 * 2. else a software key is persisted                 → SOFTWARE (reuse it).
 * 3. else try to generate a hardware key (StrongBox → TEE); on ANY failure, generate and
 *    persist a software key and log a LOUD warning.
 *
 * Once a device is on software (its hardware attempt failed and the alias is absent), the
 * persisted key is found first on the next run, so the identity NEVER rotates mid-life — the
 * `phone_id`↔pubkey binding established at enrolment stays valid.
 *
 * ── Software key at rest ───────────────────────────────────────────────────────────────
 * A software private key cannot live in the AndroidKeyStore, so it is persisted in the app's
 * private files dir, AES-GCM-wrapped by an AndroidKeyStore AES key (AES/GCM is universally
 * available even where Ed25519 is not). If the wrap itself is unavailable the blob is stored
 * unwrapped with a warning — a software key is already the weaker tier and app-private storage
 * is the floor.
 *
 * [signChallenge] goes through [AttachCrypto.signingInput] on BOTH paths, so the
 * hex-string-ASCII signing contract is identical regardless of tier.
 */
@Singleton
class KeystoreDeviceIdentity
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DeviceIdentity {
        private class Material(
            val tier: String,
            val rawPublicKey: ByteArray,
            val attestation: JsonElement,
            val sign: (ByteArray) -> ByteArray,
        )

        @Volatile private var material: Material? = null

        override fun publicKeyBase64(): String = AttachCrypto.encodePublicKey(ensure().rawPublicKey)

        override fun attestation(): JsonElement = ensure().attestation

        override fun attestationTier(): String = ensure().tier

        override fun signChallenge(nonce: String): String {
            val signed = ensure().sign(AttachCrypto.signingInput(nonce))
            return AttachCrypto.encodeSignature(signed)
        }

        /**
         * Discards BOTH possible homes of the key — the AndroidKeyStore alias and the persisted
         * software blob — and the cached material, so the next [ensure] runs the whole selection
         * from scratch and mints a fresh keypair.
         *
         * Every step is best-effort and independent: a keystore that refuses the delete must not
         * leave the software blob behind, because the selection order would then adopt the old
         * software key and the reset would look like it worked while the identity did not rotate.
         * The AES wrapping key is deliberately kept — it wraps whatever key comes next and is not
         * itself an identity.
         */
        @Synchronized
        override fun reset() {
            runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            }.onFailure { Log.w(TAG, "Could not delete the hardware identity key alias", it) }
            runCatching {
                val file = softwareKeyFile()
                if (file.exists() && !file.delete()) error("delete returned false for ${file.name}")
            }.onFailure { Log.w(TAG, "Could not delete the persisted software identity key", it) }
            material = null
            Log.w(TAG, "Device identity discarded; a fresh keypair will be minted on the next use")
        }

        @Synchronized
        private fun ensure(): Material {
            material?.let { return it }
            val resolved = resolveHardware() ?: resolvePersistedSoftware() ?: generateFresh()
            material = resolved
            Log.i(TAG, "Device identity ready (tier=${resolved.tier})")
            return resolved
        }

        // ─────────────────────────────── hardware path ────────────────────────────────────

        private fun resolveHardware(): Material? {
            return try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (!keyStore.containsAlias(KEY_ALIAS)) return null
                validatedHardwareMaterial(keyStore)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load existing hardware key; will consider software fallback", e)
                null
            }
        }

        /**
         * Builds the hardware material and PROVES it before trusting it: an AndroidKeyStore
         * "Ed25519" key can pass keygen yet produce a DER/ASN.1 signature no raw-ed25519 verifier
         * accepts (a real OEM quirk — a 71-byte signature seen on a HyperOS device), which would
         * enrol a device that can then never attach. A key that fails the self-test is deleted so
         * it is not re-adopted on the next start, and null returns to fall through to the software
         * key — whose tier the platform records for tethered/physical anyway until the hardware
         * attestation chain is verified.
         */
        private fun validatedHardwareMaterial(keyStore: KeyStore): Material? {
            val material = hardwareMaterial(keyStore)
            if (SoftwareEd25519.signerProducesRawEd25519(material.rawPublicKey, material.sign)) {
                return material
            }
            Log.w(
                TAG,
                "Hardware ed25519 key failed the raw-signature self-test (the AndroidKeyStore " +
                    "produced a non-raw or non-verifying signature — a known OEM quirk); discarding " +
                    "it and falling back to a SOFTWARE key.",
            )
            runCatching { keyStore.deleteEntry(KEY_ALIAS) }
                .onFailure { Log.w(TAG, "Could not delete the rejected hardware key alias", it) }
            return null
        }

        private fun hardwareMaterial(keyStore: KeyStore): Material {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
            val publicKey = entry.certificate.publicKey
            val rawPublicKey = AttachCrypto.rawPublicKeyFromSpki(publicKey.encoded)
            val chain = keyStore.getCertificateChain(KEY_ALIAS)
            val attestation =
                buildJsonObject {
                    put("format", JsonPrimitive(ATTESTATION_FORMAT))
                    put("tier", JsonPrimitive(DeviceIdentity.TIER_HARDWARE))
                    if (chain != null && chain.isNotEmpty()) {
                        val encoder = Base64.getEncoder()
                        put("chain", JsonArray(chain.map { JsonPrimitive(encoder.encodeToString(it.encoded)) }))
                    }
                }
            val privateKey = entry.privateKey
            return Material(
                tier = DeviceIdentity.TIER_HARDWARE,
                rawPublicKey = rawPublicKey,
                attestation = attestation,
                sign = { message -> signHardware(privateKey, message) },
            )
        }

        private fun signHardware(
            privateKey: PrivateKey,
            message: ByteArray,
        ): ByteArray =
            // No explicit provider: an AndroidKeyStore private key routes to the keystore signer.
            Signature.getInstance(ED25519).run {
                initSign(privateKey)
                update(message)
                sign()
            }

        /** Attempts hardware keygen (StrongBox → TEE). Returns null on any unsupported/failure. */
        private fun tryGenerateHardware(): Material? {
            val challenge = ByteArray(CHALLENGE_BYTES).also { SecureRandom().nextBytes(it) }
            return try {
                try {
                    generateHardwareKey(challenge, strongBox = true)
                    Log.i(TAG, "Generated StrongBox-backed ed25519 identity key")
                } catch (_: StrongBoxUnavailableException) {
                    generateHardwareKey(challenge, strongBox = false)
                    Log.i(TAG, "Generated TEE-backed ed25519 identity key (StrongBox unavailable)")
                }
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                validatedHardwareMaterial(keyStore)
            } catch (e: Exception) {
                // NoSuchAlgorithmException (no AndroidKeyStore Ed25519), ProviderException, etc.
                Log.w(TAG, "Hardware ed25519 key generation unavailable (${e.javaClass.simpleName}: ${e.message})")
                null
            }
        }

        private fun generateHardwareKey(
            challenge: ByteArray,
            strongBox: Boolean,
        ) {
            val spec =
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_NONE) // Ed25519 (EdDSA) hashes internally
                    .setAttestationChallenge(challenge)
                    .apply { if (strongBox) setIsStrongBoxBacked(true) }
                    .build()
            val generator = java.security.KeyPairGenerator.getInstance(ED25519, ANDROID_KEYSTORE)
            generator.initialize(spec)
            generator.generateKeyPair()
        }

        // ─────────────────────────────── software path ────────────────────────────────────

        private fun generateFresh(): Material {
            tryGenerateHardware()?.let { return it }

            Log.w(
                TAG,
                "AndroidKeyStore cannot mint an ed25519 key on this device — falling back to a " +
                    "SOFTWARE key (attestation tier=software). This is expected on emulators, old " +
                    "devices and de-Googled ROMs; org policy decides whether software pairing is allowed.",
            )
            val keyPair = SoftwareEd25519.generate()
            runCatching { persistSoftwareKey(keyPair) }
                .onFailure { Log.e(TAG, "Failed to persist software key; re-attach will regenerate", it) }
            return softwareMaterial(keyPair)
        }

        private fun resolvePersistedSoftware(): Material? {
            val keyPair = loadPersistedSoftwareKey() ?: return null
            Log.w(TAG, "Reusing persisted SOFTWARE ed25519 key (attestation tier=software)")
            return softwareMaterial(keyPair)
        }

        private fun softwareMaterial(keyPair: KeyPair): Material =
            Material(
                tier = DeviceIdentity.TIER_SOFTWARE,
                rawPublicKey = SoftwareEd25519.rawPublicKey(keyPair.public),
                // A software key produces no hardware chain — send an honest tier-only attestation.
                attestation = buildJsonObject { put("tier", JsonPrimitive(DeviceIdentity.TIER_SOFTWARE)) },
                sign = { message -> SoftwareEd25519.sign(keyPair.private, message) },
            )

        private fun softwareKeyFile(): File = File(context.filesDir, SOFTWARE_KEY_FILE)

        private fun persistSoftwareKey(keyPair: KeyPair) {
            val priv = keyPair.private.encoded // PKCS#8
            val pub = keyPair.public.encoded // X.509 SPKI
            val blob =
                ByteBuffer
                    .allocate(Int.SIZE_BYTES + priv.size + pub.size)
                    .putInt(priv.size)
                    .put(priv)
                    .put(pub)
                    .array()
            softwareKeyFile().writeBytes(wrap(blob))
        }

        private fun loadPersistedSoftwareKey(): KeyPair? {
            val file = softwareKeyFile()
            if (!file.exists()) return null
            return try {
                val blob = unwrap(file.readBytes())
                val buffer = ByteBuffer.wrap(blob)
                val privLen = buffer.int
                val priv = ByteArray(privLen).also { buffer.get(it) }
                val pub = ByteArray(buffer.remaining()).also { buffer.get(it) }
                SoftwareEd25519.loadKeyPair(priv, pub)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read persisted software key; regenerating", e)
                null
            }
        }

        // ── at-rest wrapping: AES-GCM under an AndroidKeyStore AES key (falls back to plaintext) ──

        private fun wrap(plain: ByteArray): ByteArray =
            try {
                val cipher = Cipher.getInstance(AES_TRANSFORM)
                cipher.init(Cipher.ENCRYPT_MODE, aesWrapKey())
                val iv = cipher.iv
                val ciphertext = cipher.doFinal(plain)
                ByteBuffer
                    .allocate(1 + iv.size + ciphertext.size)
                    .put(WRAP_AES_GCM)
                    .put(iv)
                    .put(ciphertext)
                    .array()
            } catch (e: Exception) {
                Log.w(TAG, "AES-GCM wrap unavailable; storing software key unwrapped in private storage", e)
                ByteBuffer
                    .allocate(1 + plain.size)
                    .put(WRAP_PLAINTEXT)
                    .put(plain)
                    .array()
            }

        private fun unwrap(stored: ByteArray): ByteArray {
            val buffer = ByteBuffer.wrap(stored)
            return when (val marker = buffer.get()) {
                WRAP_PLAINTEXT -> {
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }

                WRAP_AES_GCM -> {
                    val iv = ByteArray(GCM_IV_BYTES).also { buffer.get(it) }
                    val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val cipher = Cipher.getInstance(AES_TRANSFORM)
                    cipher.init(Cipher.DECRYPT_MODE, aesWrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                    cipher.doFinal(ciphertext)
                }

                else -> {
                    error("Unknown software-key wrap marker: $marker")
                }
            }
        }

        private fun aesWrapKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(AES_WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(AES_WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            return generator.generateKey()
        }

        companion object {
            private const val TAG = "MCP:DeviceIdentity"
            private const val ANDROID_KEYSTORE = "AndroidKeyStore"
            private const val KEY_ALIAS = "platform_connector_ed25519_identity"
            private const val AES_WRAP_ALIAS = "platform_connector_key_wrap_aes"
            private const val ED25519 = "Ed25519"
            private const val ATTESTATION_FORMAT = "android-key-attestation"
            private const val CHALLENGE_BYTES = 32
            private const val SOFTWARE_KEY_FILE = "connector_sw_ed25519.bin"
            private const val AES_TRANSFORM = "AES/GCM/NoPadding"
            private const val GCM_IV_BYTES = 12
            private const val GCM_TAG_BITS = 128
            private const val WRAP_PLAINTEXT: Byte = 0
            private const val WRAP_AES_GCM: Byte = 1
        }
    }
