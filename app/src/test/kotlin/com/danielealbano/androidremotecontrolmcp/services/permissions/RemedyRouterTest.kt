package com.danielealbano.androidremotecontrolmcp.services.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Where a Fix button goes, as a matrix.
 *
 * The rule with the history behind it is the last one: every audited grant must route somewhere.
 * A remedy that resolved to nothing is what a holder reads as a dead button, and it is invisible
 * in code review because the row still renders perfectly.
 */
@DisplayName("RemedyRouter")
class RemedyRouterTest {
    @Nested
    @DisplayName("destinationFor")
    inner class DestinationFor {
        @Test
        fun `a runtime permission goes to the app's own permissions screen`() {
            assertEquals(
                RemedyDestination.APP_PERMISSIONS,
                RemedyRouter.destinationFor(PermissionRemedy.RUNTIME_REQUEST),
            )
        }

        @Test
        fun `accessibility goes to the system accessibility list`() {
            assertEquals(
                RemedyDestination.ACCESSIBILITY_SETTINGS,
                RemedyRouter.destinationFor(PermissionRemedy.ACCESSIBILITY_SETTINGS),
            )
        }

        @Test
        fun `notification access goes to the system notification-access list`() {
            assertEquals(
                RemedyDestination.NOTIFICATION_LISTENER_SETTINGS,
                RemedyRouter.destinationFor(PermissionRemedy.NOTIFICATION_LISTENER_SETTINGS),
            )
        }

        @Test
        fun `device admin uses the activation prompt when the build has one`() {
            assertEquals(
                RemedyDestination.DEVICE_ADMIN_PROMPT,
                RemedyRouter.destinationFor(
                    PermissionRemedy.DEVICE_ADMIN_ACTIVATION,
                    RemedyCapabilities(deviceAdminPrompt = true),
                ),
            )
        }

        @Test
        fun `device admin falls back to the security list when the prompt is missing`() {
            assertEquals(
                RemedyDestination.DEVICE_ADMIN_LIST,
                RemedyRouter.destinationFor(
                    PermissionRemedy.DEVICE_ADMIN_ACTIVATION,
                    RemedyCapabilities(deviceAdminPrompt = false),
                ),
            )
        }

        @Test
        fun `battery optimisation asks for the exemption inline when the dialog exists`() {
            // The row acts itself; it never points at another card to be acted on.
            assertEquals(
                RemedyDestination.BATTERY_EXEMPTION_PROMPT,
                RemedyRouter.destinationFor(
                    PermissionRemedy.BATTERY_EXEMPTION_REQUEST,
                    RemedyCapabilities(batteryExemptionPrompt = true),
                ),
            )
        }

        @Test
        fun `battery optimisation falls back to the device-wide list when the dialog is missing`() {
            assertEquals(
                RemedyDestination.BATTERY_OPTIMIZATION_LIST,
                RemedyRouter.destinationFor(
                    PermissionRemedy.BATTERY_EXEMPTION_REQUEST,
                    RemedyCapabilities(batteryExemptionPrompt = false),
                ),
            )
        }

        @Test
        fun `a missing prompt changes only its own remedy`() {
            val capabilities = RemedyCapabilities(deviceAdminPrompt = false, batteryExemptionPrompt = false)

            assertEquals(
                RemedyDestination.ACCESSIBILITY_SETTINGS,
                RemedyRouter.destinationFor(PermissionRemedy.ACCESSIBILITY_SETTINGS, capabilities),
            )
        }

        @Test
        fun `both prompts are assumed present until measured otherwise`() {
            assertEquals(
                RemedyCapabilities(deviceAdminPrompt = true, batteryExemptionPrompt = true),
                RemedyCapabilities(),
            )
        }

        @Test
        fun `every remedy routes somewhere, on a build with both prompts and on one with neither`() {
            val builds =
                listOf(
                    RemedyCapabilities(deviceAdminPrompt = true, batteryExemptionPrompt = true),
                    RemedyCapabilities(deviceAdminPrompt = false, batteryExemptionPrompt = false),
                )

            builds.forEach { capabilities ->
                PermissionRemedy.entries.forEach { remedy ->
                    assertNotNull(
                        RemedyRouter.destinationFor(remedy, capabilities),
                        "remedy $remedy has no destination on $capabilities",
                    )
                }
            }
        }
    }

    @Nested
    @DisplayName("the audited grants")
    inner class AuditedGrants {
        @Test
        fun `battery optimisation is fixed from its own row`() {
            // It used to render as "see the card below", which reads as a finding nobody can act
            // on. The remedy is the per-app exemption request, like every other row's.
            assertEquals(
                PermissionRemedy.BATTERY_EXEMPTION_REQUEST,
                RequiredPermission.BATTERY_OPTIMIZATION_EXEMPTION.remedy,
            )
        }

        @Test
        fun `device administration is fixed by the activation prompt`() {
            assertEquals(
                PermissionRemedy.DEVICE_ADMIN_ACTIVATION,
                RequiredPermission.DEVICE_ADMIN.remedy,
            )
        }

        @Test
        fun `every audited grant has an actionable remedy`() {
            RequiredPermission.entries.forEach { permission ->
                assertNotNull(
                    RemedyRouter.destinationFor(permission.remedy),
                    "${permission.name} cannot be acted on from its row",
                )
            }
        }
    }
}
