@file:Suppress("TooGenericExceptionCaught")

package com.danielealbano.androidremotecontrolmcp.services.selfupdate

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import java.io.File

/** What this app knows about a build newer than the one running. */
sealed interface UpdateAvailability {
    /**
     * Nobody has answered. Either no check has been made on this connection, or one was made and
     * the gateway never replied — an older gateway with no `update_check` handler simply ignores
     * the frame, so silence is a real and expected outcome rather than a fault.
     */
    data object Unknown : UpdateAvailability

    /** A check is on the wire and its answer has not lapsed yet. */
    data object Checking : UpdateAvailability

    /** The gateway answered, and what it publishes is what is already running (or it publishes nothing). */
    data object UpToDate : UpdateAvailability

    /** The gateway publishes a version this device is not running. */
    data class Available(
        val spec: UpdateSpec,
    ) : UpdateAvailability
}

/** How far an update this device decided to apply has got. */
sealed interface UpdateInstall {
    data object Idle : UpdateInstall

    data object Downloading : UpdateInstall

    /**
     * The session is committed and the OS is working. On the ordinary path this process is killed
     * from here, so it is a state the UI shows and rarely a state it leaves.
     */
    data object Installing : UpdateInstall

    /**
     * The OS answered `STATUS_PENDING_USER_ACTION`: the install is staged and waiting for the
     * holder to confirm it. The confirmation has already been put in front of them —
     * [SelfUpdateStatusReceiver] either started it or posted a notification that launches it.
     */
    data object AwaitingConfirmation : UpdateInstall

    /** [error] is the wire refusal code; [details] is prose for the holder and the log. */
    data class Failed(
        val error: String,
        val details: String,
    ) : UpdateInstall
}

/** Everything the update card renders, and everything the connector reports, in one value. */
data class SelfUpdateState(
    val availability: UpdateAvailability = UpdateAvailability.Unknown,
    val install: UpdateInstall = UpdateInstall.Idle,
)

/** The answer an `update_app` action gets on the socket. */
sealed interface UpdateOutcome {
    /**
     * Downloaded, verified against its declared hash, and handed to the OS installer.
     *
     * It is deliberately NOT "installed": the install replaces this process, so completion can
     * never be awaited on a socket the process owns. The platform observes success by this device
     * re-attaching with a new `app_version`.
     */
    data object Accepted : UpdateOutcome

    data class Refused(
        val error: String,
        val details: String,
    ) : UpdateOutcome
}

/**
 * Applies a published build to THIS device, and holds the one piece of state both the holder's
 * card and the platform's action path read.
 *
 * ── Why this exists ────────────────────────────────────────────────────────────────────────────
 * The connector is sideloaded: there is no store, so without this a new build reaches a phone only
 * by somebody fetching an APK by hand. Two callers drive it and they meet here rather than each
 * carrying their own copy of the sequence:
 *
 * - the platform, pushing an `update_app` action frame at a racked handset nobody is holding;
 * - the holder, tapping Update on the connector screen after an `update_check` came back with a
 *   version this device is not running.
 *
 * ── The order is the contract, and every step can refuse ───────────────────────────────────────
 * Same-version → fetch → checksum → install, with a typed refusal at each step that the platform
 * branches on ([UpdateRefusal]). Two of them are worth stating:
 *
 * - **Same-version is `already-current`, not a failure.** A platform that re-pushes the running
 *   build has not malfunctioned, and answering it as an error would make a healthy fleet look
 *   broken. It also updates [UpdateAvailability] to [UpdateAvailability.UpToDate], because the
 *   push proved what the gateway publishes.
 * - **The checksum is integrity against the TRANSPORT, not trust in the publisher.** The real
 *   boundary is the OS's signature check: an APK signed with any other key is refused by Android
 *   with `STATUS_FAILURE_CONFLICT`, so a compromised gateway cannot install a different app under
 *   this package name. The hash catches a truncated or tampered download over a link nobody here
 *   controls.
 *
 * This class deliberately touches NO Android framework type beyond a log tag: the seams
 * ([ApkDownloader], [ApkInstaller]) carry the OS, so every branch above is a plain JVM unit test.
 */
class SelfUpdater(
    private val workDir: File,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val appVersion: String = BuildConfig.VERSION_NAME,
) {
    private val _state = MutableStateFlow(SelfUpdateState())
    val state: StateFlow<SelfUpdateState> = _state.asStateFlow()

    private val _checkRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Requests for a fresh `update_check` frame, collected by the connector for the life of an
     * attached socket.
     *
     * A flow rather than a method on the connector because there is no connector to call when
     * nothing is attached — and "ask the platform what it publishes" is meaningless without a
     * socket. A request raised while detached is simply dropped, which is the honest answer.
     */
    val checkRequests: SharedFlow<Unit> = _checkRequests.asSharedFlow()

    /**
     * Serialises whole update attempts. `tryLock` rather than `withLock`: a second push arriving
     * mid-install must be REFUSED, not queued behind an install that is about to end the process.
     */
    private val applying = Mutex()

    /**
     * The id of the check still waiting for an answer, so a lapsed timer can only retire the check
     * it belongs to. Without it a refresh pressed five seconds after the per-attach check would be
     * downgraded to [UpdateAvailability.Unknown] by the FIRST check's timer, while its own answer
     * was still perfectly in flight.
     */
    @Volatile private var pendingCheckId: String? = null

    /** The holder's refresh control, and what the connector's per-attach check is triggered by. */
    fun requestCheck() {
        _checkRequests.tryEmit(Unit)
    }

    /** The connector put an `update_check` carrying [id] on the wire. */
    fun onCheckSent(id: String) {
        pendingCheckId = id
        _state.update { it.copy(availability = UpdateAvailability.Checking) }
    }

    /** The answer to the check with [id] never came; a newer check's is not touched. */
    fun onCheckLapsed(id: String) {
        if (pendingCheckId != id) return
        pendingCheckId = null
        _state.update {
            if (it.availability is UpdateAvailability.Checking) {
                it.copy(availability = UpdateAvailability.Unknown)
            } else {
                it
            }
        }
    }

    /**
     * Applies an `update_info` answer. An unusable or empty payload is [UpdateAvailability.UpToDate]
     * rather than [UpdateAvailability.Unknown]: the gateway ANSWERED, and the contract's way of
     * saying "nothing is published" is an absent or empty `version`.
     *
     * The frame's `id` is deliberately NOT matched against [pendingCheckId]. The gateway sends this
     * type only in reply, so an answer that arrives is an answer to the outstanding question; a
     * strict match would discard a perfectly good one from a gateway that minted its own id.
     */
    fun onUpdateInfo(params: JsonElement?) {
        pendingCheckId = null
        val spec = UpdateSpec.parse(params)
        val availability =
            if (spec == null || spec.version == appVersion) {
                UpdateAvailability.UpToDate
            } else {
                UpdateAvailability.Available(spec)
            }
        Log.i(TAG, "Update check answered: $availability")
        _state.update { it.copy(availability = availability) }
    }

    /** The card's Update button: applies whatever the last check found, or nothing. */
    suspend fun applyAvailableUpdate(): UpdateOutcome? {
        val available = _state.value.availability as? UpdateAvailability.Available ?: return null
        return applyUpdate(available.spec)
    }

    /**
     * Fetches, verifies and installs [spec]. Returns as soon as the OS installer has taken the
     * session — see [UpdateOutcome.Accepted] for why completion is not awaited.
     */
    suspend fun applyUpdate(spec: UpdateSpec): UpdateOutcome =
        when {
            // Not a failure, so it does not go through the Failed state below: the platform
            // re-publishing what is running has PROVED this device is current.
            spec.version == appVersion -> {
                _state.update { it.copy(availability = UpdateAvailability.UpToDate, install = UpdateInstall.Idle) }
                UpdateOutcome.Refused(
                    UpdateRefusal.ALREADY_CURRENT,
                    "this device already runs version $appVersion",
                )
            }

            !applying.tryLock() -> {
                UpdateOutcome.Refused(
                    UpdateRefusal.INSTALL_REFUSED,
                    "an update is already being applied on this device",
                )
            }

            else -> {
                try {
                    val outcome = fetchVerifyInstall(spec)
                    // ONE place turns a refusal into the state the card renders, so a step added
                    // to the sequence below cannot forget to report itself.
                    if (outcome is UpdateOutcome.Refused) {
                        _state.update { it.copy(install = UpdateInstall.Failed(outcome.error, outcome.details)) }
                    }
                    outcome
                } finally {
                    applying.unlock()
                }
            }
        }

    private suspend fun fetchVerifyInstall(spec: UpdateSpec): UpdateOutcome {
        Log.i(TAG, "Applying update ${spec.version} over $appVersion")
        val target = File(workDir, APK_FILE_NAME)
        _state.update { it.copy(install = UpdateInstall.Downloading) }

        var downloadError: String? = null
        val digest =
            try {
                downloader.download(spec.url, target)
            } catch (e: Exception) {
                // Never the URL: it may carry a capability token in its query string.
                Log.w(TAG, "Could not fetch update ${spec.version}", e)
                downloadError = e.message ?: "the APK could not be fetched"
                null
            }

        return when {
            digest == null -> {
                target.delete()
                UpdateOutcome.Refused(UpdateRefusal.DOWNLOAD_FAILED, downloadError.orEmpty())
            }

            !digest.equals(spec.sha256, ignoreCase = true) -> {
                Log.e(TAG, "Update ${spec.version} failed its checksum; discarding")
                target.delete()
                UpdateOutcome.Refused(
                    UpdateRefusal.CHECKSUM_MISMATCH,
                    "the fetched APK does not match the sha256 the platform declared",
                )
            }

            else -> {
                _state.update { it.copy(install = UpdateInstall.Installing) }
                try {
                    installer.install(target)
                    UpdateOutcome.Accepted
                } catch (e: Exception) {
                    Log.e(TAG, "The OS installer refused update ${spec.version}", e)
                    UpdateOutcome.Refused(
                        UpdateRefusal.INSTALL_REFUSED,
                        e.message ?: "the OS installer refused the session",
                    )
                } finally {
                    // The session holds its own copy of the bytes once the write returns, so the
                    // staged file is dead weight on every path — including the one where this
                    // process survives to see it.
                    target.delete()
                }
            }
        }
    }

    /** The OS wants the holder to confirm; the confirmation is already in front of them. */
    fun onInstallPending() {
        _state.update { it.copy(install = UpdateInstall.AwaitingConfirmation) }
    }

    /** The install landed. Reached only when the OS reports before replacing this process. */
    fun onInstallSucceeded() {
        _state.update { SelfUpdateState(availability = UpdateAvailability.UpToDate, install = UpdateInstall.Idle) }
    }

    /** The OS refused the committed session. [details] is its own status message where it gave one. */
    fun onInstallFailed(details: String) {
        _state.update { it.copy(install = UpdateInstall.Failed(UpdateRefusal.INSTALL_REFUSED, details)) }
    }

    companion object {
        private const val TAG = "MCP:SelfUpdater"

        /**
         * One fixed name in a directory of our own, so a failed attempt's bytes are overwritten by
         * the next rather than accumulating a file per version in a cache nobody prunes.
         */
        internal const val APK_FILE_NAME = "update.apk"

        /** The cache subdirectory the APK is staged in, relative to the app's own cache dir. */
        const val WORK_DIR_NAME = "self-update"
    }
}
