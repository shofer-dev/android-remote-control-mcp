package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinPermissions
import com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig
import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationFilterMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URL
import java.util.UUID
import javax.inject.Inject

/**
 * [SettingsRepository] implementation backed by Preferences DataStore.
 *
 * This is the single access point for all persisted settings in the
 * application. No other class should access DataStore directly.
 *
 * @property dataStore The Preferences DataStore instance provided by Hilt.
 */
@Suppress("TooManyFunctions")
class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : SettingsRepository {
        override val serverConfig: Flow<ServerConfig> =
            dataStore.data.map { prefs ->
                mapPreferencesToServerConfig(prefs)
            }

        override suspend fun getServerConfig(): ServerConfig = mapPreferencesToServerConfig(dataStore.data.first())

        override suspend fun updatePort(port: Int) {
            dataStore.edit { prefs ->
                prefs[PORT_KEY] = port
            }
        }

        override suspend fun updateBindingAddress(bindingAddress: BindingAddress) {
            dataStore.edit { prefs ->
                prefs[BINDING_ADDRESS_KEY] = bindingAddress.name
            }
        }

        override suspend fun updateAutoStartOnBoot(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[AUTO_START_KEY] = enabled
            }
        }

        override suspend fun updateFileSizeLimit(limitMb: Int) {
            dataStore.edit { prefs ->
                prefs[FILE_SIZE_LIMIT_KEY] = limitMb
            }
        }

        override fun validateFileSizeLimit(limitMb: Int): Result<Int> =
            if (limitMb in ServerConfig.MIN_FILE_SIZE_LIMIT_MB..ServerConfig.MAX_FILE_SIZE_LIMIT_MB) {
                Result.success(limitMb)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "File size limit must be between ${ServerConfig.MIN_FILE_SIZE_LIMIT_MB} and " +
                            "${ServerConfig.MAX_FILE_SIZE_LIMIT_MB} MB",
                    ),
                )
            }

        override suspend fun updateAllowHttpDownloads(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[ALLOW_HTTP_DOWNLOADS_KEY] = enabled
            }
        }

        override suspend fun updateAllowUnverifiedHttpsCerts(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[ALLOW_UNVERIFIED_HTTPS_KEY] = enabled
            }
        }

        override suspend fun updateDownloadTimeout(seconds: Int) {
            dataStore.edit { prefs ->
                prefs[DOWNLOAD_TIMEOUT_KEY] = seconds
            }
        }

        override suspend fun updateDeviceSlug(slug: String) {
            dataStore.edit { prefs ->
                prefs[DEVICE_SLUG_KEY] = slug
            }
        }

        override suspend fun updateToolPermissionsConfig(config: ToolPermissionsConfig) {
            dataStore.edit { prefs ->
                prefs[TOOL_PERMISSIONS_KEY] = config.toJson()
            }
        }

        override suspend fun updateToolEnabled(
            toolName: String,
            enabled: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = ToolPermissionsConfig.fromJsonOrDefault(prefs[TOOL_PERMISSIONS_KEY])
                val updated =
                    if (enabled) {
                        current.copy(disabledTools = current.disabledTools - toolName)
                    } else {
                        current.copy(disabledTools = current.disabledTools + toolName)
                    }
                prefs[TOOL_PERMISSIONS_KEY] = updated.toJson()
            }
        }

        override suspend fun updateParamEnabled(
            toolName: String,
            paramName: String,
            enabled: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = ToolPermissionsConfig.fromJsonOrDefault(prefs[TOOL_PERMISSIONS_KEY])
                val currentParams = current.disabledParams[toolName] ?: emptySet()
                val newParams = if (enabled) currentParams - paramName else currentParams + paramName
                val newDisabledParams =
                    if (newParams.isEmpty()) {
                        current.disabledParams - toolName
                    } else {
                        current.disabledParams + (toolName to newParams)
                    }
                prefs[TOOL_PERMISSIONS_KEY] = current.copy(disabledParams = newDisabledParams).toJson()
            }
        }

        override fun validateDownloadTimeout(seconds: Int): Result<Int> =
            if (seconds in ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS..ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS) {
                Result.success(seconds)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "Download timeout must be between ${ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS} and " +
                            "${ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS} seconds",
                    ),
                )
            }

        @Suppress("ReturnCount")
        override fun validateDeviceSlug(slug: String): Result<String> {
            if (slug.length > ServerConfig.MAX_DEVICE_SLUG_LENGTH) {
                return Result.failure(
                    IllegalArgumentException(
                        "Device slug must be at most ${ServerConfig.MAX_DEVICE_SLUG_LENGTH} characters",
                    ),
                )
            }
            if (!ServerConfig.DEVICE_SLUG_PATTERN.matches(slug)) {
                return Result.failure(
                    IllegalArgumentException(
                        "Device slug can only contain letters, digits, and underscores",
                    ),
                )
            }
            return Result.success(slug)
        }

        override suspend fun getStoredLocations(): List<SettingsRepository.StoredLocation> {
            val prefs = dataStore.data.first()
            val jsonString = prefs[AUTHORIZED_LOCATIONS_KEY] ?: return emptyList()
            return parseStoredLocationsJson(jsonString)
        }

        override suspend fun addStoredLocation(location: SettingsRepository.StoredLocation) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                existing.add(location)
                prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
            }
        }

        override suspend fun removeStoredLocation(locationId: String) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                existing.removeAll { it.id == locationId }
                prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
            }
        }

        override suspend fun updateLocationDescription(
            locationId: String,
            description: String,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    existing[index] = existing[index].copy(description = description)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                } else {
                    Log.w(TAG, "updateLocationDescription: location ${sanitizeLocationId(locationId)} not found, no-op")
                }
            }
        }

        override suspend fun updateLocationAllowWrite(
            locationId: String,
            allowWrite: Boolean,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    existing[index] = existing[index].copy(allowWrite = allowWrite)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                } else {
                    Log.w(TAG, "updateLocationAllowWrite: location ${sanitizeLocationId(locationId)} not found, no-op")
                }
            }
        }

        override suspend fun updateLocationAllowDelete(
            locationId: String,
            allowDelete: Boolean,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    existing[index] = existing[index].copy(allowDelete = allowDelete)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                } else {
                    Log.w(TAG, "updateLocationAllowDelete: location ${sanitizeLocationId(locationId)} not found, no-op")
                }
            }
        }

        override suspend fun getBuiltinLocationPermissions(): Map<String, BuiltinPermissions> {
            val prefs = dataStore.data.first()
            val json = prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] ?: return emptyMap()
            return parseBuiltinPermissionsJson(json)
        }

        override suspend fun updateBuiltinLocationAllowWrite(
            locationId: String,
            allowWrite: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = getBuiltinLocationPermissionsInternal(prefs)
                val existing = current[locationId] ?: BuiltinPermissions()
                val updated = current + (locationId to existing.copy(allowWrite = allowWrite))
                prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] = serializeBuiltinPermissions(updated)
            }
        }

        override suspend fun updateBuiltinLocationAllowDelete(
            locationId: String,
            allowDelete: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = getBuiltinLocationPermissionsInternal(prefs)
                val existing = current[locationId] ?: BuiltinPermissions()
                val updated = current + (locationId to existing.copy(allowDelete = allowDelete))
                prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] = serializeBuiltinPermissions(updated)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun parseBuiltinPermissionsJson(json: String): Map<String, BuiltinPermissions> =
            try {
                val root = Json.parseToJsonElement(json).jsonObject
                root.entries.associate { (key, value) ->
                    val obj = value.jsonObject
                    key to
                        BuiltinPermissions(
                            allowWrite = obj["allowWrite"]?.jsonPrimitive?.booleanOrNull ?: false,
                            allowDelete = obj["allowDelete"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse builtin location permissions JSON", e)
                emptyMap()
            }

        private fun getBuiltinLocationPermissionsInternal(prefs: Preferences): Map<String, BuiltinPermissions> {
            val json = prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] ?: return emptyMap()
            return parseBuiltinPermissionsJson(json)
        }

        private fun serializeBuiltinPermissions(perms: Map<String, BuiltinPermissions>): String =
            Json.encodeToString(
                buildJsonObject {
                    for ((key, value) in perms) {
                        put(
                            key,
                            buildJsonObject {
                                put("allowWrite", value.allowWrite)
                                put("allowDelete", value.allowDelete)
                            },
                        )
                    }
                },
            )

        override fun validatePort(port: Int): Result<Int> =
            if (port in ServerConfig.MIN_PORT..ServerConfig.MAX_PORT) {
                Result.success(port)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "Port must be between ${ServerConfig.MIN_PORT} and ${ServerConfig.MAX_PORT}",
                    ),
                )
            }

        /**
         * Maps raw [Preferences] to a [ServerConfig] instance, applying defaults
         * for any missing keys.
         */
        private fun mapPreferencesToServerConfig(prefs: Preferences): ServerConfig {
            val bindingAddressName = prefs[BINDING_ADDRESS_KEY] ?: BindingAddress.LOCALHOST.name

            return ServerConfig(
                port = prefs[PORT_KEY] ?: ServerConfig.DEFAULT_PORT,
                bindingAddress =
                    BindingAddress.entries.firstOrNull { it.name == bindingAddressName }
                        ?: BindingAddress.LOCALHOST,
                autoStartOnBoot = prefs[AUTO_START_KEY] ?: false,
                fileSizeLimitMb = prefs[FILE_SIZE_LIMIT_KEY] ?: ServerConfig.DEFAULT_FILE_SIZE_LIMIT_MB,
                allowHttpDownloads = prefs[ALLOW_HTTP_DOWNLOADS_KEY] ?: false,
                allowUnverifiedHttpsCerts = prefs[ALLOW_UNVERIFIED_HTTPS_KEY] ?: false,
                downloadTimeoutSeconds =
                    prefs[DOWNLOAD_TIMEOUT_KEY]
                        ?: ServerConfig.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
                deviceSlug = prefs[DEVICE_SLUG_KEY] ?: "",
                toolPermissionsConfig = ToolPermissionsConfig.fromJsonOrDefault(prefs[TOOL_PERMISSIONS_KEY]),
            )
        }

        /**
         * Generates a random UUID string, used for the event-channel auth token.
         */
        private fun generateTokenString(): String = UUID.randomUUID().toString()

        @Suppress("SwallowedException", "TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
        private fun parseStoredLocationsJson(json: String?): List<SettingsRepository.StoredLocation> {
            if (json == null) return emptyList()
            return try {
                val jsonArray = Json.parseToJsonElement(json).jsonArray
                jsonArray.mapNotNull { element ->
                    try {
                        val obj = element.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val treeUri = obj["treeUri"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val description = obj["description"]?.jsonPrimitive?.content ?: ""
                        val allowWriteElement = obj["allowWrite"]
                        // Missing allowWrite in persisted JSON means this is pre-permission-fields
                        // data; default to true for backwards compatibility (old locations were
                        // implicitly full-access).
                        val allowWrite =
                            if (allowWriteElement == null || allowWriteElement is JsonNull) {
                                true
                            } else {
                                allowWriteElement.jsonPrimitive.booleanOrNull ?: false
                            }
                        val allowDeleteElement = obj["allowDelete"]
                        // Missing allowDelete in persisted JSON means this is pre-permission-fields
                        // data; default to true for backwards compatibility (old locations were
                        // implicitly full-access).
                        val allowDelete =
                            if (allowDeleteElement == null || allowDeleteElement is JsonNull) {
                                true
                            } else {
                                allowDeleteElement.jsonPrimitive.booleanOrNull ?: false
                            }
                        SettingsRepository.StoredLocation(
                            id = id,
                            name = name,
                            path = path,
                            description = description,
                            treeUri = treeUri,
                            allowWrite = allowWrite,
                            allowDelete = allowDelete,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping malformed stored location entry", e)
                        null
                    }
                }
            } catch (_: Exception) {
                // Migration: try parsing old format (JSON object: {"locationId": "treeUri"}).
                // Old-format locations pre-date permission fields and were implicitly
                // full-access, so allowWrite and allowDelete default to true.
                try {
                    val jsonObject = Json.parseToJsonElement(json).jsonObject
                    jsonObject.map { (key, value) ->
                        SettingsRepository.StoredLocation(
                            id = key,
                            name = key.substringAfterLast("/"),
                            path = "/",
                            description = "",
                            treeUri = value.jsonPrimitive.content,
                            allowWrite = true,
                            allowDelete = true,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse stored locations JSON, returning empty list", e)
                    emptyList()
                }
            }
        }

        private fun serializeStoredLocationsJson(locations: List<SettingsRepository.StoredLocation>): String =
            Json.encodeToString(
                buildJsonArray {
                    for (loc in locations) {
                        add(
                            buildJsonObject {
                                put("id", loc.id)
                                put("name", loc.name)
                                put("path", loc.path)
                                put("description", loc.description)
                                put("treeUri", loc.treeUri)
                                put("allowWrite", loc.allowWrite)
                                put("allowDelete", loc.allowDelete)
                            },
                        )
                    }
                },
            )

        // --- Event Channel ---

        override val eventChannelConfig: Flow<EventChannelConfig> =
            dataStore.data.map { prefs ->
                val json = prefs[EVENT_CHANNEL_CONFIG_KEY] ?: return@map EventChannelConfig()
                EventChannelConfig.fromJsonOrDefault(json)
            }

        override suspend fun getEventChannelConfig(): EventChannelConfig = eventChannelConfig.first()

        private suspend fun updateEventChannelConfig(transform: (EventChannelConfig) -> EventChannelConfig) {
            val current = getEventChannelConfig()
            val updated = transform(current)
            dataStore.edit { prefs ->
                prefs[EVENT_CHANNEL_CONFIG_KEY] = updated.toJson()
            }
        }

        override suspend fun updateEventChannelEnabled(enabled: Boolean) {
            updateEventChannelConfig { it.copy(enabled = enabled) }
        }

        override suspend fun updateEventChannelEndpointUrl(url: String) {
            updateEventChannelConfig { it.copy(endpointUrl = url) }
        }

        override suspend fun updateEventChannelAuthToken(token: String) {
            updateEventChannelConfig { it.copy(authToken = token) }
        }

        override suspend fun generateNewEventChannelAuthToken(): String {
            val token = generateTokenString()
            updateEventChannelAuthToken(token)
            return token
        }

        override fun validateEndpointUrl(url: String): Result<String> {
            if (url.isBlank()) {
                return Result.failure(IllegalArgumentException("Endpoint URL cannot be empty"))
            }
            return try {
                val parsed = URL(url)
                if (parsed.protocol != "http" && parsed.protocol != "https") {
                    Result.failure(IllegalArgumentException("URL must use http or https protocol"))
                } else {
                    Result.success(url)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Result.failure(IllegalArgumentException("Invalid URL format: ${e.message}"))
            }
        }

        override suspend fun updateNotificationChannelEnabled(enabled: Boolean) =
            updateEventChannelConfig { it.copy(notifications = it.notifications.copy(enabled = enabled)) }

        override suspend fun updateNotificationFilterMode(mode: NotificationFilterMode) =
            updateEventChannelConfig { it.copy(notifications = it.notifications.copy(filterMode = mode)) }

        override suspend fun updateNotificationFilterApps(apps: Set<String>) =
            updateEventChannelConfig { it.copy(notifications = it.notifications.copy(filterApps = apps)) }

        override suspend fun updateWifiChannelEnabled(enabled: Boolean) =
            updateEventChannelConfig { it.copy(wifi = it.wifi.copy(enabled = enabled)) }

        override suspend fun updateWifiSsids(ssids: Set<String>) {
            updateEventChannelConfig { it.copy(wifi = it.wifi.copy(ssids = ssids)) }
        }

        override suspend fun updateWifiNotifyOnDiscovered(enabled: Boolean) =
            updateEventChannelConfig { it.copy(wifi = it.wifi.copy(notifyOnDiscovered = enabled)) }

        override suspend fun updateWifiNotifyOnLost(enabled: Boolean) =
            updateEventChannelConfig { it.copy(wifi = it.wifi.copy(notifyOnLost = enabled)) }

        override suspend fun updateWifiNotifyOnConnected(enabled: Boolean) =
            updateEventChannelConfig { it.copy(wifi = it.wifi.copy(notifyOnConnected = enabled)) }

        override suspend fun updateWifiNotifyOnDisconnected(enabled: Boolean) =
            updateEventChannelConfig { it.copy(wifi = it.wifi.copy(notifyOnDisconnected = enabled)) }

        // --- Platform Connector ---

        override val connectorConfig: Flow<ConnectorConfig> =
            dataStore.data.map { prefs ->
                ConnectorConfig.fromJsonOrDefault(prefs[CONNECTOR_CONFIG_KEY])
            }

        override suspend fun getConnectorConfig(): ConnectorConfig = connectorConfig.first()

        private suspend fun updateConnectorConfig(transform: (ConnectorConfig) -> ConnectorConfig) {
            val current = getConnectorConfig()
            val updated = transform(current)
            dataStore.edit { prefs ->
                prefs[CONNECTOR_CONFIG_KEY] = updated.toJson()
            }
        }

        override suspend fun updateConnectorEdgeHost(edgeHost: String) {
            updateConnectorConfig { it.copy(edgeHost = edgeHost) }
        }

        override suspend fun updateConnectorEnrolmentCode(code: String) {
            updateConnectorConfig { it.copy(enrolmentCode = code) }
        }

        override suspend fun updateConnectorEnrolled(deviceId: String) {
            // The pairing code is single-use and spent by a successful enrolment — clear it so a
            // reconnect attaches with the durable device id rather than re-redeeming (which the
            // platform would refuse as code-unusable).
            updateConnectorConfig { it.copy(deviceId = deviceId, enrolmentCode = "") }
        }

        override suspend fun updateConnectorAutoStart(enabled: Boolean) {
            updateConnectorConfig { it.copy(autoStart = enabled) }
        }

        companion object {
            private const val TAG = "MCP:SettingsRepo"
            private const val MAX_LOCATION_ID_LOG_LENGTH = 200
            private val CONTROL_CHAR_REGEX = Regex("[\\p{Cntrl}]")

            private fun sanitizeLocationId(locationId: String): String =
                locationId.take(MAX_LOCATION_ID_LOG_LENGTH).replace(CONTROL_CHAR_REGEX, "")

            private val PORT_KEY = intPreferencesKey("port")
            private val BINDING_ADDRESS_KEY = stringPreferencesKey("binding_address")
            private val AUTO_START_KEY = booleanPreferencesKey("auto_start_on_boot")
            private val FILE_SIZE_LIMIT_KEY = intPreferencesKey("file_size_limit_mb")
            private val ALLOW_HTTP_DOWNLOADS_KEY = booleanPreferencesKey("allow_http_downloads")
            private val ALLOW_UNVERIFIED_HTTPS_KEY = booleanPreferencesKey("allow_unverified_https_certs")
            private val DOWNLOAD_TIMEOUT_KEY = intPreferencesKey("download_timeout_seconds")
            private val DEVICE_SLUG_KEY = stringPreferencesKey("device_slug")
            private val TOOL_PERMISSIONS_KEY = stringPreferencesKey("tool_permissions")
            private val AUTHORIZED_LOCATIONS_KEY = stringPreferencesKey("authorized_storage_locations")
            private val BUILTIN_LOCATION_PERMISSIONS_KEY = stringPreferencesKey("builtin_location_permissions")
            private val EVENT_CHANNEL_CONFIG_KEY = stringPreferencesKey("event_channel_config")
            private val CONNECTOR_CONFIG_KEY = stringPreferencesKey("connector_config")
        }
    }
