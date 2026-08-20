package com.danielealbano.androidremotecontrolmcp.services.connector.policy

/**
 * The outcome of evaluating one relayed command against the device's policy.
 *
 * A [Refused] decision is rendered by the connector into a `reply{id, error, details}` frame,
 * which the gateway propagates to the caller unchanged — so the agent (and the run's audit
 * trail) sees the typed code, never a timeout and never a silent no-op.
 */
sealed interface PolicyDecision {
    /** The command may proceed to the loopback MCP hop. */
    data object Allowed : PolicyDecision

    /**
     * The command is refused ON THE DEVICE. [error] is one of [PolicyRefusal]; [details] is
     * the human-readable reason shown in the console's audit trail.
     */
    data class Refused(
        val error: String,
        val details: String,
    ) : PolicyDecision
}

/**
 * The typed device-side refusal codes, spelled exactly as
 * `device-gateway/internal/protocol/protocol.go` declares them. The gateway never mints one —
 * it carries them back from the device — but it declares them because they are shared API
 * surface. Changing a spelling here without changing it there breaks the contract silently:
 * the caller simply stops recognising the reason.
 */
object PolicyRefusal {
    /** No policy snapshot has arrived, so there is nothing to enforce and nothing is driven. */
    const val UNAVAILABLE = "policy-unavailable"

    /** The command arrived outside the active-hours window. */
    const val OUTSIDE_ACTIVE_HOURS = "policy-outside-active-hours"

    /** The target or foreground app is not in the drivable set. */
    const val APP_NOT_DRIVABLE = "policy-app-not-drivable"

    /** The app's own UI or the OS Settings app — permanently undrivable, never policy. */
    const val STRUCTURALLY_DENIED = "policy-structurally-denied"

    /** The command rate cap for the current window is spent. */
    const val RATE_LIMITED = "policy-rate-limited"

    /** The screen is locked; nothing executes until it is unlocked by its holder. */
    const val SCREEN_LOCKED = "policy-screen-locked"

    /** Remote driving is paused (`android-use` / `android-manage`). */
    const val PAUSED = "policy-paused"
}
