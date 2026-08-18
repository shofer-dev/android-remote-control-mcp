package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider

/**
 * Handles ADB configuration broadcast intents by parsing extras and
 * applying them to [SettingsRepository].
 *
 * Extracted from [AdbConfigReceiver] to allow unit testing without
 * Hilt's [dagger.hilt.android.AndroidEntryPoint] injection lifecycle.
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
            AdbConfigReceiver.ACTION_CONFIGURE -> handleConfigure(intent)
            AdbConfigReceiver.ACTION_START_SERVER -> handleStartServer(context)
            AdbConfigReceiver.ACTION_STOP_SERVER -> handleStopServer(context)
            else -> Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
        }
    }

    private suspend fun handleConfigure(intent: Intent) {
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

        Log.i(TAG, "ADB configuration applied successfully")
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
    }
}
