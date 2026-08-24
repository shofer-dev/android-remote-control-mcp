package com.danielealbano.androidremotecontrolmcp.services.permissions

/**
 * What the real runtime checks answered, one entry per [RequiredPermission].
 *
 * A key that is ABSENT counts as NOT granted, deliberately. The alternative — assuming a grant
 * nobody confirmed — would hide the finding, and an audit that hides findings is worse than no
 * audit. Reporting a false gap costs the holder one glance at a Settings screen; missing a real
 * one costs a device that quietly cannot be driven.
 */
data class PermissionSnapshot(
    val granted: Map<RequiredPermission, Boolean> = emptyMap(),
) {
    fun isGranted(permission: RequiredPermission): Boolean = granted[permission] == true

    companion object {
        /** Convenience for tests and for the all-clear case. */
        fun allGranted(): PermissionSnapshot = PermissionSnapshot(RequiredPermission.entries.associateWith { true })
    }
}

/**
 * Whether the shade nudge should be posted, and why — the output of [MissingPermissions.decide].
 *
 * [POST] and [SKIP_QUIET_PERIOD] are the two states that look alike from outside and must not be
 * confused: the first is "say it", the second is "you already said it recently and nothing has
 * changed since".
 */
enum class NotificationDecision {
    /** Post or update the notification. */
    POST,

    /** Nothing operational is missing — the notification must be cancelled if it is showing. */
    CANCEL,

    /** Something is missing, but the app has no right to post. The card is the only surface. */
    CANNOT_POST,

    /** Already reported, unchanged, and inside the quiet period. */
    SKIP_QUIET_PERIOD,
}

/**
 * What the shade nudge last said, and when.
 *
 * It carries the OPERATIONAL set only, because that is all the notification ever names — a
 * tool-surface grant going missing must not restart the quiet period for a notification whose
 * text would not change. Null means "nothing has been reported yet", which is why the timestamp
 * lives in here rather than beside it: there is no such thing as a report time without a report.
 */
data class LastReport(
    val operational: Set<RequiredPermission>,
    val atMillis: Long,
)

/**
 * The permissions audit's decision layer: pure, total, and unit-tested as a matrix.
 *
 * Everything Android-shaped — reading the actual grant states, opening a Settings screen, posting
 * the notification — lives in [PermissionAuditor]. What is left here is the part with rules worth
 * arguing about: which gaps are worth interrupting a holder for, and how often.
 */
object MissingPermissions {
    /**
     * The missing grants, in catalog order with the operational ones first, so the card's top row
     * is always the most consequential thing wrong.
     */
    fun evaluate(snapshot: PermissionSnapshot): List<RequiredPermission> =
        RequiredPermission.entries
            .filterNot { snapshot.isGranted(it) }
            .sortedBy { if (it.isOperational) 0 else 1 }

    /** True when at least one missing grant stops the device being operated, or operated honestly. */
    fun hasOperationalGap(missing: Collection<RequiredPermission>): Boolean = missing.any { it.isOperational }

    /**
     * Whether to post the shade nudge.
     *
     * Three rules, in order, and the middle one is the reason this is not a boolean:
     * 1. No operational gap → [NotificationDecision.CANCEL]. The notification tracks a live fault,
     *    so it disappears when the fault does rather than waiting to be swiped away.
     * 2. `POST_NOTIFICATIONS` itself is the missing grant → [NotificationDecision.CANNOT_POST]. A
     *    notification cannot ask for the right to notify, and attempting it would silently drop the
     *    only report the holder was going to get. The card carries it alone.
     * 3. Otherwise post — unless the SAME set was reported within [quietPeriodMillis]. A CHANGED
     *    set overrides the quiet period, because a newly-missing grant is new information and the
     *    holder should not have to wait out a timer that a previous, different fault started.
     *
     * [lastReport] is what the notification last said, or null if it has never said anything.
     * [nowMillis] is on the monotonic clock.
     */
    fun decide(
        snapshot: PermissionSnapshot,
        missing: Collection<RequiredPermission>,
        lastReport: LastReport?,
        nowMillis: Long,
        quietPeriodMillis: Long,
    ): NotificationDecision {
        val operational = missing.filter { it.isOperational }.toSet()
        return when {
            operational.isEmpty() -> NotificationDecision.CANCEL
            !snapshot.isGranted(RequiredPermission.POST_NOTIFICATIONS) -> NotificationDecision.CANNOT_POST
            lastReport == null || operational != lastReport.operational -> NotificationDecision.POST
            nowMillis - lastReport.atMillis >= quietPeriodMillis -> NotificationDecision.POST
            else -> NotificationDecision.SKIP_QUIET_PERIOD
        }
    }
}
