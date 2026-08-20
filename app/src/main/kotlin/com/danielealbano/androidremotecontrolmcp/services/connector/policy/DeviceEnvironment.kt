@file:Suppress("TooGenericExceptionCaught")

package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the policy evaluation needs from the device itself, behind a seam so
 * [PolicyEnforcer] stays pure enough to unit-test: the wall clock, the lock state, the
 * foreground package and the OS's own idea of which package is Settings.
 */
interface DeviceEnvironment {
    /** Wall-clock milliseconds. Injected so time-dependent rules are testable. */
    fun nowMillis(): Long

    /** Minutes since local midnight, in the DEVICE's own zone (see [ActiveHours]). */
    fun minuteOfDay(): Int

    /**
     * True when the screen is locked. Null means the OS could not be asked — treated as
     * LOCKED by the enforcer, because "I do not know whether the holder is looking at a lock
     * screen" is not a state in which a remote agent should be typing.
     */
    fun isScreenLocked(): Boolean?

    /**
     * The package of the app currently in the foreground, or null when the accessibility
     * service cannot say. Null is refused rather than allowed: the allowlist and the
     * structural denylist both key on this, so an unknown foreground would otherwise be a
     * hole exactly where the rule matters.
     */
    fun foregroundPackage(): String?

    /** This app's own package id (`.debug` suffix included on a debug build). */
    fun ownPackage(): String

    /** The packages the OS reports as handling the Settings UI. May be empty. */
    fun settingsPackages(): Set<String>
}

/**
 * The production [DeviceEnvironment].
 *
 * The Settings packages are RESOLVED rather than guessed: the PackageManager is asked which
 * activity handles `Settings.ACTION_SETTINGS`, so an OEM's own Settings package
 * (`com.oneplus.settings`, a vendor fork, whatever ships next) is denied without anyone
 * having had to predict its name. The resolution is cached for the process — the answer
 * cannot change without a reinstall of the system app, and a PackageManager query on the
 * path of every command would be a needless cost.
 */
@Singleton
class AndroidDeviceEnvironment
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) : DeviceEnvironment {
        private val resolvedSettingsPackages: Set<String> by lazy { resolveSettingsPackages() }

        override fun nowMillis(): Long = System.currentTimeMillis()

        override fun minuteOfDay(): Int = ActiveHours.minuteOfDay(nowMillis())

        override fun isScreenLocked(): Boolean? =
            try {
                val keyguard = appContext.getSystemService(KeyguardManager::class.java)
                // isKeyguardLocked covers both a secured lock screen and a swipe-only one:
                // either way the holder's screen is not the surface the agent thinks it is.
                keyguard?.isKeyguardLocked
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the keyguard state", e)
                null
            }

        override fun foregroundPackage(): String? = accessibilityServiceProvider.getCurrentPackageName()

        override fun ownPackage(): String = appContext.packageName

        override fun settingsPackages(): Set<String> = resolvedSettingsPackages

        private fun resolveSettingsPackages(): Set<String> =
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                appContext.packageManager
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    .mapNotNull { it.activityInfo?.packageName }
                    .toSet()
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve the Settings package; falling back to the known names", e)
                emptySet()
            }

        private companion object {
            const val TAG = "MCP:DeviceEnvironment"
        }
    }
