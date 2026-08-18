@file:Suppress("TooGenericExceptionCaught")

package com.danielealbano.androidremotecontrolmcp.services.connector

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.LocationData
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ActionName
import com.danielealbano.androidremotecontrolmcp.services.deviceadmin.PlatformDeviceAdminReceiver
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real device-action executor (`docs/phones/android_remote_control.md` §6.4): "the app
 * executes, the platform decides". It performs the four action frames the gateway pushes on the
 * standing socket — `lock`, `wipe`, `locate`, `ring` (wire spec §6.1) — with no local judgment;
 * authorization is the gateway's (`android-use`/`android-manage`). `pause`/`resume` are
 * gateway-local and never arrive.
 *
 * Degradation is honest. `lock` and `wipe` need the [PlatformDeviceAdminReceiver] active; when it
 * is not held they return an [ActionOutcome.Failure] with `admin-not-held` rather than throwing
 * or silently succeeding. `wipe` is destructive and logs loudly before acting. `locate` rides the
 * flavor-neutral [LocationProvider] (fused on gms, LocationManager on foss) — never GMS directly.
 * `ring` uses [AudioManager] + a [Ringtone] at alarm volume.
 *
 * Each executor returns an outcome the connector renders into the `action_result` frame (which
 * the gateway logs but does not correlate — wire spec §6.3).
 */
@Singleton
class PlatformDeviceActionHandler
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val locationProvider: LocationProvider,
    ) : DeviceActionHandler {
        override suspend fun execute(
            action: String,
            params: JsonElement?,
        ): ActionOutcome =
            when (action) {
                ActionName.LOCK -> {
                    lock()
                }

                ActionName.WIPE -> {
                    wipe()
                }

                ActionName.LOCATE -> {
                    locate()
                }

                ActionName.RING -> {
                    ring(params)
                }

                else -> {
                    Log.w(TAG, "Unknown device action '$action'")
                    ActionOutcome.Failure("unknown-action", "unknown device action '$action'")
                }
            }

        private fun lock(): ActionOutcome {
            val dpm =
                when (val admin = requireActiveAdmin("lock")) {
                    is AdminAccess.Denied -> return admin.outcome
                    is AdminAccess.Ready -> admin.dpm
                }
            return try {
                dpm.lockNow()
                Log.i(TAG, "Device locked")
                ActionOutcome.Success(okPayload("locked" to "true"))
            } catch (e: SecurityException) {
                Log.e(TAG, "lockNow refused", e)
                ActionOutcome.Failure("lock-failed", e.message ?: "lockNow refused")
            }
        }

        private fun wipe(): ActionOutcome {
            // Destructive: a factory reset. Log loudly regardless of whether it proceeds.
            Log.w(TAG, "WIPE action received — attempting a full device wipe (DESTRUCTIVE)")
            val dpm =
                when (val admin = requireActiveAdmin("wipe")) {
                    is AdminAccess.Denied -> return admin.outcome
                    is AdminAccess.Ready -> admin.dpm
                }
            return try {
                // wipeData triggers an immediate reset; execution typically does not return past
                // this call, so the action_result frame may never reach the gateway — expected.
                dpm.wipeData(0)
                ActionOutcome.Success(okPayload("wiped" to "true"))
            } catch (e: SecurityException) {
                Log.e(TAG, "wipeData refused (requires device/profile owner or an admin holding wipe-data)", e)
                ActionOutcome.Failure("wipe-failed", e.message ?: "wipeData refused")
            }
        }

        /**
         * Resolves the [DevicePolicyManager] and confirms this app is an active device admin.
         * [AdminAccess.Ready] carries the manager; [AdminAccess.Denied] carries the honest
         * degradation outcome (`admin-unavailable` / `admin-not-held`).
         */
        private fun requireActiveAdmin(action: String): AdminAccess {
            val dpm =
                appContext.getSystemService(DevicePolicyManager::class.java)
                    ?: return AdminAccess.Denied(adminUnavailable(action))
            return if (PlatformDeviceAdminReceiver.isAdminActive(appContext)) {
                AdminAccess.Ready(dpm)
            } else {
                AdminAccess.Denied(adminNotHeld(action))
            }
        }

        private sealed interface AdminAccess {
            data class Ready(
                val dpm: DevicePolicyManager,
            ) : AdminAccess

            data class Denied(
                val outcome: ActionOutcome,
            ) : AdminAccess
        }

        private suspend fun locate(): ActionOutcome {
            val result = locationProvider.getLocation(freshFix = true)
            return result.fold(
                onSuccess = { data ->
                    Log.i(TAG, "Located device")
                    ActionOutcome.Success(buildLocatePayload(data))
                },
                onFailure = { e ->
                    Log.w(TAG, "Locate failed: ${e.message}")
                    ActionOutcome.Failure("locate-failed", e.message ?: "could not obtain a location fix")
                },
            )
        }

        private suspend fun ring(params: JsonElement?): ActionOutcome {
            val setup =
                resolveRingSetup()
                    ?: return ActionOutcome.Failure("ring-failed", "audio output or ringtone unavailable")
            return performRing(setup, parseDurationMs(params))
        }

        /** Resolves the alarm audio channel and a ringtone uri, or null if either is unavailable. */
        private fun resolveRingSetup(): RingSetup? {
            val audio = appContext.getSystemService(AudioManager::class.java)
            val uri =
                audio?.let {
                    RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE)
                }
            return if (audio != null && uri != null) {
                RingSetup(audio, uri)
            } else {
                Log.w(TAG, "Ring unavailable (audio=${audio != null}, ringtone=${uri != null})")
                null
            }
        }

        private suspend fun performRing(
            setup: RingSetup,
            durationMs: Long,
        ): ActionOutcome {
            val audio = setup.audio
            val priorVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            var ringtone: Ringtone? = null
            return try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                ringtone =
                    RingtoneManager.getRingtone(appContext, setup.uri)?.apply {
                        @Suppress("DEPRECATION")
                        streamType = AudioManager.STREAM_ALARM
                        play()
                    }
                Log.i(TAG, "Ringing for ${durationMs}ms")
                delay(durationMs)
                ActionOutcome.Success(okPayload("rang_ms" to durationMs.toString()))
            } catch (e: Exception) {
                Log.e(TAG, "Ring failed", e)
                ActionOutcome.Failure("ring-failed", e.message ?: "could not ring")
            } finally {
                runCatching { ringtone?.stop() }
                runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, priorVolume, 0) }
            }
        }

        private data class RingSetup(
            val audio: AudioManager,
            val uri: android.net.Uri,
        )

        private fun adminUnavailable(action: String): ActionOutcome {
            Log.e(TAG, "DevicePolicyManager unavailable; cannot $action")
            return ActionOutcome.Failure("admin-unavailable", "DevicePolicyManager is unavailable")
        }

        private fun adminNotHeld(action: String): ActionOutcome {
            Log.w(TAG, "Device admin not active; cannot $action")
            return ActionOutcome.Failure(
                "admin-not-held",
                "device admin is not active; grant it at enrolment before '$action' can run",
            )
        }

        companion object {
            private const val TAG = "MCP:DeviceAction"

            internal const val DEFAULT_RING_MS = 15_000L
            internal const val MIN_RING_MS = 1_000L
            internal const val MAX_RING_MS = 120_000L

            /** Builds a small string-valued success payload for an action_result frame. */
            internal fun okPayload(vararg pairs: Pair<String, String>): JsonElement =
                buildJsonObject {
                    for ((k, v) in pairs) put(k, JsonPrimitive(v))
                }

            /** Renders a [LocationData] fix into the `locate` action_result payload. */
            internal fun buildLocatePayload(data: LocationData): JsonElement =
                buildJsonObject {
                    put("latitude", JsonPrimitive(data.latitude))
                    put("longitude", JsonPrimitive(data.longitude))
                    put("accuracy_meters", JsonPrimitive(data.accuracyMeters))
                    data.street?.let { put("street", JsonPrimitive(it)) }
                }

            /**
             * Parses the optional `duration_ms` ring parameter, clamped to [MIN_RING_MS]..
             * [MAX_RING_MS]; absent/invalid → [DEFAULT_RING_MS]. No action defines a params schema
             * in the platform (wire spec Q5), so this is a best-effort read of a possible field.
             */
            internal fun parseDurationMs(params: JsonElement?): Long {
                val raw =
                    runCatching {
                        (params as? JsonObject)?.get("duration_ms")?.jsonPrimitive?.longOrNull
                    }.getOrNull()
                return (raw ?: DEFAULT_RING_MS).coerceIn(MIN_RING_MS, MAX_RING_MS)
            }
        }
    }
