package com.danielealbano.androidremotecontrolmcp.services.connector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The status-derivation rules. Every case is driven by an explicit `now` — the fake clock — so
 * nothing here waits on real time.
 */
@DisplayName("ConnectorLiveness")
class ConnectorLivenessTest {
    private val attachedAt = 10_000L

    private fun connected(lastHeartbeat: Long) =
        ConnectorStatus.Connected(
            attachedSinceMillis = attachedAt,
            lastServerHeartbeatMillis = lastHeartbeat,
        )

    @Nested
    @DisplayName("isStale")
    inner class IsStale {
        @Test
        fun `a heartbeat answered one interval ago is fresh`() {
            assertFalse(
                ConnectorLiveness.isStale(
                    lastServerHeartbeatMillis = attachedAt,
                    nowMillis = attachedAt + ConnectorLiveness.HEARTBEAT_INTERVAL_MS,
                ),
            )
        }

        @Test
        fun `a heartbeat answered just inside the tolerance is fresh`() {
            assertFalse(
                ConnectorLiveness.isStale(
                    lastServerHeartbeatMillis = attachedAt,
                    nowMillis = attachedAt + ConnectorLiveness.STALE_AFTER_MS - 1,
                ),
            )
        }

        @Test
        fun `the tolerance boundary itself is stale`() {
            assertTrue(
                ConnectorLiveness.isStale(
                    lastServerHeartbeatMillis = attachedAt,
                    nowMillis = attachedAt + ConnectorLiveness.STALE_AFTER_MS,
                ),
            )
        }

        @Test
        fun `the tolerance spans two whole ping intervals plus a grace`() {
            assertEquals(
                2 * ConnectorLiveness.HEARTBEAT_INTERVAL_MS + ConnectorLiveness.HEARTBEAT_GRACE_MS,
                ConnectorLiveness.STALE_AFTER_MS,
            )
        }

        @Test
        fun `the tolerance stays inside the gateway's own ninety-second lapse`() {
            // The device must give up on the link BEFORE the platform reaps it, or the two ends
            // disagree about when this device went away.
            assertTrue(ConnectorLiveness.STALE_AFTER_MS < GATEWAY_HEARTBEAT_LAPSE_MS)
        }
    }

    @Nested
    @DisplayName("ground")
    inner class Ground {
        @Test
        fun `a fresh attached link is reported unchanged`() {
            val status = connected(lastHeartbeat = attachedAt)
            val grounded = ConnectorLiveness.ground(status, attachedAt + 1_000)

            assertSame(status, grounded)
        }

        @Test
        fun `attach then silence flips to reconnecting`() {
            val status = connected(lastHeartbeat = attachedAt)
            val now = attachedAt + ConnectorLiveness.STALE_AFTER_MS

            val grounded = ConnectorLiveness.ground(status, now)

            assertEquals(ConnectorStatus.Reconnecting(nextRetryAtMillis = now), grounded)
        }

        @Test
        fun `a paused link goes stale on the same rule`() {
            val status =
                ConnectorStatus.Paused(
                    attachedSinceMillis = attachedAt,
                    lastServerHeartbeatMillis = attachedAt,
                )

            val grounded = ConnectorLiveness.ground(status, attachedAt + ConnectorLiveness.STALE_AFTER_MS)

            assertTrue(grounded is ConnectorStatus.Reconnecting)
        }

        @Test
        fun `a paused link answering heartbeats stays paused`() {
            val status =
                ConnectorStatus.Paused(
                    attachedSinceMillis = attachedAt,
                    lastServerHeartbeatMillis = attachedAt + 5_000,
                )

            val grounded = ConnectorLiveness.ground(status, attachedAt + 6_000)

            assertSame(status, grounded)
        }

        @Test
        fun `a heartbeat refreshes the link back to fresh`() {
            val stale = connected(lastHeartbeat = attachedAt)
            val refreshed = stale.withHeartbeat(attachedAt + ConnectorLiveness.HEARTBEAT_INTERVAL_MS)
            val now = attachedAt + ConnectorLiveness.STALE_AFTER_MS

            assertTrue(ConnectorLiveness.ground(stale, now) is ConnectorStatus.Reconnecting)
            assertSame(refreshed, ConnectorLiveness.ground(refreshed, now))
            assertEquals(attachedAt, refreshed.attachedSinceMillis)
        }
    }

    @Nested
    @DisplayName("halted reasons pass through")
    inner class HaltedReasons {
        @Test
        fun `every halted state survives grounding verbatim`() {
            val halted =
                listOf(
                    ConnectorStatus.UpgradeRequired,
                    ConnectorStatus.EnrolmentRejected("code already redeemed"),
                    ConnectorStatus.AttachRejected("device revoked"),
                    ConnectorStatus.TermsDeclined(null),
                    ConnectorStatus.Misconfigured("unsupported gateway URL scheme"),
                )

            halted.forEach { status ->
                assertSame(status, ConnectorLiveness.ground(status, Long.MAX_VALUE / 2))
            }
        }

        @Test
        fun `details supplied by the gateway become the reason`() {
            assertEquals("device revoked", ConnectorStatus.AttachRejected("device revoked").reason)
            assertEquals("code already redeemed", ConnectorStatus.EnrolmentRejected("code already redeemed").reason)
        }

        @Test
        fun `a refusal with no details still explains itself`() {
            listOf(
                ConnectorStatus.EnrolmentRejected(null),
                ConnectorStatus.AttachRejected(null),
                ConnectorStatus.TermsDeclined(null),
                ConnectorStatus.Misconfigured(null),
                ConnectorStatus.UpgradeRequired,
            ).forEach { status ->
                assertTrue(status.reason.isNotBlank(), "${status.notificationLabel} has no reason")
            }
        }

        @Test
        fun `non-attached states are not liveness questions`() {
            listOf(
                ConnectorStatus.NeedsConfig,
                ConnectorStatus.NotEnrolled,
                ConnectorStatus.Connecting,
                ConnectorStatus.Enrolling,
                ConnectorStatus.Attaching,
                ConnectorStatus.AwaitingTermsConsent,
                ConnectorStatus.ReConsenting,
                ConnectorStatus.Stopped,
                ConnectorStatus.OutsideActiveHours(reopensAtMillis = 0),
                ConnectorStatus.Reconnecting(nextRetryAtMillis = 0),
            ).forEach { status ->
                assertSame(status, ConnectorLiveness.ground(status, Long.MAX_VALUE / 2))
            }
        }
    }

    private companion object {
        /** phone-gateway's `HEARTBEAT_LAPSE` default, mirrored here so the margin is asserted. */
        const val GATEWAY_HEARTBEAT_LAPSE_MS = 90_000L
    }
}
