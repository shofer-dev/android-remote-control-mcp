package com.danielealbano.androidremotecontrolmcp.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.deviceadmin.PlatformDeviceAdminReceiver

/**
 * Every OS screen the permissions audit and the keep-alive hint send the holder to, as intent
 * FACTORIES.
 *
 * None of these grants can be made by the app: they are settings a human changes, or a system
 * dialog only the OS may show. All this file does is take the holder there — and it is best-effort
 * by construction, since a vendor build may lack any given screen. Starting one, and the
 * [Intent.FLAG_ACTIVITY_NEW_TASK] rule that decides whether a launch is even seen on MIUI, live in
 * `ActivityContext.kt`.
 *
 * ## Battery optimisation has TWO destinations and they are not interchangeable
 *
 * [batteryExemptionIntent] is the per-app REQUEST: a system dialog with a Yes button, which is
 * what a checklist row must fire. It needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the manifest.
 * [batteryOptimizationListIntent] is the whole-device LIST, where the holder has to find this app
 * among every other; it needs no permission and is the fallback when the dialog does not resolve.
 * Neither has anything to do with vendor AUTOSTART ([autostartIntent]), which is a third control
 * entirely — granting autostart does not exempt an app from doze, and the audit row says so.
 *
 * ## Autostart has no platform intent at all
 *
 * It is a vendor screen, so the only way in is the component name that vendor happens to use. Only
 * MIUI/HyperOS is named here — the phone whose killed connector this exists for — and the
 * component is declared in the manifest's `<queries>` so package-visibility filtering does not
 * hide it. Everything else falls back to this app's own details page, which is one tap from the
 * vendor's per-app controls on every skin.
 */
object OemKeepAliveSettings {
    private const val TAG = "MCP:OemKeepAlive"

    /** MIUI / HyperOS "Autostart" (Security app). Absent on stock Android and on other skins. */
    private val MIUI_AUTOSTART =
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )

    /** The vendor autostart screen. Resolves on MIUI/HyperOS and nowhere else. */
    fun autostartIntent(): Intent = Intent().apply { component = MIUI_AUTOSTART }

    /** The system's battery-optimisation LIST — every app, no permission required. */
    fun batteryOptimizationListIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * The system dialog that exempts THIS app from battery optimisation in one tap.
     *
     * The data URI is mandatory and names the package to exempt; without it the action shows
     * nothing. Gated on the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which this app
     * declares (it is an enrolled device agent, not a Play app — see the manifest).
     */
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        )

    /**
     * The system's confirm-device-admin screen for this app's
     * [com.danielealbano.androidremotecontrolmcp.services.deviceadmin.PlatformDeviceAdminReceiver],
     * carrying the explanation the OS shows above the Activate button.
     *
     * This is the only sanctioned way in: `DevicePolicyManager` has no API to self-activate, by
     * design — an administrator the user did not knowingly approve would be the whole threat
     * model.
     */
    fun deviceAdminActivationIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                PlatformDeviceAdminReceiver.componentName(context),
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.permission_audit_device_admin_reason),
            )
        }

    /**
     * The security settings screen that holds the device-admin list, used when the activation
     * prompt does not resolve. It cannot activate anything by itself — the holder has to find
     * "Device admin apps" there — so the audit row says as much whenever this is where Fix goes.
     */
    fun deviceAdminListIntent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)

    /**
     * This app's own settings page. The universal destination: it is where a revoked runtime
     * permission is re-granted when the app cannot show the runtime dialog (because the holder
     * chose "don't ask again", which is not a state the app can detect from outside an Activity),
     * and the last-resort fallback for every other screen here.
     */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    /**
     * Opens the vendor's autostart screen, falling back to this app's system settings page when
     * the device has none (or hides it).
     */
    fun openAutostart(context: Context) {
        if (context.startSettingsActivity(autostartIntent())) return
        Log.i(TAG, "No vendor autostart screen; falling back to application details")
        openAppDetails(context)
    }

    /** Opens the system's battery-optimisation list. */
    fun openBatteryOptimization(context: Context) {
        if (context.startSettingsActivity(batteryOptimizationListIntent())) return
        Log.i(TAG, "No battery optimisation screen; falling back to application details")
        openAppDetails(context)
    }

    /** Opens this app's system settings page. */
    fun openAppDetails(context: Context) {
        context.startSettingsActivity(appDetailsIntent(context))
    }
}
