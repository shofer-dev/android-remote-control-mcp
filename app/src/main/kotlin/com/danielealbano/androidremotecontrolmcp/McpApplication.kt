package com.danielealbano.androidremotecontrolmcp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.apps.AppIconCache
import com.danielealbano.androidremotecontrolmcp.startup.runFlavorStartupMigrations
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration
import javax.inject.Inject

@HiltAndroidApp
class McpApplication : Application() {
    @Inject
    lateinit var appIconCache: AppIconCache

    override fun onCreate() {
        super.onCreate()
        // Flavor-specific one-time migrations (gms: geofence config → dedicated key), launched eagerly
        // on a background coroutine (non-blocking — must never stall onCreate). No-op in foss.
        runFlavorStartupMigrations(this)
        createNotificationChannels()
        configureOsmdroid()
        appIconCache.preload()
        Log.i(TAG, "Application initialized, notification channels created")
    }

    private fun configureOsmdroid() {
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = packageName
        osmConfig.osmdroidBasePath = filesDir
        osmConfig.osmdroidTileCache = cacheDir.resolve("osmdroid")
    }

    /**
     * Only the connector's channel is created eagerly. The standalone MCP server's channel is
     * created by that service when it actually starts ([ensureMcpServerChannel]) — creating it
     * here would list an "MCP Server" row in the OS notification settings of every device that
     * never runs standalone mode, which is a user-visible surface for a mode the platform build
     * does not use.
     */
    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val connectorChannel =
            NotificationChannel(
                CONNECTOR_CHANNEL_ID,
                getString(R.string.notification_channel_connector_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Notification for the platform connector"
            }

        notificationManager.createNotificationChannel(connectorChannel)
    }

    companion object {
        private const val TAG = "MCP:Application"
        const val MCP_SERVER_CHANNEL_ID = "mcp_server_channel"
        const val CONNECTOR_CHANNEL_ID = "connector_channel"

        /**
         * Creates the standalone MCP server's notification channel. Idempotent — `create` on an
         * existing channel only updates its name — so the service may call it on every start.
         */
        fun ensureMcpServerChannel(context: Context) {
            val channel =
                NotificationChannel(
                    MCP_SERVER_CHANNEL_ID,
                    context.getString(R.string.notification_channel_mcp_server_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Notification for the running MCP server"
                }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
