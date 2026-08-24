package com.danielealbano.androidremotecontrolmcp.services.permissions

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.deviceadmin.PlatformDeviceAdminReceiver
import com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import com.danielealbano.androidremotecontrolmcp.utils.MonotonicClock
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the audit found, and whether the holder has waved this particular finding away for now. */
data class PermissionAuditState(
    val missing: List<RequiredPermission> = emptyList(),
    val dismissed: Boolean = false,
) {
    /** True when the card should be on screen. */
    val cardVisible: Boolean get() = missing.isNotEmpty() && !dismissed

    /** True when at least one missing grant stops the device being operated, or operated honestly. */
    val hasOperationalGap: Boolean get() = MissingPermissions.hasOperationalGap(missing)
}

/**
 * Runs the permissions audit and owns both of its surfaces: the state the card renders, and the
 * notification-shade nudge.
 *
 * WHY AN AUDIT AND NOT A PERMISSIONS SCREEN: the app already HAS a permissions screen, and it is
 * the wrong instrument — it answers a question the holder has to think to ask. A device that is
 * enrolled and attached but cannot be driven because accessibility was switched off by a system
 * update looks, from every other surface, completely healthy. The audit is what makes that
 * findable without being looked for.
 *
 * DISMISSAL IS SESSION-SCOPED, and this is the deliberate difference from
 * [com.danielealbano.androidremotecontrolmcp.ui.components.ConnectorKeepAliveHintCard], whose
 * dismissal is persisted forever. The keep-alive hint is ADVICE about a risk; a missing required
 * grant is a FAULT that is true right now. Persisting "don't tell me" about a fault would let a
 * device sit un-drivable indefinitely with every surface claiming it is fine — so [dismiss] is
 * held in memory only and every [refresh] clears it. Dismissal buys quiet for the current glance,
 * never for the condition.
 */
@Singleton
class PermissionAuditor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val clock: MonotonicClock,
    ) {
        private val _state = MutableStateFlow(PermissionAuditState())
        val state: StateFlow<PermissionAuditState> = _state.asStateFlow()

        /** What the notification last said, for the quiet-period rule. Null until it says anything. */
        @Volatile private var lastReport: LastReport? = null

        /**
         * Re-reads every grant, updates the card's state and posts, updates or cancels the shade
         * nudge. Called when the app comes to the foreground and on every watchdog tick, which is
         * what gives the audit a background surface at all — a holder who never opens the app
         * still learns that the device stopped being operable.
         */
        fun refresh(): PermissionAuditState {
            val snapshot = snapshot()
            val missing = MissingPermissions.evaluate(snapshot)
            // Clearing `dismissed` here is the session-scoped rule in code: a fresh evaluation is a
            // fresh statement of the fault, and the previous dismissal does not outlive it.
            val next = PermissionAuditState(missing = missing, dismissed = false)
            _state.value = next
            applyNotification(snapshot, missing)
            return next
        }

        /** Hides the card until the next [refresh]. */
        fun dismiss() {
            _state.value = _state.value.copy(dismissed = true)
        }

        /**
         * Reads the real state of each audited grant. Every check is the one the rest of the app
         * already trusts for that decision — [PermissionUtils] for the runtime permissions and the
         * two service bindings, [PlatformDeviceAdminReceiver.isAdminActive] for admin — so the
         * audit can never disagree with the code that actually fails.
         */
        private fun snapshot(): PermissionSnapshot {
            val powerManager = context.getSystemService(PowerManager::class.java)
            return PermissionSnapshot(
                mapOf(
                    RequiredPermission.ACCESSIBILITY_SERVICE to
                        PermissionUtils.isAccessibilityServiceEnabled(context, McpAccessibilityService::class.java),
                    RequiredPermission.POST_NOTIFICATIONS to
                        PermissionUtils.isNotificationPermissionGranted(context),
                    RequiredPermission.DEVICE_ADMIN to
                        PlatformDeviceAdminReceiver.isAdminActive(context),
                    RequiredPermission.BATTERY_OPTIMIZATION_EXEMPTION to
                        (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true),
                    RequiredPermission.NOTIFICATION_LISTENER to
                        PermissionUtils.isNotificationListenerEnabled(
                            context,
                            McpNotificationListenerService::class.java,
                        ),
                    RequiredPermission.CAMERA to PermissionUtils.isCameraPermissionGranted(context),
                    RequiredPermission.MICROPHONE to PermissionUtils.isMicrophonePermissionGranted(context),
                    RequiredPermission.LOCATION to PermissionUtils.isLocationPermissionGranted(context),
                ),
            )
        }

        private fun applyNotification(
            snapshot: PermissionSnapshot,
            missing: List<RequiredPermission>,
        ) {
            val now = clock.nowMillis()
            val decision =
                MissingPermissions.decide(
                    snapshot = snapshot,
                    missing = missing,
                    lastReport = lastReport,
                    nowMillis = now,
                    quietPeriodMillis = QUIET_PERIOD_MILLIS,
                )
            Log.i(TAG, "Permissions audit: ${missing.size} missing, notification decision $decision")
            when (decision) {
                NotificationDecision.POST -> {
                    lastReport = LastReport(missing.filter { it.isOperational }.toSet(), now)
                    post(missing)
                }

                NotificationDecision.CANCEL -> {
                    lastReport = null
                    cancel(context)
                }

                NotificationDecision.CANNOT_POST, NotificationDecision.SKIP_QUIET_PERIOD -> {
                    Unit
                }
            }
        }

        /**
         * The shade nudge. Ongoing, because it tracks a condition rather than an event: the fault
         * is still true after a swipe, so the notification stays until [refresh] finds it fixed.
         * Low importance — it must be findable, not startling; a permission gap is not an alarm.
         */
        private fun post(missing: List<RequiredPermission>) {
            val labels = missing.joinToString(separator = ", ") { context.getString(it.labelRes) }
            val text = context.getString(R.string.notification_permissions_text, labels)
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    MainActivity.permissionsIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                NotificationCompat
                    .Builder(context, McpApplication.PERMISSIONS_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.notification_permissions_title))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build()
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }

        companion object {
            private const val TAG = "MCP:PermissionAudit"

            /** Distinct from the connector's ongoing notification and from the revive nudge. */
            private const val NOTIFICATION_ID = 1004

            /**
             * How long the same finding stays quiet. Matched to the watchdog's period so a tick can
             * always re-state a fault that is still true, while the several foreground transitions
             * a holder makes in a minute cannot each re-post it.
             */
            const val QUIET_PERIOD_MILLIS = 15L * 60L * 1000L

            /** Removes the nudge. Also called by the audit itself once nothing operational is missing. */
            fun cancel(context: Context) {
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            }
        }
    }
