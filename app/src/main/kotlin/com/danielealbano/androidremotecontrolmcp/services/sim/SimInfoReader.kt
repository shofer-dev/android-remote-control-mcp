package com.danielealbano.androidremotecontrolmcp.services.sim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the tethered SIM's own identity. Behind an interface for JVM-testable substitution. */
interface SimInfoReader {
    fun read(): SimInfoResult
}

/**
 * Reads the active subscriptions via `SubscriptionManager`, the reliable app-side rung for a
 * SIM's own MSISDN.
 *
 * ## Why an app verb and not adb
 *
 * The host tried the adb ladder first and both rungs fail on a RACKED RETAIL (user-build) phone:
 * `content query content://telephony/siminfo` needs `READ_PRIVILEGED_PHONE_STATE`, which the adb
 * shell holds only on `userdebug`/`eng` builds, and `service call iphonesubinfo <n>` depends on a
 * build-specific transaction index. Inside the app it takes TWO runtime permissions —
 * `READ_PHONE_STATE` to ENUMERATE the active subscriptions (`getActiveSubscriptionInfoList`, which
 * the multi-SIM requirement needs) and `READ_PHONE_NUMBERS` to read each number
 * (`getPhoneNumber`). Both are `dangerous` runtime permissions grantable via `pm grant` on a user
 * build, exactly like the accessibility/DUMP grants the provisioning path issues; NEITHER is the
 * privileged `READ_PRIVILEGED_PHONE_STATE` that the adb rung could not obtain.
 *
 * ## Why SubscriptionManager and not TelephonyManager
 *
 * `TelephonyManager.getLine1Number()` is deprecated since API 33 and unreliable across OEMs; it
 * also cannot express multi-SIM. `SubscriptionManager.getActiveSubscriptionInfoList()` returns one
 * [SubscriptionInfo] per active SIM, and the number is read with
 * `SubscriptionManager.getPhoneNumber(subscriptionId)` on API 33+ — the non-deprecated replacement
 * for `SubscriptionInfo.getNumber()` — falling back to `getNumber()` on the API 31/32 builds that
 * predate it.
 *
 * Both permissions are checked here rather than trusting a throw, because an ungranted read
 * returns an empty list on some builds and a `SecurityException` on others — neither of which must
 * be read as "no SIM". The classification itself lives in [SimInfoResult.evaluate], which is pure.
 */
@Singleton
class SimInfoReaderImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : SimInfoReader {
        /**
         * Both grants are required — READ_PHONE_STATE to enumerate, READ_PHONE_NUMBERS to read the
         * number — and a missing EITHER is reported as the typed re-grant outcome rather than
         * silently degrading (a present list with every number nulled would look like
         * number-not-provisioned on every SIM). The guard is here; [readGranted] does the read.
         */
        @RequiresPermission(allOf = [Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS])
        override fun read(): SimInfoResult =
            if (hasRequiredPermissions()) {
                readGranted()
            } else {
                Log.i(TAG, "READ_PHONE_STATE / READ_PHONE_NUMBERS not both granted; permission-not-granted")
                SimInfoResult.PermissionNotGranted
            }

        @Suppress("TooGenericExceptionCaught")
        @RequiresPermission(allOf = [Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS])
        private fun readGranted(): SimInfoResult {
            val subscriptionManager =
                context.getSystemService(SubscriptionManager::class.java) ?: return SimInfoResult.NoActiveSim
            return try {
                val subs =
                    subscriptionManager.activeSubscriptionInfoList.orEmpty().map { info ->
                        info.toSimSubscription(subscriptionManager)
                    }
                SimInfoResult.evaluate(permissionGranted = true, subscriptions = subs)
            } catch (e: SecurityException) {
                // A revoke that races the check lands here; report it as the permission state
                // rather than an error so the host re-runs the grant instead of alarming.
                Log.w(TAG, "SecurityException reading subscriptions; treating as permission-not-granted", e)
                SimInfoResult.PermissionNotGranted
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read active subscriptions", e)
                SimInfoResult.NoActiveSim
            }
        }

        private fun hasRequiredPermissions(): Boolean =
            hasPermission(Manifest.permission.READ_PHONE_STATE) &&
                hasPermission(Manifest.permission.READ_PHONE_NUMBERS)

        private fun hasPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        /**
         * The number comes from `SubscriptionManager.getPhoneNumber(subId)` on API 33+ — the
         * sanctioned replacement for `SubscriptionInfo.getNumber()` — and from `getNumber()` itself
         * on API 31/32, where the replacement does not exist yet. The deprecated call is not a
         * degradation there: it is the same value read from the same SIM record under the same two
         * grants, and it is what every Android 12 build has. A blank result is the
         * number-not-provisioned case and is classified downstream, never here. The
         * `@RequiresPermission` propagates the obligation to [read], which holds the guard.
         *
         * `iccId` (`getIccId`) carries no lint permission gate: it returns a redacted/empty value
         * for a non-privileged caller rather than throwing, so a blank ICCID is normal on a retail
         * build and is normalised to null downstream.
         */
        @RequiresPermission(Manifest.permission.READ_PHONE_NUMBERS)
        private fun SubscriptionInfo.toSimSubscription(subscriptionManager: SubscriptionManager): SimSubscription {
            val msisdn = runCatching { readNumber(subscriptionManager) }.getOrNull()
            return SimSubscription(
                subscriptionId = subscriptionId,
                number = msisdn,
                carrierName = carrierName?.toString(),
                iccId = runCatching { iccId }.getOrNull(),
            )
        }

        /**
         * The version branch itself, kept out of [toSimSubscription] so the deprecation suppression
         * covers exactly the one legacy call and nothing else.
         */
        @RequiresPermission(Manifest.permission.READ_PHONE_NUMBERS)
        private fun SubscriptionInfo.readNumber(subscriptionManager: SubscriptionManager): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                subscriptionManager.getPhoneNumber(subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                number
            }

        companion object {
            private const val TAG = "MCP:SimInfo"
        }
    }
