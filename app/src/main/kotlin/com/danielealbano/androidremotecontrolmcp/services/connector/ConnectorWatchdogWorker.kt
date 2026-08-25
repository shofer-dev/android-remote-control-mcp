package com.danielealbano.androidremotecontrolmcp.services.connector

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.danielealbano.androidremotecontrolmcp.services.permissions.PermissionAuditor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The periodic self-heal tick: every 15 minutes, ask [ConnectorEnsure] whether the connector
 * should be running and start it if it is not, and re-run the permissions audit
 * ([PermissionAuditor]) so a grant that disappeared is reported without the app being opened.
 *
 * This is the path that repairs a connector killed while the app is closed — the HyperOS failure
 * mode where `START_STICKY` is suppressed, `BOOT_COMPLETED` never arrives (no vendor autostart
 * permission) and the phone stays silently detached until someone opens the app. Fifteen minutes
 * is WorkManager's floor for periodic work, not a tuning choice.
 *
 * BATTERY: the worker declares no constraints and holds no wakelock of its own. It does nothing
 * but a DataStore read in the common case, and WorkManager already batches it with whatever else
 * the OS is running; the connector's own socket is where the power actually goes (see
 * `docs/phones/android_remote_control.md` §8).
 *
 * DEPENDENCY INJECTION: dependencies come from the Hilt singleton graph through an
 * [EntryPoint] rather than `@HiltWorker`. `@HiltWorker` would oblige the app to disable
 * WorkManager's default `androidx.startup` initializer and re-provide it from
 * [com.danielealbano.androidremotecontrolmcp.McpApplication] — a manifest-level change to how
 * WorkManager comes up, in exchange for constructor injection into one worker that needs exactly
 * one dependency. The entry point is Hilt's own documented answer for classes it cannot
 * construct, and it leaves WorkManager's initialisation untouched.
 *
 * It always reports success: a failure has nowhere useful to go (the next tick is 15 minutes
 * away either way) and `Result.retry()` on periodic work only moves the same attempt earlier.
 */
class ConnectorWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val entryPoint =
            EntryPointAccessors
                .fromApplication(applicationContext, WatchdogEntryPoint::class.java)

        // ORDER AND ISOLATION ARE BOTH LOAD-BEARING. The revive is the reason this worker exists,
        // so it runs FIRST and its failure is the only one allowed to end the tick. The audit is a
        // REPORT — it reads Settings.Secure, DevicePolicyManager and PowerManager, any of which a
        // vendor build can make throw — and a report that cannot be produced must never be able to
        // stop the device healing itself. Running it first and unguarded (as this originally did)
        // is precisely the shape where one component's exception silently disables another.
        val outcome = entryPoint.connectorEnsure().ensure(REASON)
        val audit =
            runCatching { entryPoint.permissionAuditor().refresh() }
                .onFailure { Log.w(TAG, "Permissions audit failed; the connector ensure is unaffected", it) }
                .getOrNull()
        Log.i(TAG, "Watchdog tick: $outcome, permissions missing=${audit?.missing?.size ?: "unknown"}")
        return Result.success()
    }

    /** Hands the worker the singletons it drives from the application's Hilt graph. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WatchdogEntryPoint {
        fun connectorEnsure(): ConnectorEnsure

        fun permissionAuditor(): PermissionAuditor
    }

    companion object {
        private const val TAG = "MCP:ConnectorWatchdog"
        private const val REASON = "watchdog"

        /** The unique-work name; one schedule per device, kept across process death and reboot. */
        const val UNIQUE_WORK_NAME = "connector-watchdog"
    }
}
