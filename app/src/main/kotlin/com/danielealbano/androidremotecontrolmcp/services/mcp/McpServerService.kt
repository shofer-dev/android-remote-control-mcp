package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Foreground service that constructs the in-process MCP tool [Server].
 *
 * Since the public network layer was removed, this service no longer bolts a transport onto
 * the Server itself — the platform connector owns the transport (see
 * [com.danielealbano.androidremotecontrolmcp.services.connector.PlatformConnectorService]).
 * The Server is now built through [McpToolServerFactory], the single construction+registration
 * path shared with the connector, so the two never diverge on tool set or name prefix.
 *
 * Lifecycle:
 * 1. Started via intent from MainActivity (start/stop button) or the adb path.
 * 2. Calls startForeground() with a persistent notification.
 * 3. Reads configuration from SettingsRepository and builds the Server.
 * 4. Updates ServerStatus via companion-level StateFlow (collected by MainViewModel).
 */
@AndroidEntryPoint
class McpServerService : Service() {
    @Inject lateinit var settingsRepository:
        com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository

    @Inject lateinit var mcpToolServerFactory: McpToolServerFactory

    @Inject lateinit var screenStateSnapshotCache: ScreenStateSnapshotCache

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverActive = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "McpServerService created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                if (!serverActive.compareAndSet(false, true)) {
                    Log.w(TAG, "Server already starting or running, ignoring duplicate start request")
                } else {
                    coroutineScope.launch {
                        startServer()
                    }
                }
            }
        }

        return START_STICKY
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun startServer() {
        try {
            updateStatus(ServerStatus.Starting)

            val config = settingsRepository.getServerConfig()
            Log.i(TAG, "Building MCP tool server (deviceSlug='${config.deviceSlug}')")

            // Construct + register all tools via the shared factory. The transport that carries
            // JSON-RPC to/from this Server is owned by the platform connector.
            mcpToolServerFactory.create(config)

            updateStatus(
                ServerStatus.Running(
                    port = config.port,
                    bindingAddress = config.bindingAddress.address,
                ),
            )

            Log.i(TAG, "MCP tool server constructed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build MCP server", e)
            updateStatus(ServerStatus.Error(e.message ?: "Unknown error starting server"))
            serverActive.set(false)
        }
    }

    override fun onDestroy() {
        screenStateSnapshotCache.clear()
        Log.i(TAG, "McpServerService destroying")
        updateStatus(ServerStatus.Stopping)

        serverActive.set(false)

        coroutineScope.cancel()

        instance = null

        updateStatus(ServerStatus.Stopped)
        Log.i(TAG, "McpServerService destroyed")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateStatus(status: ServerStatus) {
        _serverStatus.value = status
    }

    @Suppress("unused")
    private fun emitLogEntry(entry: ServerLogEntry) {
        _serverLogEvents.tryEmit(entry)
    }

    private fun createNotification(): Notification {
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat
            .Builder(this, McpApplication.MCP_SERVER_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_mcp_server_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MCP:ServerService"
        const val ACTION_START = "com.danielealbano.androidremotecontrolmcp.ACTION_START_MCP_SERVER"
        const val ACTION_STOP = "com.danielealbano.androidremotecontrolmcp.ACTION_STOP_MCP_SERVER"
        const val NOTIFICATION_ID = 1001

        private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
        val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

        private val _serverLogEvents = MutableSharedFlow<ServerLogEntry>(extraBufferCapacity = 64)
        val serverLogEvents: SharedFlow<ServerLogEntry> = _serverLogEvents.asSharedFlow()

        @Volatile
        var instance: McpServerService? = null
            private set
    }
}
