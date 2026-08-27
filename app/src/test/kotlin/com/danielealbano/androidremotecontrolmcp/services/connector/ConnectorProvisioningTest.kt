package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.crypto.DeviceIdentity
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The two ways a device's platform identity is destroyed ([ConnectorProvisioning]).
 *
 * Both must destroy BOTH halves — the stored device id and the keypair — because either half
 * surviving alone leaves the phone in a state with no meaning: an id that can never attach, or a
 * key the platform still has bound to an id the phone has forgotten. The difference between them
 * is the pairing code, and it is the whole difference between a self-heal and a reset.
 */
@DisplayName("ConnectorProvisioning")
class ConnectorProvisioningTest {
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val deviceIdentity = mockk<DeviceIdentity>(relaxed = true)
    private val provisioning = ConnectorProvisioning(settingsRepository, deviceIdentity)

    @Test
    fun `discarding a void identity clears the device id and the keypair`() =
        runTest {
            provisioning.discardVoidIdentity("credential-service refused: unknown-device")

            coVerify(exactly = 1) { settingsRepository.clearConnectorIdentity() }
            verify(exactly = 1) { deviceIdentity.reset() }
        }

    @Test
    fun `discarding a void identity KEEPS a pairing code, so the phone re-enrols on the next dial`() =
        runTest {
            // The incident this exists for: an operator deletes the record and delivers a fresh
            // code while the dead id is still held. Spending that code here would strand the
            // phone exactly as the old behaviour did.
            provisioning.discardVoidIdentity(null)

            coVerify(exactly = 0) { settingsRepository.clearConnectorEnrolment() }
        }

    @Test
    fun `unprovisioning clears the device id, the pairing code and the keypair`() =
        runTest {
            provisioning.unprovision()

            coVerify(exactly = 1) { settingsRepository.clearConnectorEnrolment() }
            verify(exactly = 1) { deviceIdentity.reset() }
        }

    @Test
    fun `unprovisioning does not touch the dial target or the auto-start preference`() =
        runTest {
            // How the phone is configured is not who it is: a rack operator re-pairing the same
            // handset must not have to retype the edge host.
            provisioning.unprovision()

            coVerify(exactly = 0) { settingsRepository.updateConnectorEdgeHost(any()) }
            coVerify(exactly = 0) { settingsRepository.updateConnectorGatewayUrl(any()) }
            coVerify(exactly = 0) { settingsRepository.updateConnectorAutoStart(any()) }
            coVerify(exactly = 0) { settingsRepository.updateConnectorStoppedByUser(any()) }
        }

    @Test
    fun `the persisted clear happens BEFORE the keypair is destroyed`() =
        runTest {
            // Order matters on a phone that can be killed between the two writes: an id with no
            // key can never attach and reads as a broken device, whereas a key with no id is
            // simply an unenrolled phone that will mint a fresh one at its next enrolment.
            provisioning.unprovision()

            coVerifyOrder {
                settingsRepository.clearConnectorEnrolment()
                deviceIdentity.reset()
            }
        }
}
