package com.danielealbano.androidremotecontrolmcp.services.permissions

import androidx.annotation.StringRes
import com.danielealbano.androidremotecontrolmcp.R

/**
 * How a grant is obtained, which is what decides where its "fix this" button can send the holder.
 *
 * The distinction is not cosmetic: a [RUNTIME] grant can be asked for from inside the app with a
 * system dialog, while a [SPECIAL_ACCESS] one can only be toggled by the holder on a Settings
 * screen we are allowed to open but never to answer for them.
 */
enum class PermissionKind {
    RUNTIME,
    SPECIAL_ACCESS,
}

/**
 * What actually breaks when the grant is missing — the input to "is this worth a notification".
 *
 * [OPERATIONAL] means the device cannot be operated, or cannot be operated HONESTLY: the driving
 * plane is dead, a custody action the platform may issue cannot be performed, or the transparency
 * signal that tells the holder they are being driven cannot be shown. [TOOL_SURFACE] means one
 * curated tool family returns an error and everything else still works.
 *
 * Only an [OPERATIONAL] gap raises the notification. A phone whose camera permission was never
 * granted is not a phone in trouble, and nagging about it in the notification shade would teach
 * the holder to ignore the row that does matter.
 */
enum class PermissionCriticality {
    OPERATIONAL,
    TOOL_SURFACE,
}

/**
 * Where the card's per-item button sends the holder.
 *
 * EVERY remedy is actionable from the row it belongs to, and that is a rule rather than an
 * accident. Battery optimisation used to be the exception — it rendered as a cross-reference to
 * [com.danielealbano.androidremotecontrolmcp.ui.components.ConnectorKeepAliveHintCard] instead of
 * a button, on the theory that the card explains the OEM problem better. In the holder's hands
 * that read as a broken row: a finding with no way to act on it, next to four rows that act. The
 * card stays — it is the only place vendor AUTOSTART is offered, which is a different control —
 * but the row fires the per-app exemption dialog itself, like every other row.
 *
 * [RemedyRouter] turns one of these into the actual destination, because two of them have a
 * fallback when the system screen they want is missing from a vendor build.
 */
enum class PermissionRemedy {
    RUNTIME_REQUEST,
    ACCESSIBILITY_SETTINGS,
    DEVICE_ADMIN_ACTIVATION,
    NOTIFICATION_LISTENER_SETTINGS,
    BATTERY_EXEMPTION_REQUEST,
}

/**
 * The grants this app needs to work as an OPERATED DEVICE, each with a real runtime check behind
 * it (see [PermissionAuditor.snapshot]).
 *
 * The set is CURATED rather than derived from the manifest's `<uses-permission>` list, because
 * that list is a superset by construction: it carries install-time permissions nobody can revoke
 * (`INTERNET`, `FOREGROUND_SERVICE`), permissions the platform build does not exercise
 * (`CHANGE_WIFI_STATE`, the `READ_MEDIA_*` trio, which only matter to the standalone storage
 * mode), and it cannot express the two most load-bearing grants at all — accessibility and device
 * admin are component bindings, not permissions. An audit built from the manifest would therefore
 * be simultaneously noisy and incomplete.
 *
 * NOT audited, deliberately: **OEM autostart**. It is the other half of the keep-alive story, but
 * it has no runtime probe — there is no API that answers "is this app allowed to start itself on
 * this vendor's build" — so it can only ever be advice, never a finding. It stays as an action on
 * the keep-alive card and is not a row here, because a row that can never be satisfied would make
 * the audit permanently red and worthless.
 */
enum class RequiredPermission(
    val kind: PermissionKind,
    val criticality: PermissionCriticality,
    val remedy: PermissionRemedy,
    @param:StringRes val labelRes: Int,
    @param:StringRes val reasonRes: Int,
) {
    /**
     * The whole driving plane. Without the accessibility service bound there is no screen to read
     * and no gesture to dispatch, so every curated tool from `screen_state` to `tap` fails.
     */
    ACCESSIBILITY_SERVICE(
        kind = PermissionKind.SPECIAL_ACCESS,
        criticality = PermissionCriticality.OPERATIONAL,
        remedy = PermissionRemedy.ACCESSIBILITY_SETTINGS,
        labelRes = R.string.permission_audit_accessibility,
        reasonRes = R.string.permission_audit_accessibility_reason,
    ),

    /**
     * Governance, not decoration. The connector's ongoing notification is how a holder learns the
     * device is attached and — while a session is driving — that it is being driven right now; the
     * watchdog's tap-to-reconnect fallback is a notification too. Denied, the device can still be
     * driven but can no longer TELL anyone, which is the one failure this app must not have.
     *
     * Audited only on API 33+, where the permission exists. Below it notifications are on by
     * default and there is nothing to grant, so the check reports it held and this row never
     * appears — see `PermissionUtils.isNotificationPermissionGranted`.
     */
    POST_NOTIFICATIONS(
        kind = PermissionKind.RUNTIME,
        criticality = PermissionCriticality.OPERATIONAL,
        remedy = PermissionRemedy.RUNTIME_REQUEST,
        labelRes = R.string.permission_audit_notifications,
        reasonRes = R.string.permission_audit_notifications_reason,
    ),

    /**
     * The custody plane's `lock` and `wipe` actions run through `DevicePolicyManager` and need
     * this app to be an active administrator; without it the platform can issue them and the
     * device can only refuse.
     */
    DEVICE_ADMIN(
        kind = PermissionKind.SPECIAL_ACCESS,
        criticality = PermissionCriticality.OPERATIONAL,
        remedy = PermissionRemedy.DEVICE_ADMIN_ACTIVATION,
        labelRes = R.string.permission_audit_device_admin,
        reasonRes = R.string.permission_audit_device_admin_reason,
    ),

    /**
     * Not a permission the app holds but an exemption the holder grants, and the watchdog's
     * ability to revive a killed connector from the background depends on it (Android 12+ refuses
     * a background foreground-service start otherwise).
     *
     * It is NOT vendor autostart, and the row's text says so: the two are separate controls, they
     * are granted on separate screens, and a holder who turns on MIUI's autostart — as one did —
     * is entitled to wonder why this row is still red.
     */
    BATTERY_OPTIMIZATION_EXEMPTION(
        kind = PermissionKind.SPECIAL_ACCESS,
        criticality = PermissionCriticality.OPERATIONAL,
        remedy = PermissionRemedy.BATTERY_EXEMPTION_REQUEST,
        labelRes = R.string.permission_audit_battery,
        reasonRes = R.string.permission_audit_battery_reason,
    ),

    /** The notification tool family — reading and acting on what the device is showing. */
    NOTIFICATION_LISTENER(
        kind = PermissionKind.SPECIAL_ACCESS,
        criticality = PermissionCriticality.TOOL_SURFACE,
        remedy = PermissionRemedy.NOTIFICATION_LISTENER_SETTINGS,
        labelRes = R.string.permission_audit_notification_listener,
        reasonRes = R.string.permission_audit_notification_listener_reason,
    ),

    /** The camera tool family. */
    CAMERA(
        kind = PermissionKind.RUNTIME,
        criticality = PermissionCriticality.TOOL_SURFACE,
        remedy = PermissionRemedy.RUNTIME_REQUEST,
        labelRes = R.string.permission_audit_camera,
        reasonRes = R.string.permission_audit_camera_reason,
    ),

    /** The microphone tool family. */
    MICROPHONE(
        kind = PermissionKind.RUNTIME,
        criticality = PermissionCriticality.TOOL_SURFACE,
        remedy = PermissionRemedy.RUNTIME_REQUEST,
        labelRes = R.string.permission_audit_microphone,
        reasonRes = R.string.permission_audit_microphone_reason,
    ),

    /**
     * The `sms` device-event category alone (`services/connector/events/SmsEventSource.kt`).
     *
     * A TOOL_SURFACE gap by the same test as the camera: the device is operated exactly as well
     * without it, and one of five reporting categories goes quiet. Raising it to OPERATIONAL would
     * put a permanent row in the notification shade of every phone whose holder reasonably declined
     * SMS access, which is how a shade nudge gets trained away.
     */
    RECEIVE_SMS(
        kind = PermissionKind.RUNTIME,
        criticality = PermissionCriticality.TOOL_SURFACE,
        remedy = PermissionRemedy.RUNTIME_REQUEST,
        labelRes = R.string.permission_audit_sms,
        reasonRes = R.string.permission_audit_sms_reason,
    ),

    /** The location tool family, and the custody plane's `locate` action. */
    LOCATION(
        kind = PermissionKind.RUNTIME,
        criticality = PermissionCriticality.TOOL_SURFACE,
        remedy = PermissionRemedy.RUNTIME_REQUEST,
        labelRes = R.string.permission_audit_location,
        reasonRes = R.string.permission_audit_location_reason,
    ),
    ;

    /** True when the grant's absence stops the device being operated, or operated honestly. */
    val isOperational: Boolean get() = criticality == PermissionCriticality.OPERATIONAL
}
