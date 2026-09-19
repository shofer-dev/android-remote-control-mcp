package com.danielealbano.androidremotecontrolmcp.services.connector.events

import android.os.BatteryManager
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectivityTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The CHANGE-ONLY rule, which is the difference between a reporting plane and a firehose.
 *
 * `ACTION_BATTERY_CHANGED` fires many times a minute on a charging phone and
 * `onCapabilitiesChanged` fires on every signal wobble, so every one of these cases is a broadcast
 * that really happens and really must NOT become an event — the gateway's per-device token bucket
 * would otherwise shed the reports that mattered (`docs/phone/device_events.md` §3, §4).
 */
@DisplayName("device-event change gates")
class StateChangeTest {
    @Nested
    @DisplayName("battery")
    inner class Battery {
        @Test
        fun `the first reading is reported`() {
            // Not a timer and not noise: it is the change from "the platform knows nothing about
            // this handset's battery" to a known value, and on an idle phone it happens once per
            // attach and never again.
            assertTrue(BatteryChange.isReportable(null, BatteryState(73, charging = false)))
        }

        @Test
        fun `a level moving inside its decade is not reported`() {
            val previous = BatteryState(79, charging = false)
            assertFalse(BatteryChange.isReportable(previous, BatteryState(78, charging = false)))
            assertFalse(BatteryChange.isReportable(previous, BatteryState(70, charging = false)))
        }

        @Test
        fun `a level crossing a decade boundary is reported`() {
            assertTrue(BatteryChange.isReportable(BatteryState(70, false), BatteryState(69, false)))
            assertTrue(BatteryChange.isReportable(BatteryState(69, false), BatteryState(70, false)))
        }

        @Test
        fun `reaching a full charge is its own decade`() {
            // 100 gets bucket 10 while 99 gets bucket 9, so "it finished charging" is an edge a
            // rack operator sees. Folding 100 into the nineties would lose exactly that.
            assertEquals(10, BatteryChange.decade(100))
            assertEquals(9, BatteryChange.decade(99))
            assertTrue(BatteryChange.isReportable(BatteryState(99, true), BatteryState(100, true)))
        }

        @Test
        fun `charging beginning or ending is reported at any level`() {
            assertTrue(BatteryChange.isReportable(BatteryState(55, false), BatteryState(55, true)))
            assertTrue(BatteryChange.isReportable(BatteryState(55, true), BatteryState(55, false)))
        }

        @Test
        fun `an identical repeated reading is not reported`() {
            val state = BatteryState(55, charging = true)
            assertFalse(BatteryChange.isReportable(state, state))
        }

        @Test
        fun `the level is a percentage of the reported scale, not of 100`() {
            // An OEM fuel gauge counting in tenths reports level=500 scale=1000 for half charge.
            assertEquals(50, BatteryChange.percentage(level = 500, scale = 1000))
            assertEquals(50, BatteryChange.percentage(level = 50, scale = 100))
        }

        @Test
        fun `an unusable reading is nothing, never zero per cent`() {
            // 0% is the reading somebody acts on, so a missing extra must not impersonate it.
            assertNull(BatteryChange.percentage(level = -1, scale = 100))
            assertNull(BatteryChange.percentage(level = 50, scale = 0))
            assertNull(BatteryChange.percentage(level = 50, scale = -1))
        }

        @Test
        fun `a full battery on the charger still counts as charging`() {
            // A topped-up phone reports FULL, not CHARGING. Reading that literally would emit
            // "charging ended" for a handset nobody unplugged.
            assertTrue(BatteryChange.isOnPower(BatteryManager.BATTERY_STATUS_FULL))
            assertTrue(BatteryChange.isOnPower(BatteryManager.BATTERY_STATUS_CHARGING))
            assertFalse(BatteryChange.isOnPower(BatteryManager.BATTERY_STATUS_DISCHARGING))
            assertFalse(BatteryChange.isOnPower(BatteryManager.BATTERY_STATUS_NOT_CHARGING))
            assertFalse(BatteryChange.isOnPower(BatteryManager.BATTERY_STATUS_UNKNOWN))
        }
    }

    @Nested
    @DisplayName("connectivity")
    inner class Connectivity {
        private val wifiOnline = ConnectivityState(ConnectivityTransport.WIFI, online = true)

        @Test
        fun `the first reading is reported`() {
            assertTrue(ConnectivityChange.isReportable(null, wifiOnline))
        }

        @Test
        fun `an identical repeated reading is not reported`() {
            assertFalse(ConnectivityChange.isReportable(wifiOnline, wifiOnline))
        }

        @Test
        fun `a transport switch is reported`() {
            val cellular = ConnectivityState(ConnectivityTransport.CELLULAR, online = true)
            assertTrue(ConnectivityChange.isReportable(wifiOnline, cellular))
        }

        @Test
        fun `losing validation without changing transport is reported`() {
            // The captive-portal case: still associated to the same Wi-Fi, reaching nothing. A gate
            // that only watched `transport` would never tell anyone this phone went dark.
            val wifiOffline = ConnectivityState(ConnectivityTransport.WIFI, online = false)
            assertTrue(ConnectivityChange.isReportable(wifiOnline, wifiOffline))
        }

        @Test
        fun `going offline is reported`() {
            val none = ConnectivityState(ConnectivityTransport.NONE, online = false)
            assertTrue(ConnectivityChange.isReportable(wifiOnline, none))
        }

        @Test
        fun `wifi wins over cellular when a phone holds both`() {
            // A phone on Wi-Fi with a live SIM carries both transports on its default network; the
            // one carrying the traffic is the one to report.
            assertEquals(
                ConnectivityTransport.WIFI,
                ConnectivityChange.transportOf(hasWifi = true, hasCellular = true),
            )
        }

        @Test
        fun `a transport with no word in the vocabulary is none, never a new spelling`() {
            // Ethernet on a dock, a VPN whose underlying link is hidden. The gateway gates this
            // value against a closed set, so a fourth word would be refused outright.
            assertEquals(
                ConnectivityTransport.NONE,
                ConnectivityChange.transportOf(hasWifi = false, hasCellular = false),
            )
            assertEquals(
                ConnectivityTransport.CELLULAR,
                ConnectivityChange.transportOf(hasWifi = false, hasCellular = true),
            )
        }
    }
}
