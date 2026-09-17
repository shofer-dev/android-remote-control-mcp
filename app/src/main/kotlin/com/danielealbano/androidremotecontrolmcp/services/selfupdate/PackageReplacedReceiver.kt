package com.danielealbano.androidremotecontrolmcp.services.selfupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorEnsure
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Brings the platform connector back after an update has replaced this app.
 *
 * An install kills the process, and nothing puts it back: `START_STICKY` is a request the OEM
 * builds routinely decline, and the app is not in the foreground. Without this receiver a phone
 * would apply an update and then go quiet until a holder opened the app or the fifteen-minute
 * watchdog happened to fire — which, for a handset in a rack, is a device that looks like it was
 * bricked by the update that fixed it.
 *
 * `ACTION_MY_PACKAGE_REPLACED` is "only sent to the application that was replaced" and is a
 * protected system broadcast, so an intent filter is the whole registration and no permission gate
 * is needed.
 *
 * ── It does NOT decide for itself whether to start ─────────────────────────────────────────────
 * The condition is [ConnectorEnsure], exactly as it is for
 * [com.danielealbano.androidremotecontrolmcp.services.mcp.BootCompletedReceiver] and the watchdog,
 * so an update and a reboot can never disagree about whether this device wants a connector — and
 * so a holder who deliberately STOPPED it does not get it restarted by shipping them a new build.
 *
 * Going through [ConnectorEnsure] rather than calling `startForegroundService` directly also
 * settles the background-start question without betting on it: this broadcast IS on the documented
 * exemption list beside `ACTION_BOOT_COMPLETED`, but `ConnectorEnsure` already catches a refusal
 * and answers it with a tap-to-reconnect notification, so the device asks to be healed in the case
 * where the exemption does not hold on some vendor build.
 */
@AndroidEntryPoint
class PackageReplacedReceiver : BroadcastReceiver() {
    @Inject lateinit var connectorEnsure: ConnectorEnsure

    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        Log.i(TAG, "This app was replaced by a new version")

        // goAsync for the same reason the boot receiver uses it: the decision reads the durable
        // configuration out of DataStore, which a receiver's synchronous window cannot wait for.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(ENSURE_TIMEOUT_MS) {
                    val outcome = connectorEnsure.ensure(REASON_PACKAGE_REPLACED)
                    Log.i(TAG, "Connector ensure after replacement: $outcome")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not ensure the connector after replacement", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MCP:PackageReplaced"
        const val REASON_PACKAGE_REPLACED = "package-replaced"
        const val ENSURE_TIMEOUT_MS = 10_000L
    }
}
