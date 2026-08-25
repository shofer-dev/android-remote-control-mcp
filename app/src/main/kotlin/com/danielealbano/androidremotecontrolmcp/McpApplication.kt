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
import com.danielealbano.androidremotecontrolmcp.services.permissions.PermissionAuditor
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

    @Inject
    lateinit var permissionAuditor: PermissionAuditor

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
        // Scheduling reaches WorkManager, which throws if its androidx.startup initializer did not
        // run. That is a self-heal nicety failing; it must never be able to take the whole process
        // down on every launch, which an unguarded call in onCreate would do.
        runCatching { connectorEnsure.scheduleWatchdog() }
            .onFailure { Log.e(TAG, "Could not schedule the connector watchdog", it) }
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
     *
     * The permissions audit rides the same transition, and its failure is ISOLATED from the
     * ensure. The audit reads `Settings.Secure`, `DevicePolicyManager` and `PowerManager` — all
     * vendor surfaces that can throw — and it is a REPORT, while the ensure is the thing that
     * makes the device work again. An unguarded report that can stop a repair is the same shape
     * as a swallowed dispatch: one component's failure silently disabling another.
     */
    private fun observeAppForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    applicationScope.launch {
                        connectorEnsure.ensure(ConnectorEnsure.REASON_FOREGROUND)
                    }
                    runCatching { permissionAuditor.refresh() }
                        .onFailure { Log.w(TAG, "Permissions audit failed on foreground", it) }
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
     * The connector's channel and the permissions channel are created eagerly; the standalone MCP
     * server's is created by that service when it actually starts ([ensureMcpServerChannel]) —
     * creating it here would list an "MCP Server" row in the OS notification settings of every
     * device that never runs standalone mode, which is a user-visible surface for a mode the
     * platform build does not use.
     *
     * The permissions audit gets a channel of its OWN rather than borrowing the connector's, so a
     * holder who mutes one keeps the other. Muting the connector's ongoing row and thereby losing
     * the "you are missing accessibility" nudge — or the reverse — would be a silent coupling
     * between two unrelated decisions.
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

        val permissionsChannel =
            NotificationChannel(
                PERMISSIONS_CHANNEL_ID,
                getString(R.string.notification_channel_permissions_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Permissions this device needs in order to be operated"
            }

        notificationManager.createNotificationChannel(connectorChannel)
        notificationManager.createNotificationChannel(permissionsChannel)
    }

    companion object {
        private const val TAG = "MCP:Application"
        const val MCP_SERVER_CHANNEL_ID = "mcp_server_channel"
        const val CONNECTOR_CHANNEL_ID = "connector_channel"
        const val PERMISSIONS_CHANNEL_ID = "permissions_channel"

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
