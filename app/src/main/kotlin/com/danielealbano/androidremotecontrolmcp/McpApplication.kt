package com.danielealbano.androidremotecontrolmcp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.danielealbano.androidremotecontrolmcp.services.apps.AppIconCache
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorEnsure
import com.danielealbano.androidremotecontrolmcp.startup.runFlavorStartupMigrations
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import javax.inject.Inject

@HiltAndroidApp
class McpApplication : Application() {
    @Inject
    lateinit var appIconCache: AppIconCache

    @Inject
    lateinit var connectorEnsure: ConnectorEnsure

    /**
     * Application-scoped and deliberately never cancelled: it outlives every activity, which is
     * the point — the foreground hook must survive the activity that triggered it going away.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Flavor-specific one-time migrations (gms: geofence config → dedicated key), launched eagerly
        // on a background coroutine (non-blocking — must never stall onCreate). No-op in foss.
        runFlavorStartupMigrations(this)
        createNotificationChannels()
        configureOsmdroid()
        appIconCache.preload()
        observeAppForeground()
        connectorEnsure.scheduleWatchdog()
        Log.i(TAG, "Application initialized, notification channels created")
    }

    /**
     * Ensures the platform connector is running whenever the APP becomes visible.
     *
     * `ProcessLifecycleOwner` is what makes this "the app came to the foreground" rather than "an
     * activity resumed": it fires once per foreground session, not on every rotation or every hop
     * between tabs, so opening the app performs exactly one ensure.
     *
     * This is the path that answers the product gap the OEM kills exposed — a holder who opens the
     * app to see why the device is offline is, by that act, bringing it back. A foreground app is
     * also exempt from the Android 12+ background foreground-service-start restriction, so unlike
     * the watchdog this start cannot be refused.
     */
    private fun observeAppForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    applicationScope.launch {
                        connectorEnsure.ensure(ConnectorEnsure.REASON_FOREGROUND)
                    }
                }
            },
        )
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
