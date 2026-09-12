package com.danielealbano.androidremotecontrolmcp.services.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import java.util.concurrent.ConcurrentHashMap

object NotificationDataExtractor {
    private const val TAG = "MCP:NotifExtractor"
    private val appNameCache = ConcurrentHashMap<String, String>()

    /**
     * Reads an app's [android.content.pm.ApplicationInfo] with no flags.
     *
     * The typed `ApplicationInfoFlags` overload arrived in API 33 and is the only one that is not
     * deprecated there; the `Int` overload it replaced still exists and is the only one API 31/32
     * has. Both ask for exactly the same thing — flags of zero — so the branch is a spelling
     * difference, not a behavioural one.
     */
    private fun applicationInfo(
        pm: PackageManager,
        packageName: String,
    ) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0)
    }

    fun extract(
        sbn: StatusBarNotification,
        context: Context,
    ): NotificationData {
        val notification = sbn.notification
        val extras = notification.extras
        val appName =
            appNameCache.getOrPut(sbn.packageName) {
                val pm = context.packageManager
                try {
                    pm.getApplicationLabel(applicationInfo(pm, sbn.packageName)).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    Logger.d(TAG, "App not found for ${sbn.packageName}, using package name")
                    sbn.packageName
                }
            }
        val actions =
            notification.actions?.mapIndexed { index, action ->
                NotificationActionData(
                    actionId = NotificationProviderImpl.computeActionHash(sbn.key, index),
                    index = index,
                    title = action.title?.toString() ?: "",
                    acceptsText = action.remoteInputs?.any { !it.isDataOnly } ?: false,
                )
            } ?: emptyList()
        return NotificationData(
            notificationId = NotificationProviderImpl.computeNotificationHash(sbn.key),
            packageName = sbn.packageName,
            appName = appName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            timestamp = sbn.postTime,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            isClearable = sbn.isClearable,
            category = notification.category,
            groupKey = sbn.groupKey,
            actions = actions,
        )
    }
}
