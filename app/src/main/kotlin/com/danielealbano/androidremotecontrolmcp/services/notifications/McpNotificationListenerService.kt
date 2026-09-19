@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.services.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChangeEvent
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChangeType
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

class McpNotificationListenerService : NotificationListenerService() {
    private val lastSeenContentHash = ConcurrentHashMap<String, ContentHashEntry>()

    private data class ContentHashEntry(
        val hash: Int,
        val timestamp: Long = System.currentTimeMillis(),
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Logger.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Logger.i(TAG, "Notification listener disconnected")
    }

    override fun onDestroy() {
        Logger.i(TAG, "Notification listener service destroying")
        instance = null
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Logger.w(TAG, "Low memory condition reported")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == applicationContext.packageName) return
        val data = NotificationDataExtractor.extract(sbn, applicationContext)
        val now = System.currentTimeMillis()
        if (isEmptyNotification(data) || isDuplicate(sbn.key, data, now)) return

        if (lastSeenContentHash.size > CACHE_EVICTION_THRESHOLD) {
            evictExpiredEntries(now)
        }

        _notificationChangeEvents.tryEmit(
            NotificationChangeEvent(NotificationChangeType.POSTED, data, sbn.key),
        )
        Logger.d(TAG, "Notification posted: ${data.appName} - ${data.title}")
    }

    private fun isEmptyNotification(data: NotificationData): Boolean =
        data.title == null && data.text == null && data.bigText == null && data.actions.isEmpty()

    private fun isDuplicate(
        key: String,
        data: NotificationData,
        now: Long,
    ): Boolean {
        val contentHash = computeContentHash(data)
        val previous = lastSeenContentHash.put(key, ContentHashEntry(contentHash, now))
        return previous != null && previous.hash == contentHash
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == applicationContext.packageName) return
        lastSeenContentHash.remove(sbn.key)
        val data = NotificationDataExtractor.extract(sbn, applicationContext)
        _notificationChangeEvents.tryEmit(
            NotificationChangeEvent(NotificationChangeType.REMOVED, data, sbn.key),
        )
    }

    private fun computeContentHash(data: NotificationData): Int {
        var hash = data.title?.hashCode() ?: 0
        hash = HASH_PRIME * hash + (data.text?.hashCode() ?: 0)
        hash = HASH_PRIME * hash + (data.bigText?.hashCode() ?: 0)
        hash = HASH_PRIME * hash + (data.subText?.hashCode() ?: 0)
        hash = HASH_PRIME * hash + data.actions.size
        return hash
    }

    private fun evictExpiredEntries(now: Long) {
        val iterator = lastSeenContentHash.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.timestamp > CACHE_TTL_MS) {
                iterator.remove()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Logger.d(TAG, "onTrimMemory level=$level")
    }

    fun getNotifications(): Array<StatusBarNotification> = activeNotifications ?: emptyArray()

    fun dismissNotification(key: String) {
        cancelNotification(key)
    }

    fun snoozeNotificationByKey(
        key: String,
        durationMs: Long,
    ) {
        snoozeNotification(key, durationMs)
    }

    companion object {
        private const val TAG = "MCP:NotificationListener"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 1 day
        private const val CACHE_EVICTION_THRESHOLD = 100
        private const val HASH_PRIME = 31

        @Volatile
        var instance: McpNotificationListenerService? = null
            private set

        private val _notificationChangeEvents =
            MutableSharedFlow<NotificationChangeEvent>(
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val notificationChangeEvents: SharedFlow<NotificationChangeEvent> =
            _notificationChangeEvents.asSharedFlow()
    }
}
