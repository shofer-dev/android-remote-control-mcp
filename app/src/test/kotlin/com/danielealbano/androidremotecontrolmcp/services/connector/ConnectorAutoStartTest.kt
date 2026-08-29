package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The self-heal decision, tested as a matrix — this is the one rule all three revive paths (boot,
 * app-foreground, watchdog) share, so a change here changes every one of them at once and each
 * clause has to be pinned individually.
 */
@DisplayName("ConnectorAutoStart")
class ConnectorAutoStartTest {
    @Nested
    @DisplayName("shouldRun")
    inner class ShouldRun {
        @Test
        fun `an enrolled auto-start device with an edge host should run`() {
            assertTrue(ConnectorAutoStart.shouldRun(ENROLLED))
        }

        @Test
        fun `a gateway url alone is a dial target`() {
            // The in-cluster (emulated device) path configures gateway_url and no edge host. It
            // must self-heal exactly like a tethered phone does.
            val config =
                ConnectorConfig(
                    gatewayUrl = "ws://phone-gateway.justceo.svc.cluster.local:8025/ws/phone",
                    phoneId = PHONE_ID,
                    autoStart = true,
                )

            assertTrue(ConnectorAutoStart.shouldRun(config))
        }

        @Test
        fun `an unspent pairing code counts as a credential`() {
            val config = ENROLLED.copy(phoneId = "", enrolmentCode = "PAIR-1234")

            assertTrue(ConnectorAutoStart.shouldRun(config))
        }

        @Test
        fun `auto-start off means stay down`() {
            assertFalse(ConnectorAutoStart.shouldRun(ENROLLED.copy(autoStart = false)))
        }

        @Test
        fun `an explicit stop vetoes auto-start`() {
            assertFalse(ConnectorAutoStart.shouldRun(ENROLLED.copy(stoppedByUser = true)))
        }

        @Test
        fun `no dial target means there is nothing to start`() {
            assertFalse(ConnectorAutoStart.shouldRun(ENROLLED.copy(edgeHost = "", gatewayUrl = "")))
        }

        @Test
        fun `neither a device id nor a code means there is nothing to attach with`() {
            assertFalse(ConnectorAutoStart.shouldRun(ENROLLED.copy(phoneId = "", enrolmentCode = "")))
        }

        @Test
        fun `a blank pairing code is not a credential`() {
            assertFalse(ConnectorAutoStart.shouldRun(ENROLLED.copy(phoneId = "", enrolmentCode = "   ")))
        }

        @Test
        fun `the default configuration should not run`() {
            assertFalse(ConnectorAutoStart.shouldRun(ConnectorConfig()))
        }
    }

    @Nested
    @DisplayName("shouldStart")
    inner class ShouldStart {
        @Test
        fun `a wanted connector that is down should be started`() {
            assertTrue(ConnectorAutoStart.shouldStart(ENROLLED, isRunning = false))
        }

        @Test
        fun `a wanted connector that is already up is left alone`() {
            assertFalse(ConnectorAutoStart.shouldStart(ENROLLED, isRunning = true))
        }

        @Test
        fun `an unwanted connector is never started, running or not`() {
            val vetoed = ENROLLED.copy(stoppedByUser = true)

            assertFalse(ConnectorAutoStart.shouldStart(vetoed, isRunning = false))
            assertFalse(ConnectorAutoStart.shouldStart(vetoed, isRunning = true))
        }
    }

    private companion object {
        const val PHONE_ID = "7f3ab21c-9d44-4a1e-8f0b-2c5d6e7a8b90"

        val ENROLLED =
            ConnectorConfig(
                edgeHost = "phones.justceo.ai",
                phoneId = PHONE_ID,
                autoStart = true,
            )
    }
}
