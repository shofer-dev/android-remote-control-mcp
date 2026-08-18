package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Holds the MCP server configuration.
 *
 * All fields have sensible defaults matching the project specification.
 *
 * @property port The server port (1-65535).
 * @property bindingAddress The network binding address.
 * @property autoStartOnBoot Whether to start the MCP server on device boot.
 * @property fileSizeLimitMb File size limit for file operations (in MB).
 * @property allowHttpDownloads Whether HTTP (non-HTTPS) downloads are allowed.
 * @property allowUnverifiedHttpsCerts Whether unverified HTTPS certs are accepted for downloads.
 * @property downloadTimeoutSeconds Download timeout in seconds.
 * @property deviceSlug Optional device identifier slug for tool name prefix
 *   (letters, digits, underscores; max 20 chars).
 */
data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val bindingAddress: BindingAddress = BindingAddress.LOCALHOST,
    val autoStartOnBoot: Boolean = false,
    val fileSizeLimitMb: Int = DEFAULT_FILE_SIZE_LIMIT_MB,
    val allowHttpDownloads: Boolean = false,
    val allowUnverifiedHttpsCerts: Boolean = false,
    val downloadTimeoutSeconds: Int = DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
    val deviceSlug: String = "",
    val toolPermissionsConfig: ToolPermissionsConfig = ToolPermissionsConfig(),
) {
    companion object {
        /** Default server port. */
        const val DEFAULT_PORT = 8080

        /** Minimum valid port number. */
        const val MIN_PORT = 1

        /** Maximum valid port number. */
        const val MAX_PORT = 65535

        /** Default file size limit in megabytes. */
        const val DEFAULT_FILE_SIZE_LIMIT_MB = 50

        /** Minimum file size limit in megabytes. */
        const val MIN_FILE_SIZE_LIMIT_MB = 1

        /** Maximum file size limit in megabytes. */
        const val MAX_FILE_SIZE_LIMIT_MB = 500

        /** Default download timeout in seconds. */
        const val DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 60

        /** Minimum download timeout in seconds. */
        const val MIN_DOWNLOAD_TIMEOUT_SECONDS = 10

        /** Maximum download timeout in seconds. */
        const val MAX_DOWNLOAD_TIMEOUT_SECONDS = 300

        /** Maximum length for device slug. */
        const val MAX_DEVICE_SLUG_LENGTH = 20

        /** Pattern for valid device slug characters (letters, digits, underscores). */
        val DEVICE_SLUG_PATTERN = Regex("^[a-zA-Z0-9_]*$")
    }
}
