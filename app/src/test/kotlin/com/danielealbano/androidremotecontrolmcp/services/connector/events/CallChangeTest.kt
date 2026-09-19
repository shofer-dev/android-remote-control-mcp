package com.danielealbano.androidremotecontrolmcp.services.connector.events

import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceCallState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The `call` category's emission rule, which costs an agent TURN when it is wrong.
 *
 * `call` is the one category declared `wake: true` (`docs/phone/device_events.md` §5), so a
 * delivery to a sleeping agent synthesizes a turn. The registration-time re-statement that
 * `CallStateListener` performs is therefore not a harmless duplicate: unfiltered it would wake the
 * phones agent once per reconnect, about a call that never happened.
 */
@DisplayName("CallChange")
class CallChangeTest {
    @Test
    fun `a first state of disconnected is not reported`() {
        assertFalse(CallChange.isReportable(null, DeviceCallState.DISCONNECTED))
    }

    @Test
    fun `a first state of ringing is reported`() {
        // A handset that is mid-call as the socket comes up is exactly what the platform wants.
        assertTrue(CallChange.isReportable(null, DeviceCallState.RINGING))
    }

    @Test
    fun `a first state of active is reported`() {
        assertTrue(CallChange.isReportable(null, DeviceCallState.ACTIVE))
    }

    @Test
    fun `a repeated state is not reported`() {
        assertFalse(CallChange.isReportable(DeviceCallState.RINGING, DeviceCallState.RINGING))
        assertFalse(CallChange.isReportable(DeviceCallState.DISCONNECTED, DeviceCallState.DISCONNECTED))
    }

    @Test
    fun `the whole ring-answer-hangup arc is reported`() {
        assertTrue(CallChange.isReportable(null, DeviceCallState.RINGING))
        assertTrue(CallChange.isReportable(DeviceCallState.RINGING, DeviceCallState.ACTIVE))
        assertTrue(CallChange.isReportable(DeviceCallState.ACTIVE, DeviceCallState.DISCONNECTED))
    }

    @Test
    fun `a call that rings and is never answered still reports its end`() {
        assertTrue(CallChange.isReportable(DeviceCallState.RINGING, DeviceCallState.DISCONNECTED))
    }
}
