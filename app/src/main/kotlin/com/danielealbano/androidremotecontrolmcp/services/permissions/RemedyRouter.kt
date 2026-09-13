package com.danielealbano.androidremotecontrolmcp.services.permissions

/**
 * The actual screen a Fix button opens.
 *
 * Distinct from [PermissionRemedy], which says what the grant NEEDS; this says where the holder
 * can be taken on THIS build. The two differ whenever a vendor ships an Android without one of the
 * system screens the framework documents, which is not hypothetical — it is the whole reason a
 * checklist row can end up doing nothing at all.
 */
enum class RemedyDestination {
    /** The app's own permissions screen, which can still raise the runtime dialog. */
    APP_PERMISSIONS,

    /** The system accessibility list. */
    ACCESSIBILITY_SETTINGS,

    /** The system's confirm-device-admin prompt: one screen, one Activate button. */
    DEVICE_ADMIN_PROMPT,

    /** Security settings, where the holder finds the device-admin list by hand. */
    DEVICE_ADMIN_LIST,

    /** The system notification-access list. */
    NOTIFICATION_LISTENER_SETTINGS,

    /** The per-app "allow this app to ignore battery optimisations?" dialog. */
    BATTERY_EXEMPTION_PROMPT,

    /** The whole-device battery-optimisation list, where the holder finds this app by hand. */
    BATTERY_OPTIMIZATION_LIST,
}

/**
 * Which of the two system PROMPTS this build actually has.
 *
 * Both default to present, because that is the answer on every Android that follows the framework
 * — the flags exist to carry a negative answer that was MEASURED (`PackageManager` resolution),
 * never assumed.
 */
data class RemedyCapabilities(
    val deviceAdminPrompt: Boolean = true,
    val batteryExemptionPrompt: Boolean = true,
)

/**
 * Chooses the destination for a remedy, given what the build can do.
 *
 * Pure by design: the alternative — deciding inside the click handler, next to the `startActivity`
 * call — is how a fallback ends up existing for one remedy and not the other, with nothing able to
 * tell you which. Here it is one exhaustive `when` with a test per arm.
 *
 * A fallback destination is WEAKER than the prompt it replaces: it opens a list the holder has to
 * search rather than granting anything, so a row routed to one says so in its text — which is the
 * UI's job, since only it owns the wording.
 */
object RemedyRouter {
    fun destinationFor(
        remedy: PermissionRemedy,
        capabilities: RemedyCapabilities = RemedyCapabilities(),
    ): RemedyDestination =
        when (remedy) {
            PermissionRemedy.RUNTIME_REQUEST -> {
                RemedyDestination.APP_PERMISSIONS
            }

            PermissionRemedy.ACCESSIBILITY_SETTINGS -> {
                RemedyDestination.ACCESSIBILITY_SETTINGS
            }

            PermissionRemedy.NOTIFICATION_LISTENER_SETTINGS -> {
                RemedyDestination.NOTIFICATION_LISTENER_SETTINGS
            }

            PermissionRemedy.DEVICE_ADMIN_ACTIVATION -> {
                if (capabilities.deviceAdminPrompt) {
                    RemedyDestination.DEVICE_ADMIN_PROMPT
                } else {
                    RemedyDestination.DEVICE_ADMIN_LIST
                }
            }

            PermissionRemedy.BATTERY_EXEMPTION_REQUEST -> {
                if (capabilities.batteryExemptionPrompt) {
                    RemedyDestination.BATTERY_EXEMPTION_PROMPT
                } else {
                    RemedyDestination.BATTERY_OPTIMIZATION_LIST
                }
            }
        }
}
