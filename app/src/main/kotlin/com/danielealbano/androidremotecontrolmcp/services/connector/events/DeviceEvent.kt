package com.danielealbano.androidremotecontrolmcp.services.connector.events

import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * The DEVICE EVENT plane: what happens on this handset, reported to the platform over the socket
 * the connector already holds (`docs/phone/device_events.md`).
 *
 * # Why this is its own package, beside the connector and NOT inside `services/channel`
 *
 * `services/channel` is upstream's EVENT-CHANNEL plugin: an outbound HTTP webhook with its
 * own [EventDispatcher][com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher],
 * its own endpoint/token configuration blob and its own foreground service. It happens to listen to
 * two of the same OS signals, and that is the whole of the resemblance. Hanging the platform plane
 * off it would couple two unrelated features through one config object and one lifecycle — so the
 * channel would have to be enabled for the platform to report, and clearing the channel's settings
 * would silence the platform. The sources here are therefore separate, and the connector's socket
 * is their only sink.
 *
 * # What lives here
 *
 * One [DeviceEventSource] per category, each turning an Android callback into a [Flow], plus the
 * two pieces of logic that must be right and therefore must be testable without a device: the
 * change-only gates ([BatteryChange], [ConnectivityChange]) and the forwarding intersection
 * ([EventGate]). [DeviceEventReporter] wires them to the socket and holds no rules of its own.
 */
data class DeviceEvent(
    /** Which category this is — the wire spelling, the toggle, the policy key and the topic tag. */
    val category: DeviceEventCategory,
    /** When it happened, epoch millis on the HANDSET's own wall clock. */
    val occurredAtMillis: Long,
    /** The category's payload, spelled exactly as `deviceevent.go` documents it. */
    val payload: JsonObject,
    /**
     * The package this event is ABOUT, when it has one — the input to the platform's per-app
     * notification filter ([EventGate]).
     *
     * Only [DeviceEventCategory.NOTIFICATION] sets it. It is a field rather than a read of
     * `payload["package"]` because the gate must not depend on a payload key it does not own: a
     * renamed key would turn a filter into a no-op, and a no-op filter is a privacy decision the
     * org made and the device silently ignored.
     */
    val subjectPackage: String? = null,
) {
    /** [occurredAtMillis] as the RFC3339 string the `occurred_at` field carries. */
    val occurredAt: String get() = Instant.ofEpochMilli(occurredAtMillis).toString()
}

/**
 * One category's stream of events.
 *
 * A source that cannot observe its category — the permission is not granted, the OS refused the
 * registration — reports NOTHING and says so once in the log. It never throws and never retries:
 * a phone whose holder declined SMS access is not a broken phone, and a retry loop around a
 * permission that only a human can grant is a battery cost with no outcome.
 */
interface DeviceEventSource {
    /** The category every event from [events] carries. */
    val category: DeviceEventCategory

    /**
     * Events as they happen, for as long as the returned flow is collected.
     *
     * Collection is what registers with the OS; cancelling it is what unregisters. That ties every
     * receiver and callback to the life of ONE connector socket, which is the same discipline
     * [com.danielealbano.androidremotecontrolmcp.services.connector.ScreenLockMonitor] follows and
     * for the same reason: a registration that outlived the socket would leak one per reconnect.
     */
    fun events(): Flow<DeviceEvent>
}
