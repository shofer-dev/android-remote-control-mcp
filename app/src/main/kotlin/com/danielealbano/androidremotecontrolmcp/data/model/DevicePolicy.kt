@file:Suppress("SwallowedException")

package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Platform-authored policy the connector enforces at the LAST HOP, before the in-process MCP
 * call (`docs/phones/android_remote_control.md` §6.4). The platform is the authority; the app
 * holds no sovereign controls of its own. This is the on-device copy of that authority — an
 * *enforcement point* for policy authored on the settings plane, "the layer closest to the act
 * is the one a dispatcher bug or a prompt-injected agent cannot route around".
 *
 * The design ships this to the device as a **policy snapshot at attach**. That frame is a
 * platform-side TODO (no snapshot frame exists on `/ws/device` yet — wire spec Q3), so today the
 * snapshot is set out of band through the same DataStore / adb-broadcast surface the connector
 * already uses for its config, via
 * [com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository.updateDevicePolicy].
 * When a snapshot frame does land, its handler writes it here and every enforcement decision
 * picks it up on the next command — the enforcement point already has a home.
 *
 * A field left null means "the platform has not constrained this dimension" — the pre-snapshot
 * default, which is unrestricted for that dimension. An explicitly empty [drivableAppAllowlist]
 * means the opposite: nothing may be launched.
 *
 * @property drivableAppAllowlist package names `android_launch_app` may open; null = no
 *   allowlist provisioned yet (any package allowed).
 * @property activeHours the daily window inside which acting (WRITE-group) tools are permitted;
 *   null = always active.
 */
@Serializable
data class DevicePolicy(
    val drivableAppAllowlist: Set<String>? = null,
    val activeHours: ActiveHours? = null,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    /** True if [packageName] may be launched under the current allowlist (null = unrestricted). */
    fun allowsLaunch(packageName: String): Boolean = drivableAppAllowlist?.contains(packageName) ?: true

    /** True if acting tools are permitted at [minuteOfDay] (0..1439); null hours = always. */
    fun isWithinActiveHours(minuteOfDay: Int): Boolean = activeHours?.contains(minuteOfDay) ?: true

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        fun fromJsonOrDefault(raw: String?): DevicePolicy {
            if (raw.isNullOrBlank()) return DevicePolicy()
            return try {
                json.decodeFromString(serializer(), raw)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                DevicePolicy()
            }
        }
    }
}

/**
 * A daily active-hours window, expressed as minutes-of-day in the device's local time
 * ([startMinuteOfDay] inclusive, [endMinuteOfDay] exclusive). A window that wraps past midnight
 * (start > end, e.g. 22:00→06:00) is supported. start == end is treated as a full day (always
 * active), never an empty window — a zero-width window would silently disable all acting.
 */
@Serializable
data class ActiveHours(
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
) {
    fun contains(minuteOfDay: Int): Boolean {
        val m = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        return when {
            startMinuteOfDay == endMinuteOfDay -> true
            startMinuteOfDay < endMinuteOfDay -> m in startMinuteOfDay until endMinuteOfDay
            else -> m >= startMinuteOfDay || m < endMinuteOfDay // wraps midnight
        }
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
