package com.danielealbano.androidremotecontrolmcp.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Utility functions for checking and requesting Android permissions.
 */
object PermissionUtils {
    private const val ENABLED_SERVICES_SEPARATOR = ':'

    /**
     * Checks whether a specific accessibility service is currently enabled.
     *
     * Reads the `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` system setting
     * and checks if the given service class is listed.
     *
     * @param context Application context.
     * @param serviceClass The accessibility service class to check (e.g., `McpAccessibilityService::class.java`).
     * @return `true` if the service is enabled, `false` otherwise.
     */
    fun isAccessibilityServiceEnabled(
        context: Context,
        serviceClass: Class<*>,
    ): Boolean {
        val expectedComponentName =
            "${context.packageName}/${serviceClass.canonicalName}"

        val enabledServices =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

        return enabledServices
            .split(ENABLED_SERVICES_SEPARATOR)
            .any { it.equals(expectedComponentName, ignoreCase = true) }
    }

    /**
     * Opens the Android Accessibility Settings screen.
     *
     * @param context Application context. Uses [Intent.FLAG_ACTIVITY_NEW_TASK]
     *   so this can be called from non-Activity contexts.
     */
    fun openAccessibilitySettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    /**
     * Checks whether the app may post notifications.
     *
     * `POST_NOTIFICATIONS` exists only from API 33. Below it the permission is not defined by the
     * platform at all, so the `<uses-permission>` entry is ignored at install and
     * `ContextCompat.checkSelfPermission` answers `PERMISSION_DENIED` — the package's permission
     * state simply has no row for a permission the framework never declared. Reading that answer
     * literally would be a lie in the only direction that matters: an Android 12 phone posts
     * notifications by default, yet every surface fed by this check (the permissions screen, the
     * audit card, the shade nudge's `CANNOT_POST` rule) would report a permanent, unfixable gap —
     * unfixable because there is nothing to ask for, and `pm grant` answers "Unknown permission"
     * on such a build too.
     *
     * So below API 33 the grant is NOT APPLICABLE and is reported as held. It is the only honest
     * answer to the question this function is actually asked: may this device tell its holder what
     * is happening to it.
     *
     * @param context Application context.
     * @param sdkInt The running API level; injectable because [android.os.Build.VERSION.SDK_INT]
     *   is a static final field that unit tests cannot set.
     * @return `true` if notifications may be posted, `false` otherwise.
     */
    fun isNotificationPermissionGranted(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks whether the `CAMERA` runtime permission is granted.
     *
     * @param context Application context.
     * @return `true` if camera permission is granted, `false` otherwise.
     */
    fun isCameraPermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks whether the `RECORD_AUDIO` runtime permission is granted.
     *
     * @param context Application context.
     * @return `true` if microphone permission is granted, `false` otherwise.
     */
    fun isMicrophonePermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks whether the `ACCESS_FINE_LOCATION` runtime permission is granted.
     *
     * @param context Application context.
     * @return `true` if location permission is granted, `false` otherwise.
     */
    fun isLocationPermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks whether a specific notification listener service is currently enabled.
     *
     * Reads the `Settings.Secure` `enabled_notification_listeners` system setting
     * and checks if the given service class is listed.
     *
     * @param context Application context.
     * @param serviceClass The notification listener service class to check.
     * @return `true` if the service is enabled, `false` otherwise.
     */
    fun isNotificationListenerEnabled(
        context: Context,
        serviceClass: Class<*>,
    ): Boolean {
        val expectedComponentName =
            "${context.packageName}/${serviceClass.canonicalName}"

        val enabledListeners =
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false

        return enabledListeners
            .split(ENABLED_SERVICES_SEPARATOR)
            .any { it.equals(expectedComponentName, ignoreCase = true) }
    }

    /**
     * Opens the Android Notification Listener Settings screen.
     *
     * @param context Application context. Uses [Intent.FLAG_ACTIVITY_NEW_TASK]
     *   so this can be called from non-Activity contexts.
     */
    fun openNotificationListenerSettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }
}
