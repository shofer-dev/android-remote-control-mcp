package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.RefusalReason
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.WireError
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The one decision that DESTROYS a device's platform identity
 * ([PlatformConnector.refusalVoidsIdentity]).
 *
 * It is tested as much for what it refuses to do as for what it does. Getting it wrong in the
 * permissive direction is the worst outcome the connector can produce: a phone whose identity is
 * wiped on a revocation re-enrols as a brand-new device and quietly undoes an administrator's
 * decision. So every neighbouring refusal has a case here, and each case is the reason that
 * refusal is excluded rather than a box being ticked.
 */
@DisplayName("PlatformConnector.refusalVoidsIdentity")
class PlatformConnectorVoidIdentityTest {
    @Nested
    @DisplayName("the identity is void")
    inner class Voids {
        @Test
        fun `unauthorized plus unknown-device at attach voids the stored identity`() {
            assertTrue(
                PlatformConnector.refusalVoidsIdentity(
                    code = WireError.UNAUTHORIZED,
                    reason = RefusalReason.UNKNOWN_DEVICE,
                    state = PlatformConnector.HState.SENT_ATTACH_SIG,
                ),
            )
        }

        @Test
        fun `it also holds when the refusal arrives on the attach frame itself`() {
            // The gateway may refuse before ever minting a challenge; the verdict is the same.
            assertTrue(
                PlatformConnector.refusalVoidsIdentity(
                    code = WireError.UNAUTHORIZED,
                    reason = RefusalReason.UNKNOWN_DEVICE,
                    state = PlatformConnector.HState.SENT_ATTACH,
                ),
            )
        }
    }

    @Nested
    @DisplayName("the identity survives")
    inner class Keeps {
        @Test
        fun `a REVOKED device keeps its identity, so a revocation cannot be undone by re-enrolling`() {
            assertFalse(
                PlatformConnector.refusalVoidsIdentity(
                    code = WireError.UNAUTHORIZED,
                    reason = RefusalReason.REVOKED,
                    state = PlatformConnector.HState.SENT_ATTACH_SIG,
                ),
            )
        }

        @Test
        fun `a BAD SIGNATURE keeps its identity, because the platform's record is intact`() {
            assertFalse(
                PlatformConnector.refusalVoidsIdentity(
                    code = WireError.UNAUTHORIZED,
                    reason = RefusalReason.BAD_SIGNATURE,
                    state = PlatformConnector.HState.SENT_ATTACH_SIG,
                ),
            )
        }

        @Test
        fun `a TERMS MISMATCH is a consent verdict, not an identity one`() {
            assertFalse(
                PlatformConnector.refusalVoidsIdentity(
                    code = WireError.UNAUTHORIZED,
                    reason = RefusalReason.TERMS_MISMATCH,
                    state = PlatformConnector.HState.SENT_ATTACH_SIG,
                ),
            )
        }

        @Test
        fun `a MISSING NONCE is a protocol fault against a device the platform knows`() {
            assertFalse(
                PlatformConnector.refusalVoidsIdentity(
                    code = WireError.UNAUTHORIZED,
                    reason = RefusalReason.MISSING_NONCE,
                    state = PlatformConnector.HState.SENT_ATTACH_SIG,
                ),
            )
        }

        @Test
        fun `a MISSING SIGNATURE is likewise a protocol fault`() {
            assertFalse(
                PlatformConnector.refusalVoidsIdentity(
                    code = WireError.UNAUTHORIZED,
                    reason = RefusalReason.MISSING_SIGNATURE,
                    state = PlatformConnector.HState.SENT_ATTACH_SIG,
                ),
            )
        }

        @Test
        fun `an unauthorized with NO reason keeps the identity`() {
            // A gateway that forwards no reason must not be read as "unknown-device": the only
            // action keyed on this decision is destructive, so absence is the safe side.
            assertFalse(
                PlatformConnector.refusalVoidsIdentity(
                    code = WireError.UNAUTHORIZED,
                    reason = null,
                    state = PlatformConnector.HState.SENT_ATTACH_SIG,
                ),
            )
        }

        @Test
        fun `an unrecognised reason keeps the identity`() {
            assertFalse(
                PlatformConnector.refusalVoidsIdentity(
                    code = WireError.UNAUTHORIZED,
                    reason = "a-reason-this-app-has-never-heard-of",
                    state = PlatformConnector.HState.SENT_ATTACH_SIG,
                ),
            )
        }

        @Test
        fun `the reason alone is not enough — other codes are not identity verdicts`() {
            val otherCodes =
                listOf(
                    WireError.BAD_FRAME,
                    WireError.ENROLMENT_UNAVAILABLE,
                    WireError.TERMS_UNAVAILABLE,
                    WireError.TERMS_REQUIRED,
                    WireError.UPGRADE_REQUIRED,
                )
            otherCodes.forEach { code ->
                assertFalse(
                    PlatformConnector.refusalVoidsIdentity(
                        code = code,
                        reason = RefusalReason.UNKNOWN_DEVICE,
                        state = PlatformConnector.HState.SENT_ATTACH_SIG,
                    ),
                    "code $code must not void the identity",
                )
            }
        }

        @Test
        fun `a refusal during ENROLMENT voids nothing, because there is no identity yet`() {
            val enrolStates = listOf(PlatformConnector.HState.SENT_ENROLL, PlatformConnector.HState.SENT_ACCEPT)
            enrolStates.forEach { state ->
                assertFalse(
                    PlatformConnector.refusalVoidsIdentity(
                        code = WireError.UNAUTHORIZED,
                        reason = RefusalReason.UNKNOWN_DEVICE,
                        state = state,
                    ),
                    "state $state must not void the identity",
                )
            }
        }

        @Test
        fun `a refusal after ATTACHED is not an attach verdict`() {
            assertFalse(
                PlatformConnector.refusalVoidsIdentity(
                    code = WireError.UNAUTHORIZED,
                    reason = RefusalReason.UNKNOWN_DEVICE,
                    state = PlatformConnector.HState.ATTACHED,
                ),
            )
        }
    }
}
