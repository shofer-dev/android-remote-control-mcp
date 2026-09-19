// The CHANGE-ONLY rule for the two categories whose OS signal is continuous
// (`docs/phone/device_events.md` §3), extracted as pure data + pure predicates because it is the
// part that must be right.
//
// `ACTION_BATTERY_CHANGED` is broadcast every time any of a dozen fields moves — temperature,
// voltage, plugged state, a percentage point — which on a charging handset is many times a minute,
// for ever. A source that forwarded each one would turn a reporting plane into a telemetry firehose
// that the gateway's token bucket would then shed, so the phone would burn radio and the org would
// learn nothing it did not already know. The same is true of `onCapabilitiesChanged`, which fires
// on every link-speed and signal-strength wobble.
//
// So the rule is: emit when the state a SUBSCRIBER can act on changed, and never on a timer. There
// is no period here and there must not be one.

package com.danielealbano.androidremotecontrolmcp.services.connector.events

import android.os.BatteryManager
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectivityTransport

/** A battery reading as the plane reports it: `{level, charging}`. */
data class BatteryState(
    /** Charge as a whole percentage, 0-100. */
    val level: Int,
    val charging: Boolean,
)

/** The battery category's change rule. */
object BatteryChange {
    /**
     * The decade a level falls in — 0 for 0-9%, 9 for 90-99%, 10 for a full 100%.
     *
     * A full battery getting its own bucket is deliberate rather than an off-by-one: "it finished
     * charging" is exactly the edge somebody watching a rack wants, and folding 100 into the 90s
     * would lose it.
     */
    fun decade(level: Int): Int = level / DECADE

    /**
     * Whether [next] is worth an event given [previous].
     *
     * A null [previous] REPORTS. The first reading of a connection is a change — from "the platform
     * knows nothing about this handset's battery" to a known value — and it is the only way the
     * platform ever learns a level that then sits still for days. It costs one event per attach per
     * category, which is not a timer: an attached idle phone emits it once and is then silent.
     */
    fun isReportable(
        previous: BatteryState?,
        next: BatteryState,
    ): Boolean =
        previous == null ||
            previous.charging != next.charging ||
            decade(previous.level) != decade(next.level)

    /**
     * Charge as a percentage of [scale], or null when the broadcast named neither.
     *
     * `EXTRA_SCALE` is not always 100 — it is whatever unit the OEM's fuel gauge counts in — so the
     * division is the documented way to read `ACTION_BATTERY_CHANGED`, not a defensive flourish. A
     * scale of zero or less means the reading is unusable, and an unusable reading is reported as
     * NOTHING rather than as 0%: a phone that reports 0% is a phone somebody is about to go and
     * find.
     */
    fun percentage(
        level: Int,
        scale: Int,
    ): Int? = if (level < 0 || scale <= 0) null else (level * PERCENT / scale).coerceIn(0, PERCENT)

    /**
     * Whether a `BatteryManager.EXTRA_STATUS` value means the handset is on external power.
     *
     * [BatteryManager.BATTERY_STATUS_FULL] counts, deliberately: a phone that has topped up on its
     * charger reports FULL rather than CHARGING, and reading that literally would emit "charging
     * ended" for a handset nobody unplugged — which on a rack is exactly the wrong alarm.
     */
    fun isOnPower(status: Int): Boolean = status in ON_POWER_STATUSES

    private const val DECADE = 10
    private const val PERCENT = 100

    private val ON_POWER_STATUSES =
        setOf(
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
        )
}

/** A connectivity reading as the plane reports it: `{transport, online}`. */
data class ConnectivityState(
    /** One of [com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectivityTransport]. */
    val transport: String,
    val online: Boolean,
)

/** The connectivity category's change rule. */
object ConnectivityChange {
    /**
     * Whether [next] is worth an event given [previous] — true when EITHER field moved.
     *
     * `online` counts as well as `transport`, and that is not scope creep: a handset that keeps its
     * Wi-Fi association while the captive portal stops validating has gone offline without changing
     * transport, and a subscriber reading only transport changes would never hear about it. The
     * whole reported tuple is the state, so the whole tuple decides.
     *
     * A null [previous] reports, for the reason given on [BatteryChange.isReportable].
     */
    fun isReportable(
        previous: ConnectivityState?,
        next: ConnectivityState,
    ): Boolean = previous != next

    /**
     * The transport word for what a default network carries.
     *
     * Wi-Fi is tested FIRST because a phone on Wi-Fi with a live SIM holds both transports on the
     * default network, and the one carrying the traffic is the one to report. Anything else — an
     * Ethernet dock, a VPN whose underlying link is hidden — is [ConnectivityTransport.NONE]
     * rather than a fourth word, because the gateway gates this value against a closed vocabulary
     * and an invented spelling would be refused outright.
     */
    fun transportOf(
        hasWifi: Boolean,
        hasCellular: Boolean,
    ): String =
        when {
            hasWifi -> ConnectivityTransport.WIFI
            hasCellular -> ConnectivityTransport.CELLULAR
            else -> ConnectivityTransport.NONE
        }
}
