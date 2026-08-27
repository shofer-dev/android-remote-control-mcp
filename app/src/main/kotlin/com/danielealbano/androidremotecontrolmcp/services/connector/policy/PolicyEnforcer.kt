@file:Suppress("ReturnCount")

package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.tools.CuratedToolSurface
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The enforcement point for platform-authored policy, evaluated in the connector BEFORE the
 * loopback MCP hop (`docs/phones/android_remote_control.md` §6.4).
 *
 * The point is PROXIMITY, not sovereignty. The platform decides — the snapshot is authored
 * on the settings plane and shipped by device-gateway — and this class refuses; being the
 * layer closest to the act is what a dispatcher bug or a prompt-injected agent cannot route
 * around. The app holds no policy of its own: everything here either came from the snapshot
 * or is one of the two structural refusals (§6.4, [StructuralDenylist]) that exist precisely
 * because they must survive the platform being wrong.
 *
 * ## The order of the checks, and why it is this order
 *
 * Cheap and unconditional first, so an expensive or device-touching read never happens for a
 * command that was doomed anyway, and so the refusal a caller sees names the FIRST reason
 * rather than an incidental one:
 *
 * 1. **Not a tool call** → allowed. `initialize`, `tools/list` and `ping` negotiate the
 *    session and touch no device state; refusing them would break the MCP session itself
 *    (the caller holds ONE long-lived session across reconnects — see `RelayTransport`),
 *    which is a far worse failure than anything the refusal would prevent.
 * 2. **No snapshot** → `policy-unavailable`. A connector that has not been told the policy
 *    drives nothing. This is the fail-closed default and it is the reason the gateway sends
 *    the snapshot immediately after `attached`.
 * 3. **Paused** → `policy-paused`. The gateway already refuses to dispatch to a paused
 *    device; this is the device's own half of that refusal, which is what makes a pause hold
 *    when the dispatcher is wrong.
 * 4. **Screen locked** → `policy-screen-locked`. §6.4: lock is possible remotely, unlock is
 *    not, and nothing lands while the screen is locked. An unreadable keyguard counts as
 *    locked.
 * 5. **Outside active hours** → `policy-outside-active-hours`. The connector DETACHES
 *    outside the window, so a command should never arrive; this catches the one that raced
 *    the detach, and means the guarantee does not depend on the detach winning.
 * 6. **Rate cap** → `policy-rate-limited`, charged once per command that gets this far.
 * 7. **Structural denylist** and 8. **drivable-app allowlist**, both keyed on the target of
 *    the call (a launch names its package; anything else acts on the foreground app).
 *
 * The last two apply to the WRITE group only. Reading the Settings screen discloses nothing
 * the holder is not already looking at, and blinding the agent to where it is would make it
 * worse at recovering; the hazard being contained is AGENCY (§3 of the platform doc splits
 * exactly this way — `android-view` sees, `android-drive` acts).
 *
 * For the same reason the last two carry ONE exemption — the **navigation escape**: a
 * `press_key` of Back, Home or Recents that names no target is allowed however the foreground
 * app is judged, because those three ask the system to change which app is in front and inject
 * nothing into the one being protected. Keeping perception open so the agent can see it needs
 * to press Back is worth nothing if pressing Back is itself refused. See [escape].
 */
@Singleton
class PolicyEnforcer
    @Inject
    constructor(
        private val environment: DeviceEnvironment,
    ) {
        private val rateCap = CommandRateCap()

        @Volatile private var current: DevicePolicy? = null

        /** The policy currently in force, or null when none has arrived on this connection. */
        val policy: DevicePolicy? get() = current

        /**
         * Installs a snapshot. Called on every `policy` frame; a fresh snapshot resets the
         * rate window so a tightened cap bites immediately instead of inheriting a window
         * already spent under the old one.
         */
        fun apply(snapshot: DevicePolicy) {
            current = snapshot
            rateCap.apply(snapshot.rateLimit)
            Log.i(
                TAG,
                "Policy applied: version=${snapshot.version} active_hours='${snapshot.activeHours}' " +
                    "posture=${snapshot.drivableAppPosture} allowlist=${snapshot.drivableApps.size} " +
                    "rate=${snapshot.rateLimit.commands}/${snapshot.rateLimit.windowSeconds}s " +
                    "paused=${snapshot.paused}",
            )
        }

        /**
         * Drops the snapshot. Called when a socket ends, so a reconnect starts unpoliced —
         * and therefore refusing — until the gateway sends a fresh snapshot. A policy that
         * outlived the connection that delivered it is a policy the platform may already
         * have changed.
         */
        fun clear() {
            current = null
            rateCap.apply(RateLimit())
        }

        /** True when the window is currently closed — the connector's cue to detach. */
        fun isOutsideActiveHours(): Boolean {
            val snapshot = current ?: return false
            return !ActiveHours.isOpen(snapshot.activeHours, environment.minuteOfDay())
        }

        /**
         * Milliseconds until the active-hours window next opens, for the out-of-hours sleep.
         * 0 when there is no window or it is already open.
         */
        fun millisUntilWindowOpens(): Long {
            val window = ActiveHours.parse(current?.activeHours.orEmpty()) ?: return 0
            return window.minutesUntilOpen(environment.minuteOfDay()).toLong() * MILLIS_PER_MINUTE
        }

        /**
         * Milliseconds until the active-hours window next closes, for the mid-session
         * watchdog. 0 when there is no window or it is already closed.
         */
        fun millisUntilWindowCloses(): Long {
            val window = ActiveHours.parse(current?.activeHours.orEmpty()) ?: return 0
            return window.minutesUntilClose(environment.minuteOfDay()).toLong() * MILLIS_PER_MINUTE
        }

        /** Evaluates one relayed `cmd` payload. See the class docs for the order. */
        fun evaluate(payload: JsonElement?): PolicyDecision {
            val command = CommandDescriptor.parse(payload)
            if (!command.isToolCall) return PolicyDecision.Allowed

            val snapshot =
                current ?: return refuse(
                    PolicyRefusal.UNAVAILABLE,
                    "no policy snapshot has reached this device; it executes nothing until the platform sends one",
                    command,
                )

            if (snapshot.paused) {
                return refuse(PolicyRefusal.PAUSED, "remote driving of this device is paused", command)
            }
            if (environment.isScreenLocked() != false) {
                return refuse(
                    PolicyRefusal.SCREEN_LOCKED,
                    "the screen is locked; the platform can lock this device but never unlock it",
                    command,
                )
            }
            if (!ActiveHours.isOpen(snapshot.activeHours, environment.minuteOfDay())) {
                return refuse(
                    PolicyRefusal.OUTSIDE_ACTIVE_HOURS,
                    "outside the active-hours window '${snapshot.activeHours}' (device local time)",
                    command,
                )
            }

            val now = environment.nowMillis()
            if (!rateCap.tryConsume(now)) {
                val resetIn = rateCap.millisUntilWindowReset(now) / MILLIS_PER_SECOND
                return refuse(
                    PolicyRefusal.RATE_LIMITED,
                    "the command rate cap (${snapshot.rateLimit.commands} per " +
                        "${snapshot.rateLimit.windowSeconds}s) is spent; the window resets in ${resetIn}s",
                    command,
                )
            }

            return evaluateTarget(snapshot, command)
        }

        /**
         * The two app-scoped rules. A launch is judged by the package it NAMES; every other
         * write is judged by the package it would act on — the foreground app.
         *
         * The one exemption is the **navigation escape**, and it lives in the foreground branch
         * alone: a `press_key` of Back, Home or Recents that names no target is allowed even
         * when the foreground app earns a refusal (structural, allowlist, or undeterminable).
         * See [escape] for why that is not a hole, and
         * [CommandDescriptor.isGlobalNavigation] for why the rest of `press_key`'s vocabulary
         * is not exempt. A call that DOES name a target is judged against that target
         * unchanged — the escape is about leaving the app in front, and naming one is not
         * leaving.
         */
        private fun evaluateTarget(
            snapshot: DevicePolicy,
            command: CommandDescriptor,
        ): PolicyDecision {
            if (command.group != CuratedToolSurface.ToolGroup.WRITE) return PolicyDecision.Allowed

            command.targetUri?.let { uri ->
                packageNamedByUri(uri)?.let { named ->
                    appRefusal(snapshot, named)?.let { return refuse(it.error, it.details, command) }
                }
            }

            val target = command.targetPackage
            if (target != null) {
                appRefusal(snapshot, target)?.let { return refuse(it.error, it.details, command) }
                return PolicyDecision.Allowed
            }

            val foreground = environment.foregroundPackage()?.takeIf { it.isNotBlank() }
            val refusal = if (foreground == null) UNKNOWN_FOREGROUND else appRefusal(snapshot, foreground)
            if (refusal == null) return PolicyDecision.Allowed
            if (command.isGlobalNavigation) return escape(refusal, foreground, command)
            return refuse(refusal.error, refusal.details, command)
        }

        /**
         * The app-scoped refusal [packageName] earns, or null when it earns none: the
         * structural denylist first — it is the refusal that must survive the platform being
         * wrong — then the snapshot's drivable-app rule.
         *
         * It LOGS NOTHING, deliberately, which is the one thing to preserve when editing it.
         * The navigation escape turns some of these into an ALLOW, and a refusal that never
         * happened must not appear in the log as one; every caller that KEEPS a refusal passes
         * it to [refuse], which is where the line is written.
         */
        private fun appRefusal(
            snapshot: DevicePolicy,
            packageName: String,
        ): PolicyDecision.Refused? =
            when {
                StructuralDenylist.isDenied(packageName, environment.ownPackage(), environment.settingsPackages()) -> {
                    PolicyDecision.Refused(
                        PolicyRefusal.STRUCTURALLY_DENIED,
                        "'$packageName' is permanently outside the drivable set " +
                            "(this app's own UI and the OS Settings app)",
                    )
                }

                !isAppDrivable(snapshot, packageName) -> {
                    PolicyDecision.Refused(
                        PolicyRefusal.APP_NOT_DRIVABLE,
                        "'$packageName' is not in this device's drivable-app set " +
                            "(posture '${snapshot.drivableAppPosture}')",
                    )
                }

                else -> {
                    null
                }
            }

        /**
         * Allows a Back / Home / Recents press that the foreground app would otherwise have
         * refused, and says so at INFO — the audit trail for "the agent left Settings", which
         * is worth exactly as much as the refusal line it replaces.
         *
         * Why this is not a hole in the denylist: those three are dispatched as
         * `AccessibilityService.GLOBAL_ACTION_*`, a request to the SYSTEM rather than an event
         * injected into the denied app's window, so the most they can do is change which app is
         * in front. Nothing is typed, confirmed or activated inside the protected UI, and the
         * only direction they move the agent is OUT.
         *
         * Why it must exist at all: without it, a refusal on the foreground app makes the
         * device uncommandable rather than merely constrained. `press_key` carries no target,
         * so when the connector's own UI or a system crash dialog came to the front, every
         * command — Home included — was refused, and only a person physically holding the
         * handset could recover it. That contradicts the denylist's own rule, which refuses
         * AGENCY and deliberately keeps perception open *so that* the agent can see it needs to
         * press Back. Being able to see the exit while being unable to take it is not a
         * containment property.
         */
        private fun escape(
            refusal: PolicyDecision.Refused,
            foreground: String?,
            command: CommandDescriptor,
        ): PolicyDecision {
            Log.i(
                TAG,
                "Navigation escape: '${command.pressedKey}' allowed out of " +
                    "'${foreground ?: "an undeterminable foreground"}' (${refusal.error}); a global action " +
                    "changes which app is in front and injects nothing into it",
            )
            return PolicyDecision.Allowed
        }

        private fun refuse(
            error: String,
            details: String,
            command: CommandDescriptor,
        ): PolicyDecision.Refused {
            Log.w(TAG, "Refused '${command.toolName ?: command.method}' on device policy: $error — $details")
            return PolicyDecision.Refused(error, details)
        }

        companion object {
            private const val TAG = "MCP:PolicyEnforcer"
            private const val MILLIS_PER_MINUTE = 60_000L
            private const val MILLIS_PER_SECOND = 1_000L

            /**
             * The refusal a command with no named target earns when the device cannot say what
             * is in front of it. Unlogged like everything [appRefusal] returns, for the same
             * reason: the navigation escape may turn it into an allow.
             */
            private val UNKNOWN_FOREGROUND =
                PolicyDecision.Refused(
                    PolicyRefusal.APP_NOT_DRIVABLE,
                    "the foreground app could not be determined, so it cannot be checked against the drivable set",
                )

            /**
             * The allowlist rule, extracted as pure logic because its two postures fail in
             * opposite directions and both are deliberate:
             *
             * - `device-list` — the allowlist IS the gate, so an EMPTY list means the org has
             *   authored no restriction and every app is drivable. This is the default, and
             *   it is why turning the capability on does not brick a fleet.
             * - `allowlist-only` — a package must be named to be driven, so an empty list
             *   drives NOTHING. That is not a bug: an org that selected the stricter posture
             *   and allowlisted nothing has asked for a fleet that drives no app, and the
             *   device says so loudly and typed on every call rather than guessing what was
             *   meant.
             *
             * An unrecognised posture is treated as the stricter one. The gateway already
             * normalises the value, so a spelling that arrives here anyway is a contract
             * break, and a contract break must not read as "no restriction".
             */
            fun isAppDrivable(
                snapshot: DevicePolicy,
                packageName: String,
            ): Boolean =
                when (snapshot.drivableAppPosture) {
                    DevicePolicy.Posture.DEVICE_LIST -> {
                        snapshot.drivableApps.isEmpty() || snapshot.drivableApps.contains(packageName)
                    }

                    else -> {
                        snapshot.drivableApps.contains(packageName)
                    }
                }

            /**
             * The package a URI would hand control to, when it names one: `package:<id>`
             * (the settings/app-details form), and the `package=` component of an
             * `intent:` URI. Returns null when the URI names no package — an `https:` link
             * is judged by whatever ends up in the foreground on the NEXT command, not
             * guessed at here.
             */
            fun packageNamedByUri(uri: String): String? {
                val trimmed = uri.trim()
                if (trimmed.startsWith(PACKAGE_SCHEME)) {
                    return trimmed.removePrefix(PACKAGE_SCHEME).substringBefore('/').takeIf { it.isNotEmpty() }
                }
                if (trimmed.startsWith(INTENT_SCHEME)) {
                    // `intent:...#Intent;package=com.example;end`
                    return trimmed
                        .substringAfter(";package=", "")
                        .substringBefore(';')
                        .takeIf { it.isNotEmpty() }
                }
                if (trimmed.startsWith(ANDROID_APP_SCHEME)) {
                    // `android-app://com.example/...` — the authority IS the package.
                    return trimmed.removePrefix(ANDROID_APP_SCHEME).substringBefore('/').takeIf { it.isNotEmpty() }
                }
                return null
            }

            private const val PACKAGE_SCHEME = "package:"
            private const val INTENT_SCHEME = "intent:"
            private const val ANDROID_APP_SCHEME = "android-app://"
        }
    }
