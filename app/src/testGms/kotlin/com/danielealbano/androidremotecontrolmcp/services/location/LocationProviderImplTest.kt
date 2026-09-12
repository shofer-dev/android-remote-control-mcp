package com.danielealbano.androidremotecontrolmcp.services.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.OnCanceledListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("LocationProviderImpl")
class LocationProviderImplTest {
    private lateinit var mockContext: Context
    private lateinit var mockFusedClient: FusedLocationProviderClient
    private lateinit var mockGoogleApiAvailability: GoogleApiAvailability
    private lateinit var provider: LocationProviderImpl

    @BeforeEach
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockFusedClient = mockk(relaxed = true)
        mockGoogleApiAvailability = mockk(relaxed = true)

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        mockkStatic(GoogleApiAvailability::class)
        every { GoogleApiAvailability.getInstance() } returns mockGoogleApiAvailability

        mockkStatic(ContextCompat::class)

        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(mockContext) } returns mockFusedClient

        mockkStatic(Geocoder::class)
        every { Geocoder.isPresent() } returns true

        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)

        provider = LocationProviderImpl(mockContext)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun setupPlayServicesAvailable() {
        every {
            mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext)
        } returns ConnectionResult.SUCCESS
    }

    private fun setupPermissionGranted() {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED
    }

    @Suppress("UNCHECKED_CAST")
    private fun setupLastLocationTask(location: Location?) {
        val mockTask = mockk<Task<Location>>()
        every { mockFusedClient.lastLocation } returns mockTask
        every { mockTask.addOnSuccessListener(any()) } answers {
            val listener = firstArg<OnSuccessListener<Location>>()
            listener.onSuccess(location)
            mockTask
        }
        every { mockTask.addOnFailureListener(any()) } returns mockTask
        every { mockTask.addOnCanceledListener(any()) } returns mockTask
    }

    @Test
    fun `getLocation returns failure when Play Services unavailable`() =
        runTest {
            every {
                mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext)
            } returns ConnectionResult.SERVICE_MISSING

            val result = provider.getLocation(false)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("Google Play Services not available"))
        }

    @Test
    fun `getLocation returns failure when permission not granted`() =
        runTest {
            setupPlayServicesAvailable()
            every {
                ContextCompat.checkSelfPermission(mockContext, Manifest.permission.ACCESS_FINE_LOCATION)
            } returns PackageManager.PERMISSION_DENIED

            val result = provider.getLocation(false)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is SecurityException)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("Location permission not granted"))
        }

    @Test
    fun `getLocation with freshFix false returns last known location`() =
        runTest {
            setupPlayServicesAvailable()
            setupPermissionGranted()

            val mockLocation =
                mockk<Location> {
                    every { latitude } returns 37.7749
                    every { longitude } returns -122.4194
                    every { accuracy } returns 10.5f
                }
            setupLastLocationTask(mockLocation)

            every { Geocoder.isPresent() } returns false

            val result = provider.getLocation(false)

            assertTrue(result.isSuccess)
            val data = result.getOrThrow()
            assertEquals(37.7749, data.latitude)
            assertEquals(-122.4194, data.longitude)
            assertEquals(10.5f, data.accuracyMeters)
        }

    @Test
    fun `getLocation with freshFix false returns failure when no last known`() =
        runTest {
            setupPlayServicesAvailable()
            setupPermissionGranted()
            setupLastLocationTask(null)

            val result = provider.getLocation(false)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("No last known location available"))
        }

    @Test
    fun `getLocation with freshFix true requests location update`() =
        runTest {
            setupPlayServicesAvailable()
            setupPermissionGranted()

            val callbackSlot = slot<LocationCallback>()
            every {
                mockFusedClient.requestLocationUpdates(any(), capture(callbackSlot), any<Looper>())
            } returns mockk(relaxed = true)

            val mockLocation =
                mockk<Location> {
                    every { latitude } returns 37.7749
                    every { longitude } returns -122.4194
                    every { accuracy } returns 5.0f
                }

            val mockLocationResult =
                mockk<LocationResult> {
                    every { lastLocation } returns mockLocation
                }

            every { Geocoder.isPresent() } returns false

            // Launch and trigger callback
            val job =
                launch {
                    val result = provider.getLocation(true)
                    assertTrue(result.isSuccess)
                    assertEquals(37.7749, result.getOrThrow().latitude)
                }

            // Let the coroutine suspend at suspendCancellableCoroutine without
            // advancing virtual time past the withTimeout threshold
            advanceTimeBy(1)

            // Invoke the callback
            callbackSlot.captured.onLocationResult(mockLocationResult)

            testScheduler.advanceUntilIdle()
            job.join()
        }

    @Test
    fun `getLocation returns street from Geocoder`() =
        runTest {
            setupPlayServicesAvailable()
            setupPermissionGranted()

            val mockLocation =
                mockk<Location> {
                    every { latitude } returns 37.7749
                    every { longitude } returns -122.4194
                    every { accuracy } returns 10.5f
                }
            setupLastLocationTask(mockLocation)

            // The deprecated synchronous overload, not the API 33 callback one: `Build.VERSION.SDK_INT`
            // reads 0 in a JVM unit test, so reverseGeocode takes its pre-33 branch. The callback
            // form is the same lookup expressed differently and is exercised on a real API 33+ device.
            val mockAddress =
                mockk<Address> {
                    every { getAddressLine(0) } returns "123 Main St, San Francisco, CA"
                }
            mockkConstructor(Geocoder::class)
            @Suppress("DEPRECATION")
            every {
                anyConstructed<Geocoder>().getFromLocation(any<Double>(), any<Double>(), any<Int>())
            } returns listOf(mockAddress)

            val result = provider.getLocation(false)

            assertTrue(result.isSuccess)
            assertEquals("123 Main St, San Francisco, CA", result.getOrThrow().street)
        }

    @Test
    fun `getLocation returns null street when Geocoder not present`() =
        runTest {
            setupPlayServicesAvailable()
            setupPermissionGranted()

            val mockLocation =
                mockk<Location> {
                    every { latitude } returns 37.7749
                    every { longitude } returns -122.4194
                    every { accuracy } returns 10.5f
                }
            setupLastLocationTask(mockLocation)

            every { Geocoder.isPresent() } returns false

            val result = provider.getLocation(false)

            assertTrue(result.isSuccess)
            assertNull(result.getOrThrow().street)
        }

    @Test
    fun `getLocation returns null street when Geocoder fails`() =
        runTest {
            setupPlayServicesAvailable()
            setupPermissionGranted()

            val mockLocation =
                mockk<Location> {
                    every { latitude } returns 37.7749
                    every { longitude } returns -122.4194
                    every { accuracy } returns 10.5f
                }
            setupLastLocationTask(mockLocation)

            mockkConstructor(Geocoder::class)
            @Suppress("DEPRECATION")
            every {
                anyConstructed<Geocoder>().getFromLocation(any<Double>(), any<Double>(), any<Int>())
            } throws IOException("Service unavailable")

            val result = provider.getLocation(false)

            assertTrue(result.isSuccess)
            assertNull(result.getOrThrow().street)
        }

    @Test
    fun `getLocation returns null street when Geocoder returns empty list`() =
        runTest {
            setupPlayServicesAvailable()
            setupPermissionGranted()

            val mockLocation =
                mockk<Location> {
                    every { latitude } returns 37.7749
                    every { longitude } returns -122.4194
                    every { accuracy } returns 10.5f
                }
            setupLastLocationTask(mockLocation)

            mockkConstructor(Geocoder::class)
            @Suppress("DEPRECATION")
            every {
                anyConstructed<Geocoder>().getFromLocation(any<Double>(), any<Double>(), any<Int>())
            } returns emptyList()

            val result = provider.getLocation(false)

            assertTrue(result.isSuccess)
            assertNull(result.getOrThrow().street)
        }

    @Test
    fun `getLocation with freshFix true returns failure on timeout`() =
        runTest {
            setupPlayServicesAvailable()
            setupPermissionGranted()

            // Setup requestLocationUpdates but never invoke callback (simulate timeout)
            every {
                mockFusedClient.requestLocationUpdates(any(), any<LocationCallback>(), any<Looper>())
            } returns mockk(relaxed = true)

            val result = provider.getLocation(true)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("Timed out"))
        }

    @Test
    fun `getLocation with freshFix true returns failure when LocationResult lastLocation is null`() =
        runTest {
            setupPlayServicesAvailable()
            setupPermissionGranted()

            val callbackSlot = slot<LocationCallback>()
            every {
                mockFusedClient.requestLocationUpdates(any(), capture(callbackSlot), any<Looper>())
            } returns mockk(relaxed = true)

            val mockLocationResult =
                mockk<LocationResult> {
                    every { lastLocation } returns null
                }

            val job =
                launch {
                    val result = provider.getLocation(true)
                    assertTrue(result.isFailure)
                    assertTrue(result.exceptionOrNull()!!.message!!.contains("Location result was null"))
                }

            // Let coroutine suspend without advancing past withTimeout
            advanceTimeBy(1)
            callbackSlot.captured.onLocationResult(mockLocationResult)
            testScheduler.advanceUntilIdle()
            job.join()
        }

    @Test
    fun `getLocation rethrows CancellationException`() =
        runTest {
            setupPlayServicesAvailable()
            setupPermissionGranted()

            // Setup lastLocation task that never completes
            val mockTask = mockk<Task<Location>>()
            every { mockFusedClient.lastLocation } returns mockTask
            every { mockTask.addOnSuccessListener(any()) } returns mockTask
            every { mockTask.addOnFailureListener(any()) } returns mockTask
            every { mockTask.addOnCanceledListener(any()) } returns mockTask

            var thrownException: Throwable? = null
            val job =
                launch {
                    try {
                        provider.getLocation(false)
                    } catch (e: CancellationException) {
                        thrownException = e
                        throw e
                    }
                }
            testScheduler.advanceUntilIdle()
            job.cancel()
            job.join()

            assertNotNull(thrownException)
            assertTrue(thrownException is CancellationException)
        }
}
