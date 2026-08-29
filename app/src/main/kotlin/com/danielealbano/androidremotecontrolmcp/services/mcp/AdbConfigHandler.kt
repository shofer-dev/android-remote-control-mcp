package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.PlatformConnectorService
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider

/**
 * Handles ADB configuration broadcast intents by parsing extras and
 * applying them to [SettingsRepository].
 *
 * Extracted from [AdbConfigReceiver] to allow unit testing without
 * Hilt's [dagger.hilt.android.AndroidEntryPoint] injection lifecycle.
 *
 * Platform-connector extras on `ADB_CONFIGURE`:
 * - `edge_host` — the device-edge host for the public path (`wss://<host>/ws/phone`).
 * - `gateway_url` — a full `ws://…/ws/phone` or `wss://…/ws/phone` URL used verbatim, taking
 *   precedence over `edge_host`. This is the in-cluster path: an emulated device in an
 *   egress-locked pod reaches its internal gateway service over plain `ws://` with an explicit
 *   port, which the `edge_host` form cannot express.
 * - `enrolment_code` — the one-time pairing code.
 * - `connector_auto_start` — persisted, AND when `true` the connector is STARTED in the same
 *   broadcast (a `PlatformConnectorService.ACTION_START`), since a configure that turns auto-start
 *   on is the supervisor's signal to bring the connector up now.
 *
 * `ADB_START_CONNECTOR` / `ADB_STOP_CONNECTOR` also move the durable
 * [com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig.stoppedByUser] veto, so a
 * supervisor's stop is respected by the self-heal paths
 * ([com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorAutoStart]) rather than
 * undone by them.
 */
@Suppress("TooManyFunctions")
class AdbConfigHandler(
    private val settingsRepository: SettingsRepository,
    private val storageLocationProvider: StorageLocationProvider,
) {
    /**
     * Dispatches the intent to the appropriate handler based on its action.
     */
    suspend fun handle(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            AdbConfigReceiver.ACTION_CONFIGURE -> handleConfigure(context, intent)
            AdbConfigReceiver.ACTION_START_SERVER -> handleStartServer(context)
            AdbConfigReceiver.ACTION_STOP_SERVER -> handleStopServer(context)
            AdbConfigReceiver.ACTION_START_CONNECTOR -> handleConnector(context, PlatformConnectorService.ACTION_START)
            AdbConfigReceiver.ACTION_STOP_CONNECTOR -> handleConnector(context, PlatformConnectorService.ACTION_STOP)
            else -> Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
        }
    }

    private suspend fun handleConfigure(
        context: Context,
        intent: Intent,
    ) {
        Log.i(TAG, "Received ADB configuration broadcast")

        applyBindingAddress(intent)
        applyPort(intent)
        applyAutoStartOnBoot(intent)
        applyFileSizeLimit(intent)
        applyAllowHttpDownloads(intent)
        applyAllowUnverifiedHttpsCerts(intent)
        applyDownloadTimeout(intent)
        applyDeviceSlug(intent)
        applyToolPermissions(intent)
        applyStorageLocationPermissions(intent)
        applyEdgeHost(intent)
        applyGatewayUrl(intent)
        applyEnrolmentCode(intent)
        // Runs LAST so every connector setting above is persisted before the connector starts and
        // reads its configuration.
        applyConnectorAutoStart(context, intent)

        Log.i(TAG, "ADB configuration applied successfully")
    }

    private suspend fun applyEdgeHost(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_EDGE_HOST) ?: return
        settingsRepository.updateConnectorEdgeHost(value.trim())
        Log.i(TAG, "Connector edge host updated")
    }

    private suspend fun applyGatewayUrl(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_GATEWAY_URL) ?: return
        settingsRepository.updateConnectorGatewayUrl(value.trim())
        Log.i(TAG, "Connector gateway URL updated")
    }

    private suspend fun applyEnrolmentCode(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_ENROLMENT_CODE) ?: return
        settingsRepository.updateConnectorEnrolmentCode(value.trim())
        Log.i(TAG, "Connector enrolment code updated")
    }

    private suspend fun applyConnectorAutoStart(
        context: Context,
        intent: Intent,
    ) {
        if (!intent.hasExtra(EXTRA_CONNECTOR_AUTO_START)) return
        val value = intent.getBooleanExtra(EXTRA_CONNECTOR_AUTO_START, false)
        settingsRepository.updateConnectorAutoStart(value)
        Log.i(TAG, "Connector auto-start updated to $value")
        // The flag is not merely recorded: a configure carrying connector_auto_start=true is the
        // supervisor's signal to bring the connector up now, so start it in the same broadcast.
        if (value) {
            Log.i(TAG, "connector_auto_start=true; starting the connector")
            handleConnector(context, PlatformConnectorService.ACTION_START)
        }
    }

    /**
     * Starts or stops the connector on the supervisor's behalf, and — because an adb start/stop is
     * an EXPLICIT lifecycle decision, not a hint — records it durably. Without the flag a
     * supervisor's stop would survive for at most fifteen minutes before the watchdog revived the
     * connector it had just been told to shut down.
     */
    private suspend fun handleConnector(
        context: Context,
        action: String,
    ) {
        Log.i(TAG, "Received ADB connector broadcast: $action")
        settingsRepository.updateConnectorStoppedByUser(action == PlatformConnectorService.ACTION_STOP)
        val serviceIntent =
            Intent(context, PlatformConnectorService::class.java).apply { this.action = action }
        context.startForegroundService(serviceIntent)
    }

    private suspend fun applyBindingAddress(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_BINDING_ADDRESS) ?: return
        val address =
            when (value) {
                BindingAddress.NETWORK.address -> {
                    BindingAddress.NETWORK
                }

                BindingAddress.LOCALHOST.address -> {
                    BindingAddress.LOCALHOST
                }

                else -> {
                    Log.w(
                        TAG,
                        "Ignoring unrecognized binding_address '$value' " +
                            "(valid: ${BindingAddress.LOCALHOST.address}, " +
                            "${BindingAddress.NETWORK.address})",
                    )
                    return
                }
            }
        settingsRepository.updateBindingAddress(address)
        Log.i(TAG, "Binding address updated to $address")
    }

    private suspend fun applyPort(intent: Intent) {
        if (!intent.hasExtra(EXTRA_PORT)) return
        val value = intent.getIntExtra(EXTRA_PORT, -1)
        settingsRepository.validatePort(value).fold(
            onSuccess = {
                settingsRepository.updatePort(it)
                Log.i(TAG, "Port updated to $it")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid port $value: ${it.message}") },
        )
    }

    private suspend fun applyAutoStartOnBoot(intent: Intent) {
        if (!intent.hasExtra(EXTRA_AUTO_START_ON_BOOT)) return
        val value = intent.getBooleanExtra(EXTRA_AUTO_START_ON_BOOT, false)
        settingsRepository.updateAutoStartOnBoot(value)
        Log.i(TAG, "Auto-start on boot updated to $value")
    }

    private suspend fun applyFileSizeLimit(intent: Intent) {
        if (!intent.hasExtra(EXTRA_FILE_SIZE_LIMIT_MB)) return
        val value = intent.getIntExtra(EXTRA_FILE_SIZE_LIMIT_MB, -1)
        settingsRepository.validateFileSizeLimit(value).fold(
            onSuccess = {
                settingsRepository.updateFileSizeLimit(it)
                Log.i(TAG, "File size limit updated to ${it}MB")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid file_size_limit_mb $value: ${it.message}") },
        )
    }

    private suspend fun applyAllowHttpDownloads(intent: Intent) {
        if (!intent.hasExtra(EXTRA_ALLOW_HTTP_DOWNLOADS)) return
        val value = intent.getBooleanExtra(EXTRA_ALLOW_HTTP_DOWNLOADS, false)
        settingsRepository.updateAllowHttpDownloads(value)
        Log.i(TAG, "Allow HTTP downloads updated to $value")
    }

    private suspend fun applyAllowUnverifiedHttpsCerts(intent: Intent) {
        if (!intent.hasExtra(EXTRA_ALLOW_UNVERIFIED_HTTPS_CERTS)) return
        val value = intent.getBooleanExtra(EXTRA_ALLOW_UNVERIFIED_HTTPS_CERTS, false)
        settingsRepository.updateAllowUnverifiedHttpsCerts(value)
        Log.i(TAG, "Allow unverified HTTPS certs updated to $value")
    }

    private suspend fun applyDownloadTimeout(intent: Intent) {
        if (!intent.hasExtra(EXTRA_DOWNLOAD_TIMEOUT_SECONDS)) return
        val value = intent.getIntExtra(EXTRA_DOWNLOAD_TIMEOUT_SECONDS, -1)
        settingsRepository.validateDownloadTimeout(value).fold(
            onSuccess = {
                settingsRepository.updateDownloadTimeout(it)
                Log.i(TAG, "Download timeout updated to ${it}s")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid download_timeout_seconds $value: ${it.message}") },
        )
    }

    private suspend fun applyDeviceSlug(intent: Intent) {
        if (!intent.hasExtra(EXTRA_DEVICE_SLUG)) return
        val value = intent.getStringExtra(EXTRA_DEVICE_SLUG) ?: ""
        settingsRepository.validateDeviceSlug(value).fold(
            onSuccess = {
                settingsRepository.updateDeviceSlug(it)
                Log.i(TAG, "Device slug updated to '$it'")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid device_slug '$value': ${it.message}") },
        )
    }

    private suspend fun applyToolPermissions(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_TOOL_PERMISSIONS) ?: return
        val config = ToolPermissionsConfig.fromJson(value)
        if (config == null) {
            Log.w(TAG, "Ignoring invalid tool_permissions JSON")
            return
        }
        settingsRepository.updateToolPermissionsConfig(config)
        Log.i(
            TAG,
            "Tool permissions updated: ${config.disabledTools.size} tools disabled, " +
                "${config.disabledParams.size} param overrides",
        )
    }

    private suspend fun applyStorageLocationPermissions(intent: Intent) {
        val locationId = intent.getStringExtra(EXTRA_STORAGE_LOCATION_ID) ?: return
        if (!storageLocationProvider.isLocationAuthorized(locationId)) {
            Log.w(TAG, "Ignoring storage permissions for unknown location")
            return
        }
        if (intent.hasExtra(EXTRA_STORAGE_ALLOW_WRITE)) {
            val allowWrite = intent.getBooleanExtra(EXTRA_STORAGE_ALLOW_WRITE, false)
            storageLocationProvider.updateLocationAllowWrite(locationId, allowWrite)
            Log.i(TAG, "Storage location allowWrite updated to $allowWrite")
        }
        if (intent.hasExtra(EXTRA_STORAGE_ALLOW_DELETE)) {
            val allowDelete = intent.getBooleanExtra(EXTRA_STORAGE_ALLOW_DELETE, false)
            storageLocationProvider.updateLocationAllowDelete(locationId, allowDelete)
            Log.i(TAG, "Storage location allowDelete updated to $allowDelete")
        }
    }

    private fun handleStartServer(context: Context) {
        Log.i(TAG, "Received ADB start server broadcast")
        val serviceIntent =
            Intent(context, McpServerService::class.java).apply {
                action = McpServerService.ACTION_START
            }
        context.startForegroundService(serviceIntent)
        Log.i(TAG, "McpServerService start command sent")
    }

    private fun handleStopServer(context: Context) {
        Log.i(TAG, "Received ADB stop server broadcast")
        val serviceIntent =
            Intent(context, McpServerService::class.java).apply {
                action = McpServerService.ACTION_STOP
            }
        context.startForegroundService(serviceIntent)
        Log.i(TAG, "McpServerService stop command sent")
    }

    companion object {
        private const val TAG = "MCP:AdbConfigHandler"

        internal const val EXTRA_BINDING_ADDRESS = "binding_address"
        internal const val EXTRA_PORT = "port"
        internal const val EXTRA_AUTO_START_ON_BOOT = "auto_start_on_boot"
        internal const val EXTRA_FILE_SIZE_LIMIT_MB = "file_size_limit_mb"
        internal const val EXTRA_ALLOW_HTTP_DOWNLOADS = "allow_http_downloads"
        internal const val EXTRA_ALLOW_UNVERIFIED_HTTPS_CERTS = "allow_unverified_https_certs"
        internal const val EXTRA_DOWNLOAD_TIMEOUT_SECONDS = "download_timeout_seconds"
        internal const val EXTRA_DEVICE_SLUG = "device_slug"
        internal const val EXTRA_TOOL_PERMISSIONS = "tool_permissions"
        internal const val EXTRA_STORAGE_LOCATION_ID = "storage_location_id"
        internal const val EXTRA_STORAGE_ALLOW_WRITE = "storage_allow_write"
        internal const val EXTRA_STORAGE_ALLOW_DELETE = "storage_allow_delete"
        internal const val EXTRA_EDGE_HOST = "edge_host"
        internal const val EXTRA_GATEWAY_URL = "gateway_url"
        internal const val EXTRA_ENROLMENT_CODE = "enrolment_code"
        internal const val EXTRA_CONNECTOR_AUTO_START = "connector_auto_start"
    }
}
