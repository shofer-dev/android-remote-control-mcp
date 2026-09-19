// Registration and the intent parse are DEFENSIVE: a ROM that refuses the receiver, or hands us a
// malformed PDU set, must cost the connector nothing — the category goes quiet, which is the same
// outcome as a handset with no SIM. Same posture and same suppression as `ScreenLockMonitor`.
@file:Suppress("TooGenericExceptionCaught")

package com.danielealbano.androidremotecontrolmcp.services.connector.events

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The `sms` category: a text message arrived.
 *
 * ## Registered at runtime, never declared in the manifest
 *
 * A manifest `<receiver>` for `SMS_RECEIVED` would make this app a candidate SMS handler, which it
 * is not: it does not read the holder's inbox, does not send, and must not appear in the OS's
 * "default SMS app" chooser. Registering at runtime, for the life of one connector socket, keeps
 * the capability scoped to "while this device is attached and reporting" — the same discipline the
 * keyguard watch follows.
 *
 * ## Multipart is ONE event
 *
 * A long message arrives as several PDUs in one broadcast. They are the halves of a sentence, so
 * their bodies are concatenated in order and reported once — a subscriber must never have to
 * reassemble a message the OS already handed us whole.
 *
 * ## The grant
 *
 * `RECEIVE_SMS` is a runtime permission and an OPTIONAL one for this app: a phone whose holder
 * declined it operates completely, reports every other category, and is not broken. So the absence
 * costs one log line and silence, never a retry and never a finding that reads as a fault.
 */
class SmsEventSource(
    private val appContext: Context,
) : DeviceEventSource {
    override val category: DeviceEventCategory = DeviceEventCategory.SMS

    override fun events(): Flow<DeviceEvent> {
        if (!isGranted(appContext)) {
            Log.i(TAG, "RECEIVE_SMS is not granted; this device reports no SMS events")
            return emptyFlow()
        }
        return callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        messageFrom(intent)?.let { trySend(it) }
                    }
                }
            try {
                appContext.registerReceiver(receiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION))
                Log.i(TAG, "Reporting SMS events")
            } catch (e: Exception) {
                Log.w(TAG, "Could not watch for SMS; this device reports no SMS events", e)
            }
            awaitClose {
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    Log.w(TAG, "SMS receiver was already gone", e)
                }
            }
        }
    }

    /**
     * One broadcast as one event, or null when it carried no readable message.
     *
     * The sender and the timestamp are taken from the FIRST part: every part of a multipart message
     * carries the same originating address, and the first part's service-centre timestamp is when
     * the message was sent rather than when the last fragment happened to land.
     */
    private fun messageFrom(intent: Intent?): DeviceEvent? {
        val parts = readMessages(intent)
        val first = parts.firstOrNull() ?: return null
        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
        return DeviceEvent(
            category = category,
            occurredAtMillis = System.currentTimeMillis(),
            payload =
                EventPayloads.sms(
                    from = first.originatingAddress.orEmpty(),
                    body = body,
                    receivedAtMillis = first.timestampMillis,
                ),
        )
    }

    /**
     * The message parts a broadcast carried, or empty when it carried none this build can read.
     *
     * An unreadable broadcast is a dropped event, never a crash: this runs on the main thread
     * inside a system broadcast, where a throw takes the app's process down with it.
     */
    private fun readMessages(intent: Intent?): List<SmsMessage> {
        if (intent == null) return emptyList()
        return try {
            val parts: Array<SmsMessage>? = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            parts?.toList().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable SMS broadcast", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "MCP:SmsEvents"

        /**
         * Whether this device may receive SMS at all.
         *
         * It lives HERE, beside the only code that acts on it, rather than in `PermissionUtils`
         * with the standalone MCP surface's grants — so the permissions audit and the settings
         * screen ask the same question this source asks, and the three can never disagree about
         * whether the `sms` category is live (the rule `PermissionAuditor.snapshot` states: every
         * check is the one the code that actually fails already trusts).
         */
        fun isGranted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECEIVE_SMS,
            ) == PackageManager.PERMISSION_GRANTED
    }
}
