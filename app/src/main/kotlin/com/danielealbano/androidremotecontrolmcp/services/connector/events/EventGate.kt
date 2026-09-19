package com.danielealbano.androidremotecontrolmcp.services.connector.events

import com.danielealbano.androidremotecontrolmcp.data.model.DeviceEventConfig
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.DevicePolicy
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory

/**
 * The AND (`docs/phone/device_events.md` §2), and the ONE place it is computed.
 *
 * Whether a category leaves this handset is decided TWICE and forwarding is the INTERSECTION: the
 * holder's toggle in this app, and the platform's `events` policy. Neither side can widen what the
 * other refused — the holder can always narrow what their handset reports without asking the org,
 * and the org can narrow without touching the handset.
 *
 * ## Why it is enforced HERE, on the device
 *
 * The gateway could drop a category the org forbade, and it may well; that is not the same
 * guarantee. An event refused here never leaves the phone at all, so a gateway bug, a mis-routed
 * publish or a compromised bus subscriber cannot disclose it. It is the same reasoning that puts
 * command policy in [com.danielealbano.androidremotecontrolmcp.services.connector.policy.PolicyEnforcer]
 * rather than trusting the dispatcher: proximity to the act, not sovereignty over the decision.
 *
 * ## The fail direction, which is the opposite of the command plane's
 *
 * A null [policy] — no snapshot has reached this socket yet — PERMITS. The command plane refuses in
 * that state, deliberately, because driving a handset is an act with consequences. Reporting is
 * advisory: it discloses only what the holder's own toggles already allow, and a policy that had
 * not arrived yet must not silently blind the plane for the life of a connection. The reasoning in
 * full, and why an absent category key and empty app lists read the same way, is on
 * [com.danielealbano.androidremotecontrolmcp.services.connector.policy.EventPolicy].
 */
object EventGate {
    /**
     * Whether an event of [category] may be sent.
     *
     * @param holder the holder's per-category toggles.
     * @param policy the platform snapshot in force, or null when none has arrived on this socket.
     * @param packageName the package the event is about, for the notification per-app filter. It is
     *   consulted for [DeviceEventCategory.NOTIFICATION] alone: the filter is a statement about
     *   whose notifications leave the phone, and applying it to a `battery` event — which names no
     *   app — would silently make an allowlist mean "report no battery either".
     */
    fun allows(
        category: DeviceEventCategory,
        holder: DeviceEventConfig,
        policy: DevicePolicy?,
        packageName: String? = null,
    ): Boolean = holder.permits(category) && platformPermits(category, policy, packageName)

    /** Convenience for the reporter: the same decision, read off an already-built [DeviceEvent]. */
    fun allows(
        event: DeviceEvent,
        holder: DeviceEventConfig,
        policy: DevicePolicy?,
    ): Boolean = allows(event.category, holder, policy, event.subjectPackage)

    /** The platform's conjunct alone. Null policy permits — see the class docs. */
    private fun platformPermits(
        category: DeviceEventCategory,
        policy: DevicePolicy?,
        packageName: String?,
    ): Boolean {
        val events = policy?.events ?: return true
        return events.permits(category) &&
            (category != DeviceEventCategory.NOTIFICATION || events.notificationApps.forwards(packageName))
    }
}
