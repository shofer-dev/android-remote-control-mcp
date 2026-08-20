package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The platform-authored policy snapshot, delivered on the `policy` frame immediately after
 * `attached` and re-sent whenever the state it carries changes (today: a pause or a resume).
 * Mirrors `device-gateway/internal/protocol/protocol.go` `PolicySnapshot` field for field.
 *
 * The snapshot is held in memory only. It is never written to DataStore, and that is a
 * property rather than an omission: a persisted policy is a stale policy waiting to be
 * enforced after the platform has changed its mind, and it is one more thing on the device
 * for a holder with root to edit. A connector with no snapshot enforces nothing because it
 * DRIVES nothing — see [PolicyEnforcer], which refuses every command until one arrives.
 *
 * Field semantics, all pinned to the Go side:
 * - [version] hex sha256 over the policy fields (not [issuedAt]); equal versions are the same
 *   policy, so "did the policy change?" is a string compare.
 * - [activeHours] `"HH:MM-HH:MM"` in the DEVICE's local wall clock, or empty for always. The
 *   device's own zone is deliberate: "the phone must not act at 3am" is a statement about
 *   where the phone is, not about where the cluster is.
 * - [drivableAppPosture] one of [Posture.DEVICE_LIST] / [Posture.ALLOWLIST_ONLY].
 * - [drivableApps] the package allowlist; always an array on the wire (never `null`), because
 *   the two postures read an empty list differently.
 * - [rateLimit] a fixed-window command cap; `commands == 0` is uncapped.
 * - [paused] the `android-use` / `android-manage` pause. The gateway already refuses to
 *   dispatch to a paused device; this is the device's own half of the same refusal.
 */
@Serializable
data class DevicePolicy(
    val version: String = "",
    @SerialName("issued_at") val issuedAt: String = "",
    @SerialName("active_hours") val activeHours: String = "",
    @SerialName("drivable_app_posture") val drivableAppPosture: String = Posture.DEVICE_LIST,
    @SerialName("drivable_apps") val drivableApps: List<String> = emptyList(),
    @SerialName("rate_limit") val rateLimit: RateLimit = RateLimit(),
    val paused: Boolean = false,
) {
    /** The drivable-app postures, spelled exactly as the platform validates and sends them. */
    object Posture {
        /** The allowlist is the whole gate — an EMPTY allowlist means no app restriction. */
        const val DEVICE_LIST = "device-list"

        /** A package must be named in [drivableApps] to be driven — an empty list drives nothing. */
        const val ALLOWLIST_ONLY = "allowlist-only"
    }
}

/**
 * A fixed-window command cap. [commands] `== 0` means UNCAPPED — the platform's way of saying
 * "no cap configured", not "refuse everything", which is why the zero test is explicit
 * everywhere it is read rather than folded into a comparison.
 */
@Serializable
data class RateLimit(
    val commands: Int = 0,
    @SerialName("window_seconds") val windowSeconds: Int = 0,
)
