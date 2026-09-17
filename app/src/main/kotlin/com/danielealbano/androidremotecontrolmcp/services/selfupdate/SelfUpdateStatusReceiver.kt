package com.danielealbano.androidremotecontrolmcp.services.selfupdate

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Where the OS reports a self-install session's outcome — the `IntentSender` every
 * [PackageInstallerApkInstaller] session is committed with.
 *
 * Three answers matter, and only one of them is ordinary:
 *
 * - **`STATUS_SUCCESS`** is the one that usually never arrives, because the install has already
 *   replaced this process. Its absence is normal; the platform learns the update landed by the
 *   device re-attaching with a new `app_version`.
 * - **`STATUS_PENDING_USER_ACTION`** is the interesting one. The OS staged the install and wants a
 *   human, and it hands back an Intent to show them — in `Intent.EXTRA_INTENT`, an Intent extra
 *   rather than one of `PackageInstaller`'s own, which is the easiest thing in this API to
 *   misspell.
 * - anything else is a failure, reported into [SelfUpdater]'s state so the connector card can say
 *   what happened instead of sitting on "Installing" forever.
 *
 * ── The confirmation has to REACH the holder, and a background start silently does not ─────────
 * Starting an activity from the background is dropped by Android with no exception and no return
 * value — the only trace is a "Background activity launch blocked!" line in logcat — and this
 * receiver runs, on the ordinary path, for a phone in a rack with no window on screen. So the
 * routing is explicit: start the confirmation directly only when this app actually has a visible
 * window, and otherwise post a notification whose tap DOES carry the launch, because a
 * system-sent PendingIntent is one of the documented exemptions. It is the same rule, and the same
 * two-way shape, as the screen-capture consent path.
 */
@AndroidEntryPoint
class SelfUpdateStatusReceiver : BroadcastReceiver() {
    @Inject lateinit var selfUpdater: SelfUpdater

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, NO_STATUS)) {
            NO_STATUS -> {
                // The fill-in Intent did not land, which on this API means exactly one thing: the
                // PendingIntent behind the IntentSender was not FLAG_MUTABLE.
                Log.e(TAG, "An install status arrived with no EXTRA_STATUS; the status PendingIntent is immutable")
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                onPendingUserAction(context, intent)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "The self-update install succeeded")
                selfUpdater.onInstallSucceeded()
            }

            else -> {
                val details =
                    InstallFailure.describe(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                Log.e(TAG, "The self-update install failed: $details")
                selfUpdater.onInstallFailed(details)
            }
        }
    }

    /** Records the wait and puts the OS's confirmation where the holder will actually meet it. */
    private fun onPendingUserAction(
        context: Context,
        intent: Intent,
    ) {
        selfUpdater.onInstallPending()
        val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        if (confirm == null) {
            Log.e(TAG, "The OS asked for user action but carried no confirmation Intent")
            return
        }
        if (appHasVisibleWindow()) {
            startConfirmation(context, confirm)
        } else {
            notifyConfirmation(context, confirm)
        }
    }

    /**
     * Whether this app has a window on screen right now — the ONE condition under which a direct
     * activity start is permitted here.
     *
     * `ProcessLifecycleOwner` is the same signal `McpApplication`'s foreground hook is built on, so
     * "the app is visible" means the same thing in both places.
     */
    private fun appHasVisibleWindow(): Boolean =
        ProcessLifecycleOwner
            .get()
            .lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)

    /**
     * Starts the confirmation from the receiver's context, which is not an Activity — hence
     * [Intent.FLAG_ACTIVITY_NEW_TASK], which is mandatory from a non-activity context.
     *
     * A start that throws falls back to the notification rather than being swallowed: the holder
     * losing the dialog is the one failure that leaves an update staged forever with nothing on
     * screen to say so.
     */
    private fun startConfirmation(
        context: Context,
        confirm: Intent,
    ) {
        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { Log.i(TAG, "Showed the install confirmation directly") }
            .onFailure {
                Log.w(TAG, "Could not show the install confirmation directly; notifying instead", it)
                notifyConfirmation(context, confirm)
            }
    }

    /**
     * Asks the holder to finish the update.
     *
     * The tap is the mechanism, not the courtesy: a `PendingIntent` fired from a notification is
     * SENT BY THE SYSTEM, which is what makes the activity start legal from a process with no
     * visible window. `FLAG_IMMUTABLE` because nothing fills anything into this one.
     *
     * It rides the connector's existing channel, whose importance is LOW — so `PRIORITY_HIGH` sets
     * the pre-channel ranking only and the row is quiet on API 26+, exactly like the connector's
     * own "being driven" notification. Quiet is right here: the update is already staged and
     * nothing is broken while it waits.
     */
    private fun notifyConfirmation(
        context: Context,
        confirm: Intent,
    ) {
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                CONFIRM_NOTIFICATION_ID,
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, McpApplication.CONNECTOR_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_update_confirm_title))
                .setContentText(context.getString(R.string.notification_update_confirm_text))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        context.getString(R.string.notification_update_confirm_text),
                    ),
                ).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        context.getSystemService(NotificationManager::class.java)?.notify(CONFIRM_NOTIFICATION_ID, notification)
        Log.i(TAG, "Asked the holder to finish the update")
    }

    companion object {
        private const val TAG = "MCP:SelfUpdateStatus"

        /**
         * Distinct from the connector's ongoing row (1002) and its tap-to-reconnect row (1003),
         * which this must never replace.
         */
        private const val CONFIRM_NOTIFICATION_ID = 1004

        /** No sane status code, so it cannot collide with one the OS actually sends. */
        private const val NO_STATUS = Int.MIN_VALUE
    }
}
