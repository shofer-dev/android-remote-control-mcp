package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinPermissions
import com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig
import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationFilterMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import kotlinx.coroutines.flow.Flow

/**
 * Repository for accessing and persisting MCP server settings.
 *
 * This is the single access point for all application settings.
 * All DataStore access MUST go through this interface. UI, ViewModels,
 * and Services must not access DataStore directly.
 */
@Suppress("TooManyFunctions")
interface SettingsRepository {
    /**
     * Observes the current server configuration. Emits a new [ServerConfig]
     * whenever any setting changes.
     */
    val serverConfig: Flow<ServerConfig>

    /**
     * Returns the current server configuration as a one-shot read.
     */
    suspend fun getServerConfig(): ServerConfig

    /**
     * Updates the server port.
     *
     * @param port The new port value. Must pass [validatePort] first.
     */
    suspend fun updatePort(port: Int)

    /** Updates the network binding address. */
    suspend fun updateBindingAddress(bindingAddress: BindingAddress)

    /** Updates the auto-start-on-boot preference. */
    suspend fun updateAutoStartOnBoot(enabled: Boolean)

    /**
     * Validates a port number.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated port, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validatePort(port: Int): Result<Int>

    /** Updates the file size limit for file operations (in MB). */
    suspend fun updateFileSizeLimit(limitMb: Int)

    /**
     * Validates a file size limit value.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated limit, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateFileSizeLimit(limitMb: Int): Result<Int>

    /** Updates whether HTTP (non-HTTPS) downloads are allowed. */
    suspend fun updateAllowHttpDownloads(enabled: Boolean)

    /** Updates whether unverified HTTPS certificates are accepted for downloads. */
    suspend fun updateAllowUnverifiedHttpsCerts(enabled: Boolean)

    /** Updates the download timeout in seconds. */
    suspend fun updateDownloadTimeout(seconds: Int)

    /**
     * Updates the device slug used for tool name prefix.
     *
     * @param slug The new device slug. Must pass [validateDeviceSlug] first.
     */
    suspend fun updateDeviceSlug(slug: String)

    /**
     * Validates a download timeout value.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated timeout, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateDownloadTimeout(seconds: Int): Result<Int>

    /**
     * Validates a device slug string.
     *
     * Valid slugs contain only letters (a-z, A-Z), digits (0-9), and underscores.
     * Maximum length is [ServerConfig.MAX_DEVICE_SLUG_LENGTH] characters. Empty is valid.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated slug, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateDeviceSlug(slug: String): Result<String>

    /** Updates the full tool permissions configuration. */
    suspend fun updateToolPermissionsConfig(config: ToolPermissionsConfig)

    /** Enables or disables a specific tool. */
    suspend fun updateToolEnabled(
        toolName: String,
        enabled: Boolean,
    )

    /** Enables or disables a specific parameter for a tool. */
    suspend fun updateParamEnabled(
        toolName: String,
        paramName: String,
        enabled: Boolean,
    )

    /**
     * Data class representing a stored storage location record.
     * This is the persistence format; the full [StorageLocation] includes
     * dynamic fields like [StorageLocation.availableBytes].
     *
     * @property id Unique identifier: "{authority}/{documentId}".
     * @property name Display name of the directory.
     * @property path Human-readable path within the provider.
     * @property description User-provided description.
     * @property treeUri The granted persistent tree URI string.
     * @property allowWrite Whether write operations are allowed for this location.
     * @property allowDelete Whether delete operations are allowed for this location.
     */
    data class StoredLocation(
        val id: String,
        val name: String,
        val path: String,
        val description: String,
        val treeUri: String,
        val allowWrite: Boolean = false,
        val allowDelete: Boolean = false,
    )

    /**
     * Returns all stored storage locations.
     */
    suspend fun getStoredLocations(): List<StoredLocation>

    /**
     * Adds a storage location.
     */
    suspend fun addStoredLocation(location: StoredLocation)

    /**
     * Removes a storage location by ID.
     */
    suspend fun removeStoredLocation(locationId: String)

    /**
     * Updates the description of an existing storage location.
     *
     * @param locationId The storage location identifier.
     * @param description The new description.
     */
    suspend fun updateLocationDescription(
        locationId: String,
        description: String,
    )

    /**
     * Updates whether write operations are allowed for a storage location.
     *
     * @param locationId The storage location identifier.
     * @param allowWrite Whether write operations are allowed.
     */
    suspend fun updateLocationAllowWrite(
        locationId: String,
        allowWrite: Boolean,
    )

    /**
     * Updates whether delete operations are allowed for a storage location.
     *
     * @param locationId The storage location identifier.
     * @param allowDelete Whether delete operations are allowed.
     */
    suspend fun updateLocationAllowDelete(
        locationId: String,
        allowDelete: Boolean,
    )

    /** Returns permission overrides for all built-in locations. */
    suspend fun getBuiltinLocationPermissions(): Map<String, BuiltinPermissions>

    /** Updates the allowWrite flag for a built-in location. */
    suspend fun updateBuiltinLocationAllowWrite(
        locationId: String,
        allowWrite: Boolean,
    )

    /** Updates the allowDelete flag for a built-in location. */
    suspend fun updateBuiltinLocationAllowDelete(
        locationId: String,
        allowDelete: Boolean,
    )

    // --- Event Channel ---

    /** Observes the current event channel configuration. */
    val eventChannelConfig: Flow<EventChannelConfig>

    /** Returns the current event channel configuration as a one-shot read. */
    suspend fun getEventChannelConfig(): EventChannelConfig

    /** Updates the event channel enabled toggle. */
    suspend fun updateEventChannelEnabled(enabled: Boolean)

    /** Updates the event channel endpoint URL. */
    suspend fun updateEventChannelEndpointUrl(url: String)

    /** Updates the event channel auth token. */
    suspend fun updateEventChannelAuthToken(token: String)

    /** Generates a new random event channel auth token (UUID), persists it, and returns it. */
    suspend fun generateNewEventChannelAuthToken(): String

    /**
     * Validates an endpoint URL.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated URL, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateEndpointUrl(url: String): Result<String>

    /** Updates the notification channel enabled toggle. */
    suspend fun updateNotificationChannelEnabled(enabled: Boolean)

    /** Updates the notification filter mode. */
    suspend fun updateNotificationFilterMode(mode: NotificationFilterMode)

    /** Updates the set of app package names for notification filtering. */
    suspend fun updateNotificationFilterApps(apps: Set<String>)

    /** Updates the WiFi channel enabled toggle. */
    suspend fun updateWifiChannelEnabled(enabled: Boolean)

    /** Updates the set of WiFi SSIDs to monitor. */
    suspend fun updateWifiSsids(ssids: Set<String>)

    /** Updates the WiFi notify on discovered toggle. */
    suspend fun updateWifiNotifyOnDiscovered(enabled: Boolean)

    /** Updates the WiFi notify on lost toggle. */
    suspend fun updateWifiNotifyOnLost(enabled: Boolean)

    /** Updates the WiFi notify on connected toggle. */
    suspend fun updateWifiNotifyOnConnected(enabled: Boolean)

    /** Updates the WiFi notify on disconnected toggle. */
    suspend fun updateWifiNotifyOnDisconnected(enabled: Boolean)

    // --- Platform Connector ---

    /** Observes the current platform-connector configuration. */
    val connectorConfig: Flow<ConnectorConfig>

    /** Returns the current platform-connector configuration as a one-shot read. */
    suspend fun getConnectorConfig(): ConnectorConfig

    /** Updates the device-edge host (e.g. `devices.justceo.ai`). */
    suspend fun updateConnectorEdgeHost(edgeHost: String)

    /**
     * Updates the full gateway URL override — a `ws://…/ws/device` or `wss://…/ws/device` URL used
     * verbatim in preference to the [updateConnectorEdgeHost] fallback. Blank means "no override".
     */
    suspend fun updateConnectorGatewayUrl(gatewayUrl: String)

    /** Updates the one-time enrolment (pairing) code. */
    suspend fun updateConnectorEnrolmentCode(code: String)

    /** Persists the device id returned by `enrolled` and clears the spent enrolment code. */
    suspend fun updateConnectorEnrolled(deviceId: String)

    /** Updates the connector auto-start toggle. */
    suspend fun updateConnectorAutoStart(enabled: Boolean)
}
