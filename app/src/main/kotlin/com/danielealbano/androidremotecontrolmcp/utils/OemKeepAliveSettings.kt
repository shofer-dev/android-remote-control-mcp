package com.danielealbano.androidremotecontrolmcp.utils

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.deviceadmin.PlatformDeviceAdminReceiver

/**
 * Opens the two OS screens that decide whether a background connector survives on this phone.
 *
 * Neither is a permission the app can request. They are settings a human has to change, so all
 * this does is take them there — and it is best-effort by construction: nothing in the app is
 * gated on either, and a phone where both screens are missing still works, just less reliably.
 *
 * BATTERY OPTIMISATION is the one with a documented, universal intent
 * ([Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS], which opens the system list and needs
 * no permission — unlike `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which demands a
 * Play-restricted one). It matters twice over: it keeps the OS from freezing the connector, AND
 * it is a documented exemption from the Android 12+ ban on starting a foreground service from the
 * background — which is precisely what the watchdog
 * ([com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorWatchdogWorker]) needs
 * to be able to do.
 *
 * AUTOSTART has no platform intent at all: it is a vendor screen, so the only way in is the
 * component name that vendor happens to use. Only MIUI/HyperOS is named here — the phone whose
 * killed connector this exists for — and the component is declared in the manifest's `<queries>`
 * so package-visibility filtering does not hide it. Everything else falls back to this app's own
 * details page, which is one tap from the vendor's per-app controls on every skin.
 */
object OemKeepAliveSettings {
    private const val TAG = "MCP:OemKeepAlive"

    /** MIUI / HyperOS "Autostart" (Security app). Absent on stock Android and on other skins. */
    private val MIUI_AUTOSTART =
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )

    /**
     * Opens the vendor's autostart screen, falling back to this app's system settings page when
     * the device has none (or hides it).
     */
    fun openAutostart(context: Context) {
        val vendorIntent =
            Intent().apply {
                component = MIUI_AUTOSTART
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (launch(context, vendorIntent)) return
        Log.i(TAG, "No vendor autostart screen; falling back to application details")
        openAppDetails(context)
    }

    /** Opens the system's battery-optimisation list. */
    fun openBatteryOptimization(context: Context) {
        val intent =
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (launch(context, intent)) return
        Log.i(TAG, "No battery optimisation screen; falling back to application details")
        openAppDetails(context)
    }

    /**
     * Opens the system's confirm-device-admin screen for this app's
     * [com.danielealbano.androidremotecontrolmcp.services.deviceadmin.PlatformDeviceAdminReceiver],
     * carrying the explanation the OS shows above the Activate button.
     *
     * This is the only sanctioned way in: `DevicePolicyManager` has no API to self-activate, by
     * design — an administrator the user did not knowingly approve would be the whole threat
     * model. Falls back to this app's details page on a build that refuses the action (a device
     * already managed by another owner).
     */
    fun openDeviceAdminActivation(context: Context) {
        val intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    PlatformDeviceAdminReceiver.componentName(context),
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(R.string.permission_audit_device_admin_reason),
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (launch(context, intent)) return
        Log.i(TAG, "Device admin activation refused; falling back to application details")
        openAppDetails(context)
    }

    /**
     * This app's own settings page. The universal destination: it is where a revoked runtime
     * permission is re-granted when the app cannot show the runtime dialog (because the holder
     * chose "don't ask again", which is not a state the app can detect from outside an Activity).
     */
    fun openAppDetails(context: Context) {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        launch(context, intent)
    }

    /**
     * Starts [intent], reporting whether it went anywhere. Both failure modes are expected rather
     * than exceptional: the screen may not exist on this build (`ActivityNotFoundException`) or
     * may exist but not be launchable by us (`SecurityException`), and either way the caller has
     * a fallback.
     */
    private fun launch(
        context: Context,
        intent: Intent,
    ): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "Settings screen not present on this device", e)
            false
        } catch (e: SecurityException) {
            Log.i(TAG, "Settings screen refused the launch", e)
            false
        }
}
