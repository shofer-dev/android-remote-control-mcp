// The callback registration is DEFENSIVE: a ROM that refuses it, or a device with no connectivity
// service at all, must cost the connector nothing — the category goes quiet. Same posture and same
// suppression as `ScreenLockMonitor`.
@file:Suppress("TooGenericExceptionCaught")

package com.danielealbano.androidremotecontrolmcp.services.connector.events

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectivityTransport
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The `connectivity` category: this handset's DEFAULT network changed.
 *
 * ## Not `services/channel/listeners/WifiEventListener`
 *
 * That listener belongs to the channel plugin and asks a different question — which of a
 * holder-configured list of SSIDs is in range, discovered or lost — which is scan-driven, throttled
 * to half-hourly in the background, and says nothing about whether the device can reach anything.
 * This source asks the one question the platform cares about: what carries this phone's traffic
 * right now, and does it work.
 *
 * ## `online` is VALIDATED, not "associated"
 *
 * [NetworkCapabilities.NET_CAPABILITY_VALIDATED] is the OS's own verdict that traffic actually
 * reached the internet over this network. An associated Wi-Fi behind an unaccepted captive portal
 * is `wifi` + `online: false`, which is precisely the state an operator needs to see — a phone that
 * looks connected on the handset and reaches nothing.
 *
 * ## The grant
 *
 * `ACCESS_NETWORK_STATE` is install-time and already declared; there is nothing for the holder to
 * refuse, so there is no permission branch here.
 */
class ConnectivityEventSource(
    private val appContext: Context,
) : DeviceEventSource {
    override val category: DeviceEventCategory = DeviceEventCategory.CONNECTIVITY

    override fun events(): Flow<DeviceEvent> {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            Log.i(TAG, "No connectivity service on this device; it reports no connectivity events")
            return emptyFlow()
        }
        return callbackFlow {
            var previous: ConnectivityState? = null
            val emit = { next: ConnectivityState ->
                if (ConnectivityChange.isReportable(previous, next)) {
                    previous = next
                    trySend(
                        DeviceEvent(
                            category = category,
                            occurredAtMillis = System.currentTimeMillis(),
                            payload = EventPayloads.connectivity(next),
                        ),
                    )
                }
            }
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        emit(stateOf(capabilities))
                    }

                    override fun onLost(network: Network) {
                        emit(OFFLINE)
                    }
                }
            try {
                manager.registerDefaultNetworkCallback(callback)
                Log.i(TAG, "Reporting connectivity events")
            } catch (e: Exception) {
                Log.w(TAG, "Could not watch the default network; this device reports no connectivity events", e)
            }
            awaitClose {
                try {
                    manager.unregisterNetworkCallback(callback)
                } catch (e: Exception) {
                    Log.w(TAG, "Network callback was already gone", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "MCP:ConnectivityEvents"

        /**
         * What a handset with no default network reports.
         *
         * A POSITIVE statement rather than silence: `onLost` is the only signal that a phone has
         * gone dark, and a subscriber that never heard it could not tell "offline" from "this
         * device has never reported".
         */
        val OFFLINE = ConnectivityState(ConnectivityTransport.NONE, online = false)

        /**
         * The reported tuple for a set of capabilities.
         *
         * Reading the three flags is all this does; which word they map to is
         * [ConnectivityChange.transportOf]'s, where it is tested.
         */
        fun stateOf(capabilities: NetworkCapabilities): ConnectivityState =
            ConnectivityState(
                transport =
                    ConnectivityChange.transportOf(
                        hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        hasCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    ),
                online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
    }
}
