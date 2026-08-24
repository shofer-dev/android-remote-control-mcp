package com.danielealbano.androidremotecontrolmcp.services.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The permissions audit's decision layer as a matrix.
 *
 * Two rules carry all the risk and both are pinned here: an absent grant state must read as NOT
 * granted (an audit that hides findings is worse than none), and the notification must never be
 * attempted when `POST_NOTIFICATIONS` is itself what is missing (it would be dropped silently,
 * taking the holder's only report with it).
 */
@DisplayName("MissingPermissions")
class MissingPermissionsTest {
    @Nested
    @DisplayName("evaluate")
    inner class Evaluate {
        @Test
        fun `a fully granted device reports nothing missing`() {
            assertTrue(MissingPermissions.evaluate(PermissionSnapshot.allGranted()).isEmpty())
        }

        @Test
        fun `an empty snapshot reports every grant as missing`() {
            // Fail-closed: nothing was confirmed, so nothing is assumed.
            val missing = MissingPermissions.evaluate(PermissionSnapshot())

            assertEquals(RequiredPermission.entries.size, missing.size)
        }

        @Test
        fun `an explicitly false grant is missing`() {
            val missing = MissingPermissions.evaluate(granted(RequiredPermission.ACCESSIBILITY_SERVICE to false))

            assertTrue(missing.contains(RequiredPermission.ACCESSIBILITY_SERVICE))
        }

        @Test
        fun `operational gaps are listed before tool-surface ones`() {
            val missing =
                MissingPermissions.evaluate(
                    granted(
                        RequiredPermission.CAMERA to false,
                        RequiredPermission.ACCESSIBILITY_SERVICE to false,
                    ),
                )

            assertEquals(
                listOf(RequiredPermission.ACCESSIBILITY_SERVICE, RequiredPermission.CAMERA),
                missing,
            )
        }

        @Test
        fun `a tool-surface gap alone is not an operational gap`() {
            val missing = MissingPermissions.evaluate(granted(RequiredPermission.CAMERA to false))

            assertFalse(MissingPermissions.hasOperationalGap(missing))
        }

        @Test
        fun `battery optimisation counts as operational`() {
            // The watchdog cannot revive a killed connector from the background without it.
            val missing =
                MissingPermissions.evaluate(
                    granted(RequiredPermission.BATTERY_OPTIMIZATION_EXEMPTION to false),
                )

            assertTrue(MissingPermissions.hasOperationalGap(missing))
        }
    }

    @Nested
    @DisplayName("decide")
    inner class Decide {
        @Test
        fun `nothing operational missing cancels the notification`() {
            val snapshot = granted(RequiredPermission.CAMERA to false)

            assertEquals(
                NotificationDecision.CANCEL,
                decisionFor(snapshot, lastReport = null, nowMillis = 0),
            )
        }

        @Test
        fun `a fully granted device cancels the notification`() {
            assertEquals(
                NotificationDecision.CANCEL,
                decisionFor(PermissionSnapshot.allGranted(), lastReport = null, nowMillis = 0),
            )
        }

        @Test
        fun `a first operational gap posts`() {
            val snapshot = granted(RequiredPermission.ACCESSIBILITY_SERVICE to false)

            assertEquals(
                NotificationDecision.POST,
                decisionFor(snapshot, lastReport = null, nowMillis = 0),
            )
        }

        @Test
        fun `missing POST_NOTIFICATIONS makes the card the only surface`() {
            val snapshot = granted(RequiredPermission.POST_NOTIFICATIONS to false)

            assertEquals(
                NotificationDecision.CANNOT_POST,
                decisionFor(snapshot, lastReport = null, nowMillis = 0),
            )
        }

        @Test
        fun `the same gap inside the quiet period is skipped`() {
            val snapshot = granted(RequiredPermission.ACCESSIBILITY_SERVICE to false)

            assertEquals(
                NotificationDecision.SKIP_QUIET_PERIOD,
                decisionFor(
                    snapshot,
                    lastReport = reported(RequiredPermission.ACCESSIBILITY_SERVICE),
                    nowMillis = QUIET - 1,
                ),
            )
        }

        @Test
        fun `the same gap after the quiet period is re-posted`() {
            val snapshot = granted(RequiredPermission.ACCESSIBILITY_SERVICE to false)

            assertEquals(
                NotificationDecision.POST,
                decisionFor(
                    snapshot,
                    lastReport = reported(RequiredPermission.ACCESSIBILITY_SERVICE),
                    nowMillis = QUIET,
                ),
            )
        }

        @Test
        fun `a newly missing grant overrides the quiet period`() {
            // New information: the holder should not wait out a timer a different fault started.
            val snapshot =
                granted(
                    RequiredPermission.ACCESSIBILITY_SERVICE to false,
                    RequiredPermission.DEVICE_ADMIN to false,
                )

            assertEquals(
                NotificationDecision.POST,
                decisionFor(
                    snapshot,
                    lastReport = reported(RequiredPermission.ACCESSIBILITY_SERVICE),
                    nowMillis = 1,
                ),
            )
        }

        @Test
        fun `a tool-surface change does not restart the quiet period`() {
            // The notification only ever names operational gaps, so a camera permission going
            // missing must not re-post a notification that would read identically.
            val snapshot =
                granted(
                    RequiredPermission.ACCESSIBILITY_SERVICE to false,
                    RequiredPermission.CAMERA to false,
                )

            assertEquals(
                NotificationDecision.SKIP_QUIET_PERIOD,
                decisionFor(
                    snapshot,
                    lastReport = reported(RequiredPermission.ACCESSIBILITY_SERVICE),
                    nowMillis = 1,
                ),
            )
        }

        @Test
        fun `a resolved gap cancels even inside the quiet period`() {
            assertEquals(
                NotificationDecision.CANCEL,
                decisionFor(
                    PermissionSnapshot.allGranted(),
                    lastReport = reported(RequiredPermission.ACCESSIBILITY_SERVICE),
                    nowMillis = 1,
                ),
            )
        }

        private fun decisionFor(
            snapshot: PermissionSnapshot,
            lastReport: LastReport?,
            nowMillis: Long,
        ): NotificationDecision =
            MissingPermissions.decide(
                snapshot = snapshot,
                missing = MissingPermissions.evaluate(snapshot),
                lastReport = lastReport,
                nowMillis = nowMillis,
                quietPeriodMillis = QUIET,
            )
    }

    private companion object {
        const val QUIET = 15L * 60L * 1000L

        /** A previous report made at time zero, so the quiet period is measured from there. */
        fun reported(vararg operational: RequiredPermission): LastReport = LastReport(operational.toSet(), atMillis = 0)

        /** An all-granted snapshot with the named grants overridden. */
        fun granted(vararg overrides: Pair<RequiredPermission, Boolean>): PermissionSnapshot =
            PermissionSnapshot(PermissionSnapshot.allGranted().granted + overrides.toMap())
    }
}
