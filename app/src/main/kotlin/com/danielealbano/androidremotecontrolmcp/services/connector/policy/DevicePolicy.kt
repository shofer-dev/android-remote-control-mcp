package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The platform-authored policy snapshot, delivered on the `policy` frame immediately after
 * `attached` and re-sent whenever the state it carries changes (today: a pause or a resume).
 * Mirrors `phone-gateway/internal/protocol/protocol.go` `PolicySnapshot` field for field.
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
 * - [drivableAppPosture] one of [Posture.ON_PHONE_LIST] / [Posture.ALLOWLIST_ONLY].
 * - [drivableApps] the package allowlist; always an array on the wire (never `null`), because
 *   the two postures read an empty list differently.
 * - [rateLimit] a fixed-window command cap; `commands == 0` is uncapped.
 * - [paused] the `phone-use` / `phone-manage` pause. The gateway already refuses to
 *   dispatch to a paused device; this is the device's own half of the same refusal.
 * - [events] the DEVICE EVENT plane's half, and the one section whose default is OPEN — see
 *   [EventPolicy].
 */
@Serializable
data class DevicePolicy(
    val version: String = "",
    @SerialName("issued_at") val issuedAt: String = "",
    @SerialName("active_hours") val activeHours: String = "",
    @SerialName("drivable_app_posture") val drivableAppPosture: String = Posture.ON_PHONE_LIST,
    @SerialName("drivable_apps") val drivableApps: List<String> = emptyList(),
    @SerialName("rate_limit") val rateLimit: RateLimit = RateLimit(),
    val paused: Boolean = false,
    val events: EventPolicy = EventPolicy(),
) {
    /** The drivable-app postures, spelled exactly as the platform validates and sends them. */
    object Posture {
        /** The allowlist is the whole gate — an EMPTY allowlist means no app restriction. */
        const val ON_PHONE_LIST = "on-phone-list"

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

/**
 * The platform's half of the device-event plane's two filters
 * (`docs/phone/device_events.md` §2), mirroring `protocol.EventPolicy`.
 *
 * ## The default is EVERYTHING, and that is the OPPOSITE posture from the command plane
 *
 * [PolicyEnforcer] fails CLOSED: no snapshot means no command executes, because driving a handset
 * is an act with consequences and "the platform never said" must not read as "go ahead". This
 * section fails OPEN: an absent `events` object, an absent category key, and both app lists empty
 * all mean PERMITTED. The asymmetry is deliberate and is not a relaxation of the same rule —
 * the two answer different questions. Reporting is ADVISORY (§3): an event that does not leave the
 * phone is a fact the org never learns, so a policy the device could not parse must not silently
 * blind the plane; and the holder's own per-category toggles are the other conjunct, so "everything
 * permitted" here still forwards nothing the holder has switched off. **Policy only NARROWS.**
 *
 * - [categories] per-category enable, keyed by [DeviceEventCategory.wire]. The gateway always
 *   renders ALL FIVE keys, so a missing one means an older gateway rather than an org decision —
 *   and is read as permitted for exactly that reason. `false` is the org forbidding the category.
 * - [notificationApps] applies to the `notification` category ALONE, because *which apps'
 *   notifications leave the phone* is an org privacy decision rather than an app-side preference.
 */
@Serializable
data class EventPolicy(
    val categories: Map<String, Boolean> = emptyMap(),
    @SerialName("notification_apps") val notificationApps: EventAppFilter = EventAppFilter(),
) {
    /**
     * Whether the platform permits [category]. An absent key permits — see the class docs.
     */
    fun permits(category: DeviceEventCategory): Boolean = categories[category.wire] != false
}

/**
 * The notification per-app filter (`protocol.AppFilter`, carried as `notification_apps`).
 *
 * Named for what it filters rather than for the Go type, because `AppFilter` is already taken in
 * this app by the installed-app listing enum
 * ([com.danielealbano.androidremotecontrolmcp.data.model.AppFilter]) and two unrelated things with
 * one name is how the wrong import compiles.
 *
 * Semantics, in order: a NON-EMPTY [allow] is the whole gate — only the packages it names forward;
 * [block] then subtracts from whatever survived. Both empty is the default and forwards every app.
 */
@Serializable
data class EventAppFilter(
    val allow: List<String> = emptyList(),
    val block: List<String> = emptyList(),
) {
    /**
     * Whether this filter forwards [packageName].
     *
     * A null package — an event whose subject the OS would not name — is refused whenever an
     * [allow] list exists, since "only these packages" cannot be satisfied by one nobody can
     * identify, and permitted otherwise: an unnameable package cannot be in a [block] list either.
     */
    fun forwards(packageName: String?): Boolean {
        if (allow.isNotEmpty() && (packageName == null || packageName !in allow)) return false
        return packageName == null || packageName !in block
    }
}
