package com.danielealbano.androidremotecontrolmcp.services.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "MCP:ReverseGeocoder"

/** Framework-only (GMS-free) reverse geocoding shared by all LocationProvider implementations. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun reverseGeocode(
    context: Context,
    latitude: Double,
    longitude: Double,
): String? {
    if (!Geocoder.isPresent()) {
        Log.d(TAG, "Geocoder not present on this device")
        return null
    }
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            awaitAddressLine(context, latitude, longitude)
        } else {
            blockingAddressLine(context, latitude, longitude)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "Reverse geocoding failed: ${e.message}")
        null
    }
}

/**
 * The API 33+ path: the callback form, which the framework services off the caller's thread.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private suspend fun awaitAddressLine(
    context: Context,
    latitude: Double,
    longitude: Double,
): String? =
    suspendCancellableCoroutine { cont ->
        Geocoder(context, Locale.getDefault()).getFromLocation(
            latitude,
            longitude,
            1,
            object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: List<Address>) {
                    cont.resume(addresses.firstOrNull()?.getAddressLine(0))
                }

                override fun onError(errorMessage: String?) {
                    Log.d(TAG, "Geocoder onError: $errorMessage")
                    cont.resume(null)
                }
            },
        )
    }

/**
 * The API 31/32 path: the deprecated synchronous form, which is the only one those builds have.
 *
 * It performs network I/O on the calling thread and throws `IOException` when the backend service
 * is unreachable — hence [Dispatchers.IO], and hence the caller's catch, which turns a failed
 * lookup into "no address" exactly as [onError] does above.
 */
@Suppress("DEPRECATION")
private suspend fun blockingAddressLine(
    context: Context,
    latitude: Double,
    longitude: Double,
): String? =
    withContext(Dispatchers.IO) {
        Geocoder(context, Locale.getDefault())
            .getFromLocation(latitude, longitude, 1)
            ?.firstOrNull()
            ?.getAddressLine(0)
    }
