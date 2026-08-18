package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.data.model.ActiveHours
import com.danielealbano.androidremotecontrolmcp.data.model.DevicePolicy
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.tools.CuratedToolSurface
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The connector's last-hop policy enforcement point (`docs/phones/android_remote_control.md`
 * §6.4). It runs INSIDE [RelayTransport], on every `tools/call`, BEFORE the in-process MCP
 * dispatch — the layer closest to the act, which a dispatcher bug or a prompt-injected agent
 * cannot route around. It reads the locally-held [DevicePolicy] (the on-device copy of the
 * platform's policy snapshot) and applies two checks:
 *
 * 1. **Active hours** — an acting (WRITE-group, per [CuratedToolSurface]) tool called outside
 *    the platform's active-hours window is refused. READ-group perception tools are unaffected;
 *    the connector's own out-of-hours detach (a separate concern) is the coarser guarantee, this
 *    is the fine one.
 * 2. **Drivable-app allowlist** — `android_launch_app` (`open_app`) is refused when its target
 *    package is not on the platform allowlist. The app's own settings and OS Settings are
 *    permanently undrivable (§6.4) — that is a structural exclusion the allowlist expresses.
 *
 * A refusal is a typed on-device error carried back to the agent as an MCP tool error
 * ([Decision.Deny]); the platform's own gates (`android-use`/`android-manage`) remain the
 * primary authority — this is the guarantee, not the only control.
 *
 * [decide] is pure and unit-tested; [evaluate] is the impure wrapper that reads the current
 * policy and clock.
 */
@Singleton
class ConnectorPolicyEnforcer
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) {
        /**
         * Evaluates [fullToolName] (prefixed, as it arrives on the wire) against the current
         * device policy. [toolNamePrefix] is the active `android_[<slug>_]` prefix, stripped to
         * recover the curated base name.
         */
        suspend fun evaluate(
            fullToolName: String,
            toolNamePrefix: String,
            params: JsonElement?,
        ): Decision {
            val baseName = fullToolName.removePrefix(toolNamePrefix)
            val policy = settingsRepository.getDevicePolicy()
            return decide(baseName, params, policy, nowMinuteOfDay())
        }

        private fun nowMinuteOfDay(): Int {
            val cal = Calendar.getInstance()
            return cal.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR + cal.get(Calendar.MINUTE)
        }

        /** The outcome of a policy evaluation. */
        sealed interface Decision {
            data object Allow : Decision

            /** A typed on-device refusal; [code] is a stable machine string, [message] is prose. */
            data class Deny(
                val code: String,
                val message: String,
            ) : Decision
        }

        companion object {
            private const val MINUTES_PER_HOUR = 60

            /** Typed refusal codes (§6.4). */
            const val CODE_OUTSIDE_ACTIVE_HOURS = "policy-outside-active-hours"
            const val CODE_APP_NOT_ALLOWED = "policy-app-not-allowlisted"

            /** The `open_app` package parameter name (see AppManagementTools). */
            private const val PACKAGE_PARAM = "package_id"

            /**
             * The pure decision. Fails CLOSED for an uncurated tool: an unknown base name is
             * treated as WRITE (acting), so the active-hours gate applies rather than being
             * bypassed. A curated tool uses its declared [CuratedToolSurface] group.
             */
            fun decide(
                baseName: String,
                params: JsonElement?,
                policy: DevicePolicy,
                nowMinuteOfDay: Int,
            ): Decision =
                activeHoursDeny(baseName, policy, nowMinuteOfDay)
                    ?: launchAllowlistDeny(baseName, params, policy)
                    ?: Decision.Allow

            /** Refuses an acting (WRITE-group) tool outside active hours; null otherwise. */
            private fun activeHoursDeny(
                baseName: String,
                policy: DevicePolicy,
                nowMinuteOfDay: Int,
            ): Decision.Deny? {
                val group = CuratedToolSurface.groupOf(baseName) ?: CuratedToolSurface.ToolGroup.WRITE
                val acting = group == CuratedToolSurface.ToolGroup.WRITE
                return if (acting && !policy.isWithinActiveHours(nowMinuteOfDay)) {
                    Decision.Deny(
                        CODE_OUTSIDE_ACTIVE_HOURS,
                        "acting tool '$baseName' refused: outside the platform's active-hours window",
                    )
                } else {
                    null
                }
            }

            /** Refuses a launch of a non-allowlisted package; null otherwise. */
            private fun launchAllowlistDeny(
                baseName: String,
                params: JsonElement?,
                policy: DevicePolicy,
            ): Decision.Deny? {
                if (baseName != CuratedToolSurface.LAUNCH_APP) return null
                val target = packageArg(params)
                return if (target != null && !policy.allowsLaunch(target)) {
                    Decision.Deny(
                        CODE_APP_NOT_ALLOWED,
                        "launch of '$target' refused: not on the platform drivable-app allowlist",
                    )
                } else {
                    null
                }
            }

            private fun packageArg(params: JsonElement?): String? =
                runCatching {
                    params
                        ?.jsonObject
                        ?.get(PACKAGE_PARAM)
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull()

            /** Convenience re-export for callers building [ActiveHours] windows. */
            fun activeHours(
                startMinuteOfDay: Int,
                endMinuteOfDay: Int,
            ): ActiveHours = ActiveHours(startMinuteOfDay, endMinuteOfDay)
        }
    }
