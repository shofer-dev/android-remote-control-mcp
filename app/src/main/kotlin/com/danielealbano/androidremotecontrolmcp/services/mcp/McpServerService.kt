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
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerAppManagementTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerCameraTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerFileTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerGestureTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerIntentTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerLocationTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerNodeActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerNotificationTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerScreenIntrospectionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerSystemActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerTextInputTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerTouchActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerUtilityTools
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
import com.danielealbano.androidremotecontrolmcp.services.camera.CameraProvider
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
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
 * Foreground service that runs the MCP server (HTTP by default, optional HTTPS).
 *
 * Lifecycle:
 * 1. Started via intent from MainActivity (start/stop button)
 * 2. Calls startForeground() with persistent notification
 * 3. Reads configuration from SettingsRepository
 * 4. Creates and starts McpServer (Ktor HTTP, optionally HTTPS)
 * 5. Updates ServerStatus via companion-level StateFlow (collected by MainViewModel)
 * 6. On stop: gracefully shuts down server, clears singleton
 */
@AndroidEntryPoint
class McpServerService : Service() {
    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var actionExecutor: ActionExecutor

    @Inject lateinit var accessibilityServiceProvider: AccessibilityServiceProvider

    @Inject lateinit var screenCaptureProvider: ScreenCaptureProvider

    @Inject lateinit var treeParser: AccessibilityTreeParser

    @Inject lateinit var elementFinder: ElementFinder

    @Inject lateinit var compactTreeFormatter: CompactTreeFormatter

    @Inject lateinit var screenshotAnnotator: ScreenshotAnnotator

    @Inject lateinit var screenshotEncoder: ScreenshotEncoder

    @Inject lateinit var storageLocationProvider: StorageLocationProvider

    @Inject lateinit var fileOperationProvider: FileOperationProvider

    @Inject lateinit var appManager: AppManager

    @Inject lateinit var typeInputController: TypeInputController

    @Inject lateinit var nodeCache: AccessibilityNodeCache

    @Inject lateinit var screenStateSnapshotCache: ScreenStateSnapshotCache

    @Inject lateinit var webViewNodeMerger: WebViewNodeMerger

    @Inject lateinit var cameraProvider: CameraProvider

    @Inject lateinit var intentDispatcher: IntentDispatcher

    @Inject lateinit var notificationProvider: NotificationProvider

    @Inject lateinit var locationProvider: LocationProvider

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
            val toolNamePrefix = McpToolUtils.buildToolNamePrefix(config.deviceSlug)
            Log.i(
                TAG,
                "Starting MCP server with config: port=${config.port}, " +
                    "binding=${config.bindingAddress.address}, toolNamePrefix=$toolNamePrefix",
            )

            // Create the in-process MCP SDK Server instance and register all tools. The transport that
            // carries JSON-RPC to/from this Server is owned by the platform connector (added in a later
            // wave); the public network layer that used to bolt a Ktor transport onto it has been removed.
            val sdkServer =
                Server(
                    serverInfo =
                        Implementation(
                            name = McpToolUtils.buildServerName(config.deviceSlug),
                            version = com.danielealbano.androidremotecontrolmcp.BuildConfig.VERSION_NAME,
                        ),
                    options =
                        ServerOptions(
                            capabilities =
                                ServerCapabilities(
                                    tools = ServerCapabilities.Tools(listChanged = false),
                                ),
                        ),
                )
            registerAllTools(sdkServer, toolNamePrefix, config.toolPermissionsConfig)

            updateStatus(
                ServerStatus.Running(
                    port = config.port,
                    bindingAddress = config.bindingAddress.address,
                ),
            )

            Log.i(TAG, "MCP server started successfully on ${config.bindingAddress.address}:${config.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MCP server", e)
            updateStatus(ServerStatus.Error(e.message ?: "Unknown error starting server"))
            serverActive.set(false)
        }
    }

    private fun registerAllTools(
        server: Server,
        toolNamePrefix: String,
        perms: ToolPermissionsConfig,
    ) {
        registerScreenIntrospectionTools(
            server,
            treeParser,
            accessibilityServiceProvider,
            screenCaptureProvider,
            compactTreeFormatter,
            screenshotAnnotator,
            screenshotEncoder,
            nodeCache,
            screenStateSnapshotCache,
            webViewNodeMerger,
            toolNamePrefix,
            perms,
        )
        registerSystemActionTools(server, actionExecutor, accessibilityServiceProvider, toolNamePrefix, perms)
        registerTouchActionTools(server, actionExecutor, toolNamePrefix, perms)
        registerGestureTools(server, actionExecutor, toolNamePrefix, perms)
        registerNodeActionTools(
            server,
            treeParser,
            elementFinder,
            actionExecutor,
            accessibilityServiceProvider,
            nodeCache,
            toolNamePrefix,
            perms,
        )
        registerTextInputTools(
            server,
            treeParser,
            actionExecutor,
            accessibilityServiceProvider,
            typeInputController,
            nodeCache,
            toolNamePrefix,
            perms,
        )
        registerUtilityTools(
            server,
            treeParser,
            elementFinder,
            accessibilityServiceProvider,
            nodeCache,
            toolNamePrefix,
            perms,
        )
        registerFileTools(server, storageLocationProvider, fileOperationProvider, toolNamePrefix, perms)
        registerAppManagementTools(server, appManager, toolNamePrefix, perms)
        registerCameraTools(server, cameraProvider, fileOperationProvider, toolNamePrefix, perms)
        registerIntentTools(server, intentDispatcher, toolNamePrefix, perms)
        registerNotificationTools(server, notificationProvider, toolNamePrefix, perms)
        registerLocationTools(server, locationProvider, toolNamePrefix, perms)
    }

    override fun onDestroy() {
        screenStateSnapshotCache.clear()
        Log.i(TAG, "McpServerService destroying")
        updateStatus(ServerStatus.Stopping)

        serverActive.set(false)

        // Cancel coroutine scope
        coroutineScope.cancel()

        // Clear singleton
        instance = null

        updateStatus(ServerStatus.Stopped)
        Log.i(TAG, "McpServerService destroyed")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateStatus(status: ServerStatus) {
        _serverStatus.value = status
    }

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

        /**
         * Shared server status flow. Collected by MainViewModel to update the UI.
         * Uses a companion-level StateFlow so it survives service rebinding and is
         * accessible without requiring a bound service reference.
         */
        private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
        val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

        /**
         * Shared server log events flow. Collected by MainViewModel to display
         * log entries in the UI. Uses a SharedFlow (not StateFlow) because each
         * event is a discrete emission, not a current-state snapshot.
         *
         * extraBufferCapacity = 64 prevents dropped events during brief UI
         * collection pauses (e.g., during configuration changes).
         */
        private val _serverLogEvents = MutableSharedFlow<ServerLogEntry>(extraBufferCapacity = 64)
        val serverLogEvents: SharedFlow<ServerLogEntry> = _serverLogEvents.asSharedFlow()

        @Volatile
        var instance: McpServerService? = null
            private set
    }
}
