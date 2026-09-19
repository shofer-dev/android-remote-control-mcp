// The receiver registration is DEFENSIVE: a ROM that refuses it must cost the connector nothing —
// the category goes quiet. Same posture and same suppression as `ScreenLockMonitor`.
@file:Suppress("TooGenericExceptionCaught")

package com.danielealbano.androidremotecontrolmcp.services.connector.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The `battery` category: charge crossed a decade, or the handset went on or off power.
 *
 * ## Registered at runtime, and it needs no permission at all
 *
 * `ACTION_BATTERY_CHANGED` is a STICKY protected broadcast that cannot be received by a manifest
 * receiver — the OS refuses to deliver it to one — so runtime registration is not a choice here,
 * it is the only mechanism. Being sticky also means registration delivers the current reading at
 * once, which is how the plane gets a baseline with no poll and no timer.
 *
 * ## Why the change gate is not optional
 *
 * This broadcast fires whenever ANY battery field moves — temperature, voltage, a percentage point
 * — which on a charging phone is several times a minute, indefinitely. A source that forwarded each
 * one would be exactly the failure `docs/phone/device_events.md` §3 forbids: it would spend the
 * gateway's per-device token bucket on noise, so the events that mattered would be the ones shed.
 * [BatteryChange] is what makes an idle phone silent, and it is tested rather than trusted.
 */
class BatteryEventSource(
    private val appContext: Context,
) : DeviceEventSource {
    override val category: DeviceEventCategory = DeviceEventCategory.BATTERY

    override fun events(): Flow<DeviceEvent> =
        callbackFlow {
            var previous: BatteryState? = null
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        val next = stateOf(intent) ?: return
                        if (!BatteryChange.isReportable(previous, next)) return
                        previous = next
                        trySend(
                            DeviceEvent(
                                category = category,
                                occurredAtMillis = System.currentTimeMillis(),
                                payload = EventPayloads.battery(next),
                            ),
                        )
                    }
                }
            try {
                appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                Log.i(TAG, "Reporting battery events")
            } catch (e: Exception) {
                Log.w(TAG, "Could not watch the battery; this device reports no battery events", e)
            }
            awaitClose {
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    Log.w(TAG, "Battery receiver was already gone", e)
                }
            }
        }

    private companion object {
        const val TAG = "MCP:BatteryEvents"
        const val UNSET = -1

        /**
         * The reading a broadcast carried, or null when it carried no usable one.
         *
         * Reading the three extras is all this does; what those numbers MEAN — the fuel gauge's
         * scale, whether FULL counts as charging — is [BatteryChange]'s, where it is tested.
         */
        fun stateOf(intent: Intent?): BatteryState? {
            if (intent == null) return null
            return BatteryChange
                .percentage(
                    level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, UNSET),
                    scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, UNSET),
                )?.let { level ->
                    BatteryState(
                        level = level,
                        charging = BatteryChange.isOnPower(intent.getIntExtra(BatteryManager.EXTRA_STATUS, UNSET)),
                    )
                }
        }
    }
}
