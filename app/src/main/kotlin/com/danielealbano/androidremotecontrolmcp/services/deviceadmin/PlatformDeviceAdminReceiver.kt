package com.danielealbano.androidremotecontrolmcp.services.deviceadmin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * The app's Device Administration component, activated at enrolment
 * (`docs/phones/android_remote_control.md` §3.3). Its presence is what lets the device-action
 * plane ([com.danielealbano.androidremotecontrolmcp.services.connector.PlatformDeviceActionHandler])
 * call the privileged [DevicePolicyManager] operations `lock` (`lockNow`) and `wipe`
 * (`wipeData`); the policies it may exercise are declared in `res/xml/device_admin_policies.xml`
 * (force-lock, wipe-data). `locate` and `ring` are NOT device-admin policies and do not depend
 * on this receiver.
 *
 * The receiver itself holds no logic beyond lifecycle logging — the platform decides, the app
 * executes. Admin is requested/granted out of band (the enrolment provisioning flow); when it is
 * not held, the action executors degrade honestly rather than crashing (see the handler).
 */
class PlatformDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(
        context: Context,
        intent: android.content.Intent,
    ) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(
        context: Context,
        intent: android.content.Intent,
    ) {
        Log.w(TAG, "Device admin disabled — lock/wipe are no longer available")
    }

    companion object {
        private const val TAG = "MCP:DeviceAdmin"

        /** The [ComponentName] of this receiver, used to query/enforce active-admin status. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, PlatformDeviceAdminReceiver::class.java)

        /** True if this app is currently an active device administrator. */
        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
            return dpm.isAdminActive(componentName(context))
        }
    }
}
