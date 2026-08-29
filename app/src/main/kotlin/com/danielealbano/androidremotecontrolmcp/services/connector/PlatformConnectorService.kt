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
import com.danielealbano.androidremotecontrolmcp.services.screenstream.ScreenStreamController
import com.danielealbano.androidremotecontrolmcp.ui.ConnectorTermsActivity
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import com.danielealbano.androidremotecontrolmcp.utils.MonotonicClock
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
 * `/ws/phone` connection alive. Mirrors [com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService]'s
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

    @Inject lateinit var provisioning: ConnectorProvisioning

    @Inject lateinit var serverFactory: McpToolServerFactory

    @Inject lateinit var policyEnforcer: PolicyEnforcer

    @Inject lateinit var activityIndicator: RemoteActivityIndicator

    @Inject lateinit var screenStream: ScreenStreamController

    @Inject lateinit var clock: MonotonicClock

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var connector: PlatformConnector? = null

    /** The configured gateway host, for the notification text. Written from the config collector. */
    @Volatile private var dialHost: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // The CURRENT status, not a hardcoded "Connecting…": a redundant start (an ensure path
        // that raced the service's own liveness read) would otherwise re-label an attached
        // connector as connecting until its next status emission.
        startForeground(NOTIFICATION_ID, buildNotification(_status.value))

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                serviceRunning.set(true)
                ConnectorEnsure.cancelReviveNotification(this)
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
                provisioning = provisioning,
                serverFactory = serverFactory,
                policyEnforcer = policyEnforcer,
                activityIndicator = activityIndicator,
                screenStream = screenStream,
                clock = clock,
            )
        connector = platformConnector

        serviceScope.launch {
            platformConnector.status.collect { status ->
                _status.value = status
                updateNotification(status, activityIndicator.driving.value)
            }
        }
        // The notification names the host the device is attached to, so a holder can tell WHICH
        // platform holds it. That is configuration rather than status, so it is tracked here
        // instead of riding on every ConnectorStatus value.
        serviceScope.launch {
            settingsRepository.connectorConfig.collect { config ->
                dialHost = config.dialHost
                updateNotification(_status.value, activityIndicator.driving.value)
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
        serviceRunning.set(false)
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
                .setContentText(statusText(status))
                .build()
        }
    }

    /**
     * The notification's status line. The settled states a holder actually acts on get a full
     * sentence — attached (and to whom), attached-but-paused, lost, and stopped-with-a-reason —
     * and everything else falls back to the state's own short label, which is already the right
     * length for a transient handshake step.
     */
    private fun statusText(status: ConnectorStatus): String =
        when (status) {
            is ConnectorStatus.Connected -> {
                if (dialHost.isBlank()) {
                    getString(R.string.notification_connector_connected)
                } else {
                    getString(R.string.notification_connector_connected_host, dialHost)
                }
            }

            is ConnectorStatus.Paused -> {
                getString(R.string.notification_connector_paused)
            }

            is ConnectorStatus.Reconnecting -> {
                getString(R.string.notification_connector_reconnecting)
            }

            is ConnectorStatus.Halted -> {
                getString(R.string.notification_connector_halted, status.reason)
            }

            else -> {
                status.notificationLabel
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

        private val serviceRunning = AtomicBoolean(false)

        /**
         * Whether this service is currently alive, for the ensure paths ([ConnectorEnsure]).
         *
         * It is a process-scoped fact, and that is exactly right for the question being asked: if
         * the process was killed too, this reads false on the next start, which is the case the
         * revive paths exist for. It is deliberately NOT derived from [status] — a status is what
         * the connector believes about the LINK, and a halted-but-running connector must not be
         * "restarted" by a path that only meant to check whether the service exists.
         */
        val isRunning: Boolean get() = serviceRunning.get()
    }
}
