// Every OS read here is DEFENSIVE: a vendor ROM that refuses the telephony service, or throws from
// the callback registration, must cost the connector nothing — the category simply reports nothing,
// which is the same outcome as a handset with no radio. Catching narrowly would mean predicting
// which OEM throws what. Same posture and same suppression as `ScreenLockMonitor`.
@file:Suppress("TooGenericExceptionCaught")

package com.danielealbano.androidremotecontrolmcp.services.connector.events

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceCallState
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The `call` category: this handset started ringing, answered, or hung up.
 *
 * ## `TelephonyCallback`, with no `PhoneStateListener` fallback
 *
 * [TelephonyCallback] arrived in API 31 and `PhoneStateListener` was deprecated in the same
 * release. This app's `minSdk` is 31, so there is no build that can run this code and lack the new
 * API — the fallback every online example still carries would be dead code here, and dead code in
 * a permission-sensitive path is worse than none.
 *
 * ## The number is EMPTY, on purpose
 *
 * [TelephonyCallback.CallStateListener] is gated on `READ_PHONE_STATE`, which this app already
 * holds for SIM enumeration — and on modern Android that permission yields the STATE only. The
 * incoming number needs `READ_CALL_LOG`, and this app deliberately does not ask for it:
 *
 * - It is a privacy ask out of all proportion to one payload field. `READ_CALL_LOG` discloses the
 *   handset's entire call HISTORY — every number the holder has ever dialled or answered — to get
 *   the caller id of the call currently ringing.
 * - Google Play treats the call-log group as a RESTRICTED permission requiring a declared, approved
 *   default-handler use case. This app is not a dialer and has none.
 *
 * So `number` is reported as `""`, which a subscriber reads as "not disclosed". A `ringing` event
 * with no number is still the thing the plane exists for: it is what wakes the agent.
 */
class CallEventSource(
    private val appContext: Context,
) : DeviceEventSource {
    override val category: DeviceEventCategory = DeviceEventCategory.CALL

    override fun events(): Flow<DeviceEvent> {
        val telephony = watchableTelephony() ?: return emptyFlow()
        return callbackFlow {
            var previous: String? = null
            val callback =
                object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        val reported = stateName(state)
                        if (!CallChange.isReportable(previous, reported)) return
                        previous = reported
                        trySend(
                            DeviceEvent(
                                category = category,
                                occurredAtMillis = System.currentTimeMillis(),
                                payload = EventPayloads.call(reported, ""),
                            ),
                        )
                    }
                }
            try {
                telephony.registerTelephonyCallback(appContext.mainExecutor, callback)
                Log.i(TAG, "Reporting call events")
            } catch (e: Exception) {
                // A registration that fails leaves the flow silent for the life of this socket.
                // The connector stays correct: a category that reports nothing is the documented
                // outcome of a grant or a radio it does not have.
                Log.w(TAG, "Could not watch the call state; this device reports no call events", e)
            }
            awaitClose {
                try {
                    telephony.unregisterTelephonyCallback(callback)
                } catch (e: Exception) {
                    Log.w(TAG, "Call-state callback was already gone", e)
                }
            }
        }
    }

    /**
     * The telephony service, when this device can actually be watched — otherwise null, with one
     * log line saying which of the two reasons applied.
     *
     * A missing grant and a handset with no radio are the same outcome to the plane (the category
     * reports nothing) and must be distinguishable in the log, which is the whole of what this
     * does.
     */
    private fun watchableTelephony(): TelephonyManager? {
        val granted =
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_PHONE_STATE,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "READ_PHONE_STATE is not granted; this device reports no call events")
            return null
        }
        return appContext.getSystemService(TelephonyManager::class.java).also {
            if (it == null) Log.i(TAG, "No telephony service on this device; it reports no call events")
        }
    }

    private companion object {
        const val TAG = "MCP:CallEvents"

        /**
         * The three framework states, mapped onto the host leg's vocabulary. Anything else — a
         * value a future release adds — is read as [DeviceCallState.DISCONNECTED], the state that
         * claims the least.
         */
        fun stateName(state: Int): String =
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> DeviceCallState.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> DeviceCallState.ACTIVE
                else -> DeviceCallState.DISCONNECTED
            }
    }
}

/**
 * The call category's emission rule, pure because getting it wrong costs an agent TURN.
 *
 * `call` is the one category declared `wake: true` in the integration manifest
 * (`docs/phone/device_events.md` §5): a delivery to a sleeping agent synthesizes a turn. Two things
 * follow, and both are here rather than in the callback so they can be tested without a radio.
 */
object CallChange {
    /**
     * Whether moving from [previous] to [next] is worth an event.
     *
     * - **A repeated state is not.** `CallStateListener` re-states the current state on
     *   registration and can re-deliver it, and a second `disconnected` means nothing happened.
     * - **A FIRST state of `disconnected` is not.** Registration fires immediately with whatever
     *   the phone is doing, so an idle handset would otherwise report "the call ended" on every
     *   attach — waking the agent once per reconnect, about a call that never existed. A first
     *   state of `ringing` or `active` IS reported: a phone that is mid-call as the socket comes up
     *   is exactly what the platform wants to hear about.
     */
    fun isReportable(
        previous: String?,
        next: String,
    ): Boolean = next != previous && !(previous == null && next == DeviceCallState.DISCONNECTED)
}
