package com.danielealbano.androidremotecontrolmcp.di

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepositoryImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCacheImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutorImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCacheImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputControllerImpl
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManagerImpl
import com.danielealbano.androidremotecontrolmcp.services.camera.CameraProvider
import com.danielealbano.androidremotecontrolmcp.services.camera.CameraProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcherImpl
import com.danielealbano.androidremotecontrolmcp.services.connector.DeviceActionHandler
import com.danielealbano.androidremotecontrolmcp.services.connector.PlatformDeviceActionHandler
import com.danielealbano.androidremotecontrolmcp.services.connector.crypto.DeviceIdentity
import com.danielealbano.androidremotecontrolmcp.services.connector.crypto.KeystoreDeviceIdentity
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.AndroidDeviceEnvironment
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.DeviceEnvironment
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcherImpl
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProvider
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ApiLevelProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.DefaultApiLevelProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkService
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkServiceImpl
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentInbox
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentInboxImpl
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.storage.MediaStoreFileOperations
import com.danielealbano.androidremotecontrolmcp.services.storage.MediaStoreFileOperationsImpl
import com.danielealbano.androidremotecontrolmcp.services.storage.PermissionChecker
import com.danielealbano.androidremotecontrolmcp.services.storage.PermissionCheckerImpl
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProviderImpl
import com.danielealbano.androidremotecontrolmcp.utils.MonotonicClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the IO [CoroutineDispatcher]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Extension property for creating the Preferences DataStore on [Context]. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * Provides the application-scoped [DataStore] for settings persistence.
     */
    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.settingsDataStore

    /**
     * Provides [Dispatchers.IO] for background work.
     */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Provides the monotonic time source every elapsed-interval measurement reads.
     * `elapsedRealtime` rather than the wall clock: a link's age must not jump when the device's
     * clock is corrected.
     */
    @Provides
    @Singleton
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    /**
     * Binds [SettingsRepositoryImpl] as the implementation of [SettingsRepository].
     */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}

@Suppress("TooManyFunctions")
@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {
    @Binds
    @Singleton
    abstract fun bindAccessibilityNodeCache(impl: AccessibilityNodeCacheImpl): AccessibilityNodeCache

    @Binds
    @Singleton
    abstract fun bindScreenStateSnapshotCache(impl: ScreenStateSnapshotCacheImpl): ScreenStateSnapshotCache

    @Binds
    @Singleton
    abstract fun bindApiLevelProvider(impl: DefaultApiLevelProvider): ApiLevelProvider

    @Binds
    @Singleton
    abstract fun bindTypeInputController(impl: TypeInputControllerImpl): TypeInputController

    @Binds
    @Singleton
    abstract fun bindActionExecutor(impl: ActionExecutorImpl): ActionExecutor

    @Binds
    @Singleton
    abstract fun bindAccessibilityServiceProvider(impl: AccessibilityServiceProviderImpl): AccessibilityServiceProvider

    @Binds
    @Singleton
    abstract fun bindScreenCaptureProvider(impl: ScreenCaptureProviderImpl): ScreenCaptureProvider

    @Binds
    @Singleton
    abstract fun bindStorageLocationProvider(impl: StorageLocationProviderImpl): StorageLocationProvider

    @Binds
    @Singleton
    abstract fun bindFileOperationProvider(impl: FileOperationProviderImpl): FileOperationProvider

    @Binds
    @Singleton
    abstract fun bindMediaStoreFileOperations(impl: MediaStoreFileOperationsImpl): MediaStoreFileOperations

    @Binds
    @Singleton
    abstract fun bindAppManager(impl: AppManagerImpl): AppManager

    @Binds
    @Singleton
    abstract fun bindCameraProvider(impl: CameraProviderImpl): CameraProvider

    @Binds
    @Singleton
    abstract fun bindIntentDispatcher(impl: IntentDispatcherImpl): IntentDispatcher

    @Binds
    @Singleton
    abstract fun bindNotificationProvider(impl: NotificationProviderImpl): NotificationProvider

    @Binds
    @Singleton
    abstract fun bindPermissionChecker(impl: PermissionCheckerImpl): PermissionChecker

    // LocationProvider is bound per-flavor (Fused in gms / LocationManager in foss) — see
    // GmsLocationModule / FossLocationModule.

    @Binds
    @Singleton
    abstract fun bindEventDispatcher(impl: EventDispatcherImpl): EventDispatcher

    // GeofenceManager and GeofenceChannelController are bound per-flavor — see GmsGeofenceModule
    // (gms) / FossGeofenceModule (foss). foss has no GeofenceManager (geofencing excluded).

    @Binds
    @Singleton
    abstract fun bindEphemeralFileLinkService(impl: EphemeralFileLinkServiceImpl): EphemeralFileLinkService

    @Binds
    @Singleton
    abstract fun bindSharedContentInbox(impl: SharedContentInboxImpl): SharedContentInbox

    // --- Platform Connector ---

    @Binds
    @Singleton
    abstract fun bindDeviceIdentity(impl: KeystoreDeviceIdentity): DeviceIdentity

    // The DeviceAdminReceiver-backed executors (lock/wipe via DevicePolicyManager, locate via
    // the flavor-neutral LocationProvider, ring via AudioManager) — §6.4 device-action plane.
    @Binds
    @Singleton
    abstract fun bindDeviceActionHandler(impl: PlatformDeviceActionHandler): DeviceActionHandler

    // The seam the policy evaluation reads the device through (clock, keyguard, foreground
    // package, the OS's own Settings package) — behind an interface so PolicyEnforcer's rules
    // are unit-testable without a device.
    @Binds
    @Singleton
    abstract fun bindDeviceEnvironment(impl: AndroidDeviceEnvironment): DeviceEnvironment
}
