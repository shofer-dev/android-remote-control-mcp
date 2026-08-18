package com.danielealbano.androidremotecontrolmcp.services.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
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
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single construction point for the in-process MCP [Server], hoisted out of
 * [McpServerService] so BOTH that service and the platform connector obtain a Server built
 * and registered the SAME way (fork map §3.2, risk 1). Before this factory the Server was a
 * local `val` with ~21 injected collaborators, reachable by nobody but the service; the
 * connector needs the identical, fully-registered Server to bridge relay frames onto.
 *
 * [create] returns a FRESH Server per call rather than a shared singleton, because tool
 * registration is parameterised by runtime config — the device-slug tool-name prefix and the
 * [ToolPermissionsConfig] kill-switch are baked in at registration time (registration-time,
 * not call-time; fork map §3.3). A shared singleton could not carry per-config registration.
 * Each caller connects its own transport/session to its own Server; the Server type supports
 * that, and there is exactly one construction+registration path (this class) so there is no
 * risk of divergent tool sets or prefix drift between the two callers.
 */
@Singleton
@Suppress("LongParameterList")
class McpToolServerFactory
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val screenCaptureProvider: ScreenCaptureProvider,
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val compactTreeFormatter: CompactTreeFormatter,
        private val screenshotAnnotator: ScreenshotAnnotator,
        private val screenshotEncoder: ScreenshotEncoder,
        private val storageLocationProvider: StorageLocationProvider,
        private val fileOperationProvider: FileOperationProvider,
        private val appManager: AppManager,
        private val typeInputController: TypeInputController,
        private val nodeCache: AccessibilityNodeCache,
        private val screenStateSnapshotCache: ScreenStateSnapshotCache,
        private val webViewNodeMerger: WebViewNodeMerger,
        private val cameraProvider: CameraProvider,
        private val intentDispatcher: IntentDispatcher,
        private val notificationProvider: NotificationProvider,
        private val locationProvider: LocationProvider,
    ) {
        /**
         * Builds a fully-registered [Server] (all 58 tools) for the given [config]. The
         * server name and tool-name prefix derive from [ServerConfig.deviceSlug]; the tool set
         * is gated by [ServerConfig.toolPermissionsConfig].
         */
        fun create(config: ServerConfig): Server {
            val toolNamePrefix = McpToolUtils.buildToolNamePrefix(config.deviceSlug)
            val server =
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
            registerAllTools(server, toolNamePrefix, config.toolPermissionsConfig)
            return server
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
    }
