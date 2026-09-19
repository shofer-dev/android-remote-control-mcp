package com.danielealbano.androidremotecontrolmcp.data.model

import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationData

data class NotificationChangeEvent(
    val eventType: NotificationChangeType,
    val notification: NotificationData,
    /**
     * The framework's own [android.service.notification.StatusBarNotification.getKey].
     *
     * Carried beside [notification] rather than inside it because [NotificationData] is the MCP
     * tool surface's shape, where the identifier is deliberately the opaque
     * [NotificationData.notificationId] hash. The device-event plane reports the raw key instead
     * (`docs/phone/device_events.md` §3), so a subscriber can correlate a post with the dismissal
     * or the action that followed it.
     */
    val key: String,
)

enum class NotificationChangeType {
    POSTED,
    REMOVED,
}
