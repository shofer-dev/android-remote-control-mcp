package com.danielealbano.androidremotecontrolmcp.services.selfupdate

import android.content.pm.PackageInstaller

/**
 * Turns a `PackageInstaller` status code into one sentence a holder and a log line can both use.
 *
 * It lives outside [SelfUpdateStatusReceiver] rather than inside it because it is the only part of
 * that receiver with a decision in it, and a `BroadcastReceiver` is an awkward thing to call into
 * from a test. Here it is an ordinary function over an Int.
 *
 * The OS's own `EXTRA_STATUS_MESSAGE` is appended whenever there is one: it names the conflicting
 * package or the missing ABI, which the status code alone never does. Dropping it is how "a
 * different signing certificate" becomes "the install failed" — the single most misleading thing
 * this path could report, since a certificate mismatch is a build-pipeline fault that will never
 * fix itself.
 */
object InstallFailure {
    fun describe(
        status: Int,
        message: String?,
    ): String {
        val reason =
            when (status) {
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    "the update was declined or the session was abandoned"
                }

                PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                    "the update was blocked by device policy or a package verifier"
                }

                PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                    "the update conflicts with the installed app — usually a different signing certificate"
                }

                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                    "the update is incompatible with this device"
                }

                PackageInstaller.STATUS_FAILURE_INVALID -> {
                    "the APK is malformed, corrupt or incorrectly signed"
                }

                PackageInstaller.STATUS_FAILURE_STORAGE -> {
                    "there is not enough storage for the update"
                }

                PackageInstaller.STATUS_FAILURE_TIMEOUT -> {
                    "the install did not finish in time"
                }

                else -> {
                    "the install failed (status $status)"
                }
            }
        return if (message.isNullOrBlank()) reason else "$reason: $message"
    }
}
