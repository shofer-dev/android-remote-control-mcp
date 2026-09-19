// The per-source and per-config catches are DELIBERATELY broad. This plane's job is to keep
// reporting; a source or a settings read that fails in a way nobody predicted must cost exactly
// that one thing and must SAY SO, never take the other four down and never disappear silently.
// Catching narrowly would mean predicting which vendor ROM throws what, which is the guess these
// catches exist to avoid. Same posture and same suppression as the sources beside them.
@file:Suppress("TooGenericExceptionCaught")

package com.danielealbano.androidremotecontrolmcp.services.connector.events

import android.content.Context
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.DeviceEventConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.DevicePolicy
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Turns the five [DeviceEventSource]s into `event` frames, gated by the holder-AND-platform
 * intersection, for as long as one connector socket lives.
 *
 * It holds NO rules. The change-only decisions belong to the sources ([BatteryChange],
 * [ConnectivityChange], [CallChange]), the forwarding decision belongs to [EventGate], and the
 * payload spellings belong to [EventPayloads] — all of them pure and unit-tested. What is left
 * here is plumbing: collect the sources concurrently, read the two filters as they stand when each
 * event arrives, and hand the survivors to the caller.
 *
 * ## Why the filters are read per EVENT and not per collection
 *
 * A holder who switches a category off mid-session, and a platform that sends a narrower snapshot
 * mid-session, must both take effect at once. Re-deriving the pipeline on every config change would
 * instead tear down and re-register every OS callback — dropping the change-only baselines with
 * them, so the next reading would re-report — so the sources are started ONCE and the filters are
 * read at the moment of decision.
 *
 * ## The policy is read through a supplier
 *
 * [policy] is a lambda rather than a value because the snapshot in force is owned by
 * [com.danielealbano.androidremotecontrolmcp.services.connector.policy.PolicyEnforcer] and is
 * replaced on every `policy` frame and cleared when the socket ends. A copy taken at construction
 * would be a policy the platform may already have changed — the same trap the enforcer's own
 * clear-on-disconnect rule exists to avoid.
 */
class DeviceEventReporter(
    private val sources: List<DeviceEventSource>,
    private val settingsRepository: SettingsRepository,
    private val policy: () -> DevicePolicy?,
) {
    /**
     * The frames this device wants to send, for as long as the flow is collected.
     *
     * Collecting is what registers every source with the OS; cancelling is what unregisters them,
     * which is why the connector starts this on `attached` and cancels it in the same `finally`
     * that tears down the heartbeat and the keyguard watch.
     */
    fun reports(): Flow<Frame> =
        callbackFlow {
            // NOTHING SUSPENDS BEFORE THIS LOOP, and that is the whole shape of this method.
            // Registering with the OS is the only thing that must happen; the holder's toggles are
            // an input to a decision made later, per event. An earlier version read the config
            // first, and a read that did not return left every source unregistered — the plane
            // silently reported nothing, with no exception and no log, for the life of the socket.
            Log.i(TAG, "Device event reporting starting for ${sources.size} categories")
            val holder = MutableStateFlow<DeviceEventConfig?>(null)
            launch { trackHolderConfig(holder) }
            launch { warnIfHolderConfigStalls(holder) }
            sources.forEach { source -> launch { runSource(source, holder) } }
            awaitClose { Log.i(TAG, "Device event reporting stopped") }
        }

    /**
     * Collects one source for the life of the flow, ISOLATED from its four siblings.
     *
     * The isolation is the point. All five share one producer scope, so without this a single
     * source throwing — a vendor ROM refusing a registration in a way the source's own catch does
     * not cover — would cancel the scope and take the whole plane down with it, and the throw would
     * then surface as an unhandled exception in the connector's scope rather than as a report about
     * one category. A failed source is loud, local and final: that category stops, the other four
     * keep going.
     */
    private suspend fun ProducerScope<Frame>.runSource(
        source: DeviceEventSource,
        holder: MutableStateFlow<DeviceEventConfig?>,
    ) {
        try {
            source.events().collect { event ->
                // Waits for the first config rather than assuming one. A holder who switched a
                // category off must not have it reported because a disk read was slow, so this
                // delays an EVENT and never a registration.
                val config = holder.filterNotNull().first()
                if (EventGate.allows(event, config, policy())) {
                    trySend(frameFor(event))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "The '${source.category.wire}' source stopped; this device reports no more of that category", e)
        }
    }

    /**
     * Tracks the holder's toggles for the life of the flow.
     *
     * A read that FAILS falls back to the documented all-on default — the same posture
     * [DeviceEventConfig.fromJsonOrDefault] takes for a blob it cannot parse, and for the same
     * reason: a phone that quietly stopped reporting looks exactly like a phone with nothing to
     * report. The platform's policy is still the other conjunct, so the org's narrowing survives it.
     */
    private suspend fun trackHolderConfig(holder: MutableStateFlow<DeviceEventConfig?>) {
        try {
            settingsRepository.deviceEventConfig.collect { holder.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not read the holder's event toggles; falling back to the all-on default", e)
            holder.compareAndSet(null, DeviceEventConfig())
        }
    }

    /**
     * Says so, once and loudly, if the toggles never arrive.
     *
     * It deliberately does NOT substitute a default. A config that has not arrived is not a config
     * that cannot be read: a holder who switched a category off made a CONSENT decision, and
     * overriding it because a read was slow would disclose what they declined. So events are held
     * and the condition is reported — the one thing that must never happen again is this plane
     * being silent AND invisible at the same time.
     */
    private suspend fun warnIfHolderConfigStalls(holder: MutableStateFlow<DeviceEventConfig?>) {
        delay(HOLDER_CONFIG_WARN_MS)
        if (holder.value == null) {
            Log.w(
                TAG,
                "The holder's event toggles have not arrived after ${HOLDER_CONFIG_WARN_MS}ms; " +
                    "every source is registered but events are held until they do",
            )
        }
    }

    companion object {
        private const val TAG = "MCP:DeviceEvents"

        /**
         * How long the toggles may take to arrive before the condition is reported. Generous: a
         * first DataStore read on a cold device is disk I/O, and this is a warning, not a timeout.
         */
        private const val HOLDER_CONFIG_WARN_MS = 10_000L

        /**
         * One event as the frame that carries it — the whole of this plane's outbound encoding.
         *
         * Internal so the mapping is pinned by a unit test rather than by inspection: the three
         * field names are the contract with the gateway, and a renamed one would be read as an
         * absent one.
         */
        internal fun frameFor(event: DeviceEvent): Frame =
            Frame(
                type = FrameType.EVENT,
                category = event.category.wire,
                occurredAt = event.occurredAt,
                payload = event.payload,
            )

        /**
         * The production source set, one per category, in the vocabulary's stable order.
         *
         * Built here rather than injected because each source is a thin wrapper over an OS
         * registration with no state worth a Hilt binding, and listing them in one place is what
         * makes "the plane reports five categories" checkable by reading five lines.
         */
        fun all(appContext: Context): List<DeviceEventSource> =
            listOf(
                NotificationEventSource(appContext),
                CallEventSource(appContext),
                SmsEventSource(appContext),
                ConnectivityEventSource(appContext),
                BatteryEventSource(appContext),
            )
    }
}
