@file:Suppress("TooGenericExceptionCaught")

package com.danielealbano.androidremotecontrolmcp.services.selfupdate

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The seam [SelfUpdater] hands a verified APK to. An interface so the updater can be driven in a
 * unit test without an Android [PackageInstaller] — the happy path asserts that the installer was
 * called, and every refusal path asserts that it was NOT.
 */
interface ApkInstaller {
    /**
     * Writes [apk] into a self-install session and commits it.
     *
     * Returning normally means the session was SEALED, not that the install succeeded: the outcome
     * arrives asynchronously at [SelfUpdateStatusReceiver], and on the ordinary path this process
     * is killed before it could be observed here.
     *
     * @throws Exception for anything that stops the session from being committed — no session, a
     *   write that failed, a commit the OS refused. The caller reports them as one
     *   [UpdateRefusal.INSTALL_REFUSED]; they are not separable by anything a device could do
     *   differently.
     */
    fun install(apk: File)
}

/**
 * The real installer: one `MODE_FULL_INSTALL` session over this app's own package.
 *
 * Whether the commit is SILENT is the OS's decision, not this class's, and it is worth stating
 * where the code is rather than leaving it to be rediscovered. Each clause below was read out of
 * `PackageInstallerSession.computeUserActionRequirement()` and `checkUserActionRequirement()` in
 * AOSP at tag `android14-release`, which is what the API-34 documentation is generated from:
 *
 * - `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` is a REQUEST. API 31+, so unconditional at
 *   `minSdk 33`; passing anything outside the three constants throws `IllegalArgumentException`.
 * - The framework honours it for a **self-update** — the package being written is the package
 *   doing the writing — without any installer-of-record history. So the FIRST self-update of a
 *   sideloaded build is already silent; the widely-repeated "you must be the installer of record"
 *   rule is the test applied to updating a DIFFERENT package.
 * - What it really depends on is the holder's per-app **"Install unknown apps"** toggle, which is
 *   the `appop` half of `REQUEST_INSTALL_PACKAGES`. Without it the OS answers
 *   `STATUS_PENDING_USER_ACTION` instead, and the install waits for a tap.
 * - A second silent update of the same package within 30 seconds also falls back to a prompt
 *   (`SilentUpdatePolicy`), so a retry loop starts asking the holder rather than looping quietly.
 *
 * None of that is something to detect in advance. The honest posture — and the framework's own
 * documented instruction — is to always be prepared to handle `STATUS_PENDING_USER_ACTION`, which
 * is why [SelfUpdateStatusReceiver] exists rather than being an optimisation.
 */
@Singleton
class PackageInstallerApkInstaller
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
    ) : ApkInstaller {
        override fun install(apk: File) {
            val installer = appContext.packageManager.packageInstaller
            val params =
                PackageInstaller
                    .SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply {
                        setAppPackageName(appContext.packageName)
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                        // A hint, so the OS can reserve space and refuse early with
                        // STATUS_FAILURE_STORAGE rather than part-way through the write.
                        setSize(apk.length())
                    }
            val sessionId = installer.createSession(params)
            try {
                writeAndCommit(installer, sessionId, apk)
            } catch (e: Exception) {
                // A session that is neither committed nor abandoned lingers and counts against this
                // app's session limit, so a failed attempt must not leave one behind.
                runCatching { installer.abandonSession(sessionId) }
                    .onFailure { Log.w(TAG, "Could not abandon the failed update session", it) }
                throw e
            }
        }

        /**
         * Streams the APK into the session and seals it.
         *
         * The write stream is closed BEFORE `commit`, which is a requirement rather than tidiness:
         * `commit` throws `SecurityException` while any stream opened through `openWrite` is still
         * open. `fsync` precedes that close so the bytes are durable before the session is sealed.
         */
        private fun writeAndCommit(
            installer: PackageInstaller,
            sessionId: Int,
            apk: File,
        ) {
            installer.openSession(sessionId).use { session ->
                session.openWrite(APK_ENTRY_NAME, 0, apk.length()).use { sink ->
                    apk.inputStream().use { source -> source.copyTo(sink) }
                    session.fsync(sink)
                }
                session.commit(statusSender(sessionId))
            }
            Log.i(TAG, "Committed self-update session $sessionId")
        }

        /**
         * Where the OS reports this session's outcome.
         *
         * **`FLAG_MUTABLE` is load-bearing.** The framework merges a fill-in Intent carrying
         * `EXTRA_STATUS` — and, for a pending confirmation, `Intent.EXTRA_INTENT` — into this
         * PendingIntent when it fires. An IMMUTABLE one accepts no fill-in, so the receiver would
         * run with no status at all, which reads as a mysterious default rather than as an error.
         * Android 12+ forbids a mutable IMPLICIT PendingIntent; this one names its receiver class,
         * so it is explicit and the restriction does not apply.
         *
         * The session id is the request code, so two sessions can never share one PendingIntent.
         */
        private fun statusSender(sessionId: Int): IntentSender =
            PendingIntent
                .getBroadcast(
                    appContext,
                    sessionId,
                    Intent(appContext, SelfUpdateStatusReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ).intentSender

        private companion object {
            const val TAG = "MCP:SelfUpdateInstall"

            /** The session-local name of the base APK; any stable name works, so this one is stated once. */
            const val APK_ENTRY_NAME = "base.apk"
        }
    }
