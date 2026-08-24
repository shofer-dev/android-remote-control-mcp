package com.danielealbano.androidremotecontrolmcp.services.connector

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the platform connector back up whenever the durable configuration says it should be
 * running and it is not — the single implementation behind all three self-heal paths.
 *
 * WHY THIS EXISTS: `START_STICKY` is a request, not a guarantee. OEM Android builds (HyperOS, One
 * UI, EMUI) kill a foreground service and suppress the sticky restart, and `BOOT_COMPLETED` never
 * arrives at all without the vendor's autostart permission. The observed failure was a phone whose
 * connector had been dead for hours with the app showing "Stopped" and no way to act on it. So
 * three independent paths now ask the same question:
 *
 * 1. **Foreground** — [McpApplication] observes `ProcessLifecycleOwner` and calls [ensure] when
 *    the app becomes visible. Opening the app is the one action a holder always knows how to take.
 * 2. **Watchdog** — [ConnectorWatchdogWorker], every 15 minutes, calls the same [ensure].
 * 3. **Explicit** — [start] and [stop] behind the connector card's control, which additionally
 *    move the [com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig.stoppedByUser]
 *    veto that the other two respect.
 *
 * THE BACKGROUND-START WALL: since Android 12 an app in the background may not call
 * `startForegroundService` at all, and the exemption list (developer.android.com →
 * "Restrictions on starting foreground services from the background") contains neither
 * WorkManager nor JobScheduler. It DOES contain "the user turned off battery optimisations for
 * the app" and "the user acted on a notification". So path 2 works outright on a device the
 * holder has exempted, and on one they have not it fails with
 * [ForegroundServiceStartNotAllowedException] — which is caught, and answered with a
 * tap-to-reconnect notification whose action carries the same start intent. Tapping it IS the
 * user action the exemption asks for. The device therefore never sits silently dead: it either
 * heals itself or asks, once, to be healed.
 */
@Singleton
class ConnectorEnsure
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
    ) {
        /**
         * Starts the connector if — and only if — the durable configuration says it should be
         * running and it is not already. Idempotent and safe to call from any of the revive
         * paths; [reason] appears in the log so a support session can tell which one fired.
         */
        suspend fun ensure(reason: String): Outcome {
            val config = settingsRepository.getConnectorConfig()
            val running = PlatformConnectorService.isRunning
            if (!ConnectorAutoStart.shouldStart(config, running)) {
                val outcome = if (running) Outcome.ALREADY_RUNNING else Outcome.NOT_WANTED
                Log.i(TAG, "Connector ensure ($reason): $outcome")
                return outcome
            }
            return sendStart(reason)
        }

        /**
         * The holder's explicit Start. Clears the stop veto first — otherwise the very next
         * [ensure] would undo the start it is about to perform — then starts the connector and
         * makes sure the watchdog is scheduled.
         */
        suspend fun start(): Outcome {
            settingsRepository.updateConnectorStoppedByUser(false)
            scheduleWatchdog()
            if (PlatformConnectorService.isRunning) {
                Log.i(TAG, "Connector ensure (user-start): ${Outcome.ALREADY_RUNNING}")
                return Outcome.ALREADY_RUNNING
            }
            return sendStart("user-start")
        }

        /**
         * The holder's explicit Stop. Records the veto BEFORE stopping, so a watchdog tick that
         * lands in the gap between the two reads a configuration that already says "stay down".
         */
        suspend fun stop() {
            settingsRepository.updateConnectorStoppedByUser(true)
            cancelReviveNotification(context)
            context.startForegroundService(connectorIntent(context, PlatformConnectorService.ACTION_STOP))
            Log.i(TAG, "Connector stopped by the holder")
        }

        /**
         * Schedules the watchdog as unique periodic work. [ExistingPeriodicWorkPolicy.KEEP] makes
         * this idempotent, which is what lets every entry point (app start, an explicit start)
         * call it without coordinating: WorkManager's schedule is durable across process death and
         * reboot, so the first call is the one that matters and the rest are no-ops.
         */
        fun scheduleWatchdog() {
            val request =
                PeriodicWorkRequestBuilder<ConnectorWatchdogWorker>(
                    WATCHDOG_INTERVAL_MINUTES,
                    TimeUnit.MINUTES,
                ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                ConnectorWatchdogWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        private fun sendStart(reason: String): Outcome =
            try {
                context.startForegroundService(connectorIntent(context, PlatformConnectorService.ACTION_START))
                cancelReviveNotification(context)
                Log.i(TAG, "Connector ensure ($reason): ${Outcome.STARTED}")
                Outcome.STARTED
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // Not a bug and not recoverable in-process: the app is in the background on a
                // device that has not exempted it. Ask the holder for the one tap that is itself
                // an exemption.
                Log.w(TAG, "Connector ensure ($reason): background start refused", e)
                postReviveNotification(context)
                Outcome.BLOCKED
            }

        /** What an [ensure] attempt did, for logging and for the caller's own reporting. */
        enum class Outcome {
            /** The configuration says the connector should not run (or a stop veto is set). */
            NOT_WANTED,

            /** The connector service is already up. */
            ALREADY_RUNNING,

            /** A start intent was sent. */
            STARTED,

            /** The OS refused a background foreground-service start; the holder has been asked. */
            BLOCKED,
        }

        companion object {
            private const val TAG = "MCP:ConnectorEnsure"

            /** The reason string the app-came-to-the-foreground path logs. */
            const val REASON_FOREGROUND = "app-foreground"

            /**
             * WorkManager's floor for periodic work. Anything smaller is silently clamped to it,
             * so it is stated rather than chosen.
             */
            const val WATCHDOG_INTERVAL_MINUTES = 15L

            /** Distinct from the connector's own ongoing notification, which it must not replace. */
            private const val REVIVE_NOTIFICATION_ID = 1003

            private fun connectorIntent(
                context: Context,
                action: String,
            ): Intent =
                Intent(context, PlatformConnectorService::class.java).apply {
                    this.action = action
                }

            /**
             * The tap-to-reconnect notification. Its action fires a
             * [PendingIntent.getForegroundService] carrying the ordinary start intent: a user tap
             * on a notification is one of the documented exemptions from the background
             * foreground-service-start restriction, so the start the worker could not make
             * succeeds from here.
             */
            private fun postReviveNotification(context: Context) {
                val pendingIntent =
                    PendingIntent.getForegroundService(
                        context,
                        REVIVE_NOTIFICATION_ID,
                        connectorIntent(context, PlatformConnectorService.ACTION_START),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                val notification =
                    NotificationCompat
                        .Builder(context, McpApplication.CONNECTOR_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(context.getString(R.string.notification_connector_revive_title))
                        .setContentText(context.getString(R.string.notification_connector_revive_text))
                        .setStyle(
                            NotificationCompat.BigTextStyle().bigText(
                                context.getString(R.string.notification_connector_revive_text),
                            ),
                        ).setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .build()
                context
                    .getSystemService(NotificationManager::class.java)
                    ?.notify(REVIVE_NOTIFICATION_ID, notification)
            }

            /**
             * Removes the tap-to-reconnect notification. Called from every path that makes it
             * untrue — a successful start here, and the service's own `onStartCommand`, which
             * also catches the case where the holder started it some other way.
             */
            fun cancelReviveNotification(context: Context) {
                context.getSystemService(NotificationManager::class.java)?.cancel(REVIVE_NOTIFICATION_ID)
            }
        }
    }
