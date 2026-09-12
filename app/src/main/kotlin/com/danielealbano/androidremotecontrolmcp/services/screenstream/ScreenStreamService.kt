package com.danielealbano.androidremotecontrolmcp.services.screenstream

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The `mediaProjection` foreground service that holds the phone's screen-capture grant.
 *
 * ── The ordering is the API, not a convention ──────────────────────────────────────────────────
 *
 * Android 14 enforces a precise sequence, and getting it wrong throws rather than degrading:
 *
 * 1. `createScreenCaptureIntent()` is launched and the user grants it — BEFORE this service starts.
 *    The platform's own documentation states the prerequisite in that order, and it is why the
 *    consent lives in [ScreenStreamConsentActivity] and the projection is taken here.
 * 2. `startForeground()` with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`, which needs the matching
 *    `foregroundServiceType` and the `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission in the
 *    manifest; without them the OS throws.
 * 3. only then `getMediaProjection()` and `createVirtualDisplay()`
 *    ([MediaProjectionHolder.arm]). `MediaProjection.start` throws `SecurityException` when no
 *    foreground service of this type is running.
 *
 * The service then STAYS up for the whole armed lifetime, because it is not merely a wrapper: the
 * OS watches it, and stops the projection the moment no foreground service of this type is running.
 *
 * It is a second foreground service beside the connector's, deliberately. The connector's is
 * `specialUse`, and a service may not be both — and folding capture into it would tie the phone's
 * screen grant to the lifetime of its control connection, so every reconnect would cost a consent.
 */
@AndroidEntryPoint
class ScreenStreamService : Service() {
    @Inject lateinit var holder: MediaProjectionHolder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_ARM -> arm(intent)
            else -> disarm()
        }
        return START_NOT_STICKY
    }

    /**
     * Goes foreground and takes the projection. A refusal stops the service rather than leaving a
     * notification claiming a capture that is not running.
     */
    private fun arm(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) {
            Log.w(TAG, "Arm request carried no consent result; nothing to take")
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        if (!holder.arm(resultCode, data)) {
            Log.w(TAG, "The consent could not be turned into a projection; standing down")
            stopSelf()
        }
    }

    private fun disarm() {
        holder.disarm()
        stopSelf()
    }

    override fun onDestroy() {
        // Releasing here as well as on the explicit stop is what keeps `armed` honest when the OS
        // kills the service: the flag must never claim a capture the process no longer holds.
        holder.disarm()
        super.onDestroy()
    }

    /**
     * The ongoing notification. It says the screen can be viewed remotely rather than that
     * something is running: on this platform a holder is always able to tell their device is
     * drivable, and the armed state is exactly that fact.
     */
    private fun notification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, McpApplication.CONNECTOR_CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_stream_notification_title))
            .setContentText(getString(R.string.screen_stream_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "ScreenStreamService"
        private const val NOTIFICATION_ID = 1004

        private const val ACTION_ARM = "com.danielealbano.androidremotecontrolmcp.ACTION_ARM_SCREEN_STREAM"
        private const val ACTION_DISARM = "com.danielealbano.androidremotecontrolmcp.ACTION_DISARM_SCREEN_STREAM"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** The intent [ScreenStreamConsentActivity] sends once the user has granted capture. */
        fun armIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
        ): Intent =
            Intent(context, ScreenStreamService::class.java).apply {
                action = ACTION_ARM
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }

        /** Releases the grant; the next stream needs a fresh consent. */
        fun disarmIntent(context: Context): Intent {
            val intent = Intent(context, ScreenStreamService::class.java)
            intent.action = ACTION_DISARM
            return intent
        }
    }
}
