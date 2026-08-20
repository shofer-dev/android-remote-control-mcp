@file:Suppress("MaxLineLength")

package com.danielealbano.androidremotecontrolmcp.services.connector

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.crypto.DeviceIdentity
import com.danielealbano.androidremotecontrolmcp.services.connector.indicator.RemoteActivityIndicator
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.PolicyEnforcer
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpToolServerFactory
import com.danielealbano.androidremotecontrolmcp.ui.ConnectorTermsActivity
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Foreground service that hosts the [PlatformConnector] so Android keeps the outbound
 * `/ws/device` connection alive. Mirrors [com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService]'s
 * shape (specialUse FGS, companion status flow) and [com.danielealbano.androidremotecontrolmcp.services.channel.EventChannelService]'s
 * config-driven lifecycle.
 *
 * The service owns the connector's coroutine scope; the connector runs until the scope is
 * cancelled in [onDestroy]. Its [ConnectorStatus] is mirrored to a companion [StateFlow] for
 * the UI and used to keep the ongoing notification's text current.
 */
@AndroidEntryPoint
class PlatformConnectorService : Service() {
    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var deviceIdentity: DeviceIdentity

    @Inject lateinit var actionHandler: DeviceActionHandler

    @Inject lateinit var termsBroker: TermsConsentBroker

    @Inject lateinit var serverFactory: McpToolServerFactory

    @Inject lateinit var policyEnforcer: PolicyEnforcer

    @Inject lateinit var activityIndicator: RemoteActivityIndicator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var connector: PlatformConnector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, buildNotification(ConnectorStatus.Connecting))

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startConnector()
            }
        }
        return START_STICKY
    }

    private fun startConnector() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "Connector already running; ignoring duplicate start")
            return
        }
        val platformConnector =
            PlatformConnector(
                appContext = applicationContext,
                scope = serviceScope,
                settingsRepository = settingsRepository,
                deviceIdentity = deviceIdentity,
                actionHandler = actionHandler,
                termsBroker = termsBroker,
                serverFactory = serverFactory,
                policyEnforcer = policyEnforcer,
                activityIndicator = activityIndicator,
            )
        connector = platformConnector

        serviceScope.launch {
            platformConnector.status.collect { status ->
                _status.value = status
                updateNotification(status, activityIndicator.driving.value)
            }
        }
        // The activity indicator has its own notification text: a holder glancing at the
        // shade must be able to tell "connected" from "being driven right now" without
        // opening anything (§6.4 — the signal is unmissable and not suppressible).
        serviceScope.launch {
            activityIndicator.driving.collect { driving ->
                updateNotification(_status.value, driving)
            }
        }
        // The linger is expired by a ticker rather than by a per-command timer, so the rule
        // lives in one testable place (RemoteActivityIndicator.tick).
        serviceScope.launch {
            while (isActive) {
                delay(RemoteActivityIndicator.TICK_INTERVAL_MILLIS)
                activityIndicator.tick()
            }
        }
        // Surface the terms UI when the handshake needs consent. This background activity start
        // works while the app is in the foreground (the testing path); Wave 3 hardens it with a
        // full-screen-intent notification so it also fires from a cold background.
        serviceScope.launch {
            termsBroker.pending.collect { pending ->
                if (pending != null) {
                    val intent =
                        Intent(this@PlatformConnectorService, ConnectorTermsActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(intent) }
                        .onFailure { Log.w(TAG, "Could not launch terms activity", it) }
                }
            }
        }
        serviceScope.launch { platformConnector.run() }
    }

    override fun onDestroy() {
        Log.i(TAG, "PlatformConnectorService destroying")
        running.set(false)
        // Tear the transparency signals down BEFORE the scope dies, or the screen border
        // outlives the service that could remove it.
        activityIndicator.reset()
        policyEnforcer.clear()
        serviceScope.cancel()
        _status.value = ConnectorStatus.Stopped
        super.onDestroy()
    }

    private fun updateNotification(
        status: ConnectorStatus,
        driving: Boolean,
    ) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(status, driving))
    }

    /**
     * The ongoing notification. While a session is DRIVING it changes title, text and colour
     * and is raised to `PRIORITY_HIGH`, so the holder sees a distinct row rather than a
     * status line that happens to read differently. Nothing the platform sends can turn this
     * off — the policy snapshot carries no field for it and there is no action frame for it
     * (§6.4: transparency is a signal, not a control).
     */
    private fun buildNotification(
        status: ConnectorStatus,
        driving: Boolean = false,
    ): Notification {
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            NotificationCompat
                .Builder(this, McpApplication.CONNECTOR_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
        return if (driving) {
            builder
                .setContentTitle(getString(R.string.notification_connector_driving_title))
                .setContentText(getString(R.string.notification_connector_driving_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColorized(true)
                .setColor(DRIVING_NOTIFICATION_COLOR)
                .build()
        } else {
            builder
                .setContentTitle(getString(R.string.notification_connector_title))
                .setContentText(status.notificationLabel)
                .build()
        }
    }

    companion object {
        private const val TAG = "MCP:ConnectorService"
        const val ACTION_START = "com.danielealbano.androidremotecontrolmcp.ACTION_START_CONNECTOR"
        const val ACTION_STOP = "com.danielealbano.androidremotecontrolmcp.ACTION_STOP_CONNECTOR"
        const val NOTIFICATION_ID = 1002

        /** The same amber-red the screen border uses, so the two signals read as one thing. */
        private const val DRIVING_NOTIFICATION_COLOR = 0xFFD32F2F.toInt()

        private val _status = MutableStateFlow<ConnectorStatus>(ConnectorStatus.Stopped)

        /** Connector status for the UI; survives rebinding like [com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService.serverStatus]. */
        val status: StateFlow<ConnectorStatus> = _status.asStateFlow()
    }
}
