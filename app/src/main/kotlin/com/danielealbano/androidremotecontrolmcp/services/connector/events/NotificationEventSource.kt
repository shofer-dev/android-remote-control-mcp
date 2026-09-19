package com.danielealbano.androidremotecontrolmcp.services.connector.events

import android.content.Context
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChangeType
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * The `notification` category: an app put something in the shade.
 *
 * It registers NOTHING. The app already runs a bound [McpNotificationListenerService] whose
 * de-duplicated change flow the MCP tool surface and the channel plugin both read, and a second
 * listener component would be a second thing for the holder to grant and a second copy of the
 * empty-notification and duplicate-content rules to keep in step. This source is a projection of
 * that flow onto the wire shape.
 *
 * ## POSTED only
 *
 * A REMOVED event is dropped. The plane reports what the WORLD did to the device
 * (`docs/phone/device_events.md` §3) — a dismissal is what somebody did to the device's own UI,
 * usually the holder swiping, and it carries no information the agent can act on. Forwarding both
 * would also double the plane's busiest category for nothing.
 *
 * ## The grant is LOGGED, never gated on
 *
 * Notification access is a SPECIAL-ACCESS binding, not a runtime permission: only the holder can
 * grant it, on a Settings screen, and they may do so while the device is attached. Without it the
 * listener service is never bound and the shared flow is simply silent — so the check here buys one
 * honest log line and nothing else. Returning an empty flow instead would be the WORSE behaviour it
 * looks like: a grant made mid-session would then report nothing until the next reconnect, which on
 * a healthy link may be hours.
 */
class NotificationEventSource(
    private val appContext: Context,
) : DeviceEventSource {
    override val category: DeviceEventCategory = DeviceEventCategory.NOTIFICATION

    override fun events(): Flow<DeviceEvent> =
        McpNotificationListenerService.notificationChangeEvents
            .filter { it.eventType == NotificationChangeType.POSTED }
            .map { event ->
                val data = event.notification
                DeviceEvent(
                    category = category,
                    occurredAtMillis = data.timestamp,
                    payload = EventPayloads.notification(data, event.key),
                    subjectPackage = data.packageName,
                )
            }.onStart { logGrant() }

    /** States, once per socket, whether this category can actually produce anything. */
    private fun logGrant() {
        val granted =
            PermissionUtils.isNotificationListenerEnabled(appContext, McpNotificationListenerService::class.java)
        if (granted) {
            Log.i(TAG, "Reporting notification events")
        } else {
            Log.i(TAG, "Notification access is not granted; this device reports no notification events yet")
        }
    }

    private companion object {
        const val TAG = "MCP:NotificationEvents"
    }
}
