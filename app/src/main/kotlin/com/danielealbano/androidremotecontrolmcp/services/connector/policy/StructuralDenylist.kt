package com.danielealbano.androidremotecontrolmcp.services.connector.policy

/**
 * The refusals that are NOT policy — the app's own UI and the OS Settings app are permanently
 * outside the drivable set (`docs/phones/android_remote_control.md` §6.4).
 *
 * They are hard-coded here rather than shipped in the [DevicePolicy] snapshot, and the
 * distinction is the whole point: a policy-supplied denylist is a denylist the platform can
 * shorten, and the platform is exactly the party these two entries defend against. The agent
 * driving this device must never be able to reach the accessibility toggle, the device-admin
 * screen or this app's own configuration — the controls that constrain it — however the
 * platform's policy is authored, and however persuasive the text on the screen is (§6.5).
 *
 * Two entries, two reasons:
 * - **This app's own package** ([ownPackage], which is `BuildConfig.APPLICATION_ID` and so
 *   carries the `.debug` suffix on a debug build). Driving our own UI could stop the
 *   connector, clear the enrolment or edit the connector configuration.
 * - **The OS Settings app.** [AOSP_SETTINGS_PACKAGES] names the ones shipped by AOSP and
 *   Google; an OEM's own Settings package is resolved at runtime instead of guessed
 *   ([AndroidDeviceEnvironment] asks the PackageManager which package handles
 *   `Settings.ACTION_SETTINGS`), because a hard-coded list of vendor spellings would fail
 *   silently on the first phone nobody thought of.
 *
 * What this deliberately does NOT do is block PERCEPTION. A read tool that observes the
 * Settings screen discloses nothing the holder is not already looking at, and blinding the
 * agent to where it is would make it worse at recovering (it could not see that it needs to
 * press Back). The refusal is on AGENCY: input into a denied app, and launching one.
 */
object StructuralDenylist {
    /**
     * The Settings packages shipped by AOSP and Google. `settings.intelligence` is the search
     * front-end that hosts the same toggles, so it is denied for the same reason as Settings
     * itself.
     */
    val AOSP_SETTINGS_PACKAGES: Set<String> =
        setOf(
            "com.android.settings",
            "com.android.settings.intelligence",
            "com.google.android.settings",
            "com.android.systemui",
        )

    /**
     * The permanent denylist: this app plus every known Settings package plus whatever the
     * OS reports as its Settings handler ([resolvedSettingsPackages], possibly empty).
     */
    fun denied(
        ownPackage: String,
        resolvedSettingsPackages: Set<String> = emptySet(),
    ): Set<String> = AOSP_SETTINGS_PACKAGES + resolvedSettingsPackages + ownPackage

    /**
     * True when [packageName] is structurally undrivable. A null or blank package is NOT
     * denied here: "we could not tell which app is in the foreground" is a different failure
     * with a different answer (see [PolicyEnforcer], which refuses it as an unknown
     * foreground rather than pretending it is Settings).
     */
    fun isDenied(
        packageName: String?,
        ownPackage: String,
        resolvedSettingsPackages: Set<String> = emptySet(),
    ): Boolean {
        val target = packageName?.trim().orEmpty()
        if (target.isEmpty()) return false
        return denied(ownPackage, resolvedSettingsPackages).contains(target)
    }
}
