package com.danielealbano.androidremotecontrolmcp.services.connector.protocol

/**
 * The device-event vocabulary, mirroring `phone-gateway/internal/protocol/deviceevent.go`.
 *
 * A CATEGORY is the unit of everything on this plane (`docs/phone/device_events.md` §1): of the
 * holder's consent, of the platform's filtering, of the bus topic's address and of the agent's
 * wake policy. One spelling travels from the switch in this app's settings to the subscriber's
 * selector, so a reader can follow `call` through every layer by its name — which is why the
 * enum carries its [wire] spelling rather than relying on [name] being lowercased somewhere.
 *
 * The set is FIVE from the start and closed: a category this build invents would be answered
 * [WireError.UNKNOWN_EVENT_CATEGORY] by the gateway, because the gateway is always the older half
 * of the pair (the platform ships the APK).
 */
enum class DeviceEventCategory(
    val wire: String,
) {
    /** An app posted a notification. Payload `{package, app_name, title, text, posted_at, key}`. */
    NOTIFICATION("notification"),

    /** The handset's call state moved. Payload `{state, number}`. */
    CALL("call"),

    /** An SMS arrived. Payload `{from, body, received_at}`. */
    SMS("sms"),

    /** The default network changed. Payload `{transport, online}`, on CHANGE only. */
    CONNECTIVITY("connectivity"),

    /** The battery crossed a decade, or charging began or ended. Payload `{level, charging}`. */
    BATTERY("battery"),
    ;

    companion object {
        /**
         * The category with this wire spelling, or null.
         *
         * Null rather than an exception because the caller is always reading data from outside this
         * app — a policy snapshot's per-category map, a persisted config written by an older build
         * — and an unknown key there is something to IGNORE, never something to crash on.
         */
        fun fromWire(wire: String): DeviceEventCategory? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The `call` category's state vocabulary, deliberately the same three words the host telephony leg
 * uses (`protocol.TelephonyEventRinging` / `…Active` / `…Disconnected`), so a reader following a
 * call through the platform never has to learn a second spelling for the same edge.
 *
 * There is no `dialing` member on this leg: the connector OBSERVES a call it did not place, and
 * `TelephonyCallback.CallStateListener` reports only RINGING / OFFHOOK / IDLE.
 */
object DeviceCallState {
    const val RINGING = "ringing"
    const val ACTIVE = "active"
    const val DISCONNECTED = "disconnected"
}

/**
 * The `connectivity` category's transport vocabulary.
 *
 * [NONE] is a POSITIVE statement that the handset holds no default network — not an absent field —
 * which is what lets a subscriber tell "went offline" from "never reported".
 */
object ConnectivityTransport {
    const val WIFI = "wifi"
    const val CELLULAR = "cellular"
    const val NONE = "none"
}
