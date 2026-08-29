package com.danielealbano.androidremotecontrolmcp.services.connector

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.crypto.DeviceIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONE place a device's platform identity is destroyed.
 *
 * An identity is TWO durable things that must die together: the `phone_id`
 * ([com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig.phoneId]) and the
 * ed25519 keypair the attach challenge is signed with ([DeviceIdentity]). Clearing one without
 * the other leaves the phone in a state neither the app nor the platform has a name for — an id
 * with no key that can never attach, or a key bound at the platform to an id the phone has
 * forgotten. Two callers need this and neither may re-implement it:
 *
 * - [discardVoidIdentity], when the platform answers an attach with
 *   [com.danielealbano.androidremotecontrolmcp.services.connector.protocol.RefusalReason.UNKNOWN_DEVICE].
 *   The identity is void by definition — the platform has erased the record — so retrying under
 *   it is a loop with no exit, which is exactly what stranded a re-provisioned handset until its
 *   app data was cleared by hand.
 * - [unprovision], the holder's deliberate act on the phone.
 *
 * They differ in ONE thing, and it is the difference between self-heal and a deliberate reset:
 * the self-heal KEEPS an unspent enrolment code, because a code delivered while the dead id was
 * still held is precisely what should provision the phone again on the next dial. The holder's
 * unprovision spends nothing and keeps nothing.
 *
 * What SURVIVES both, deliberately: the dial target (edge host / gateway URL) and the auto-start
 * preference — how the phone is configured rather than who it is — and the platform's own device
 * record, which this app has no authority over and never touches.
 */
@Singleton
class ConnectorProvisioning
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val deviceIdentity: DeviceIdentity,
    ) {
        /**
         * Discards an identity the PLATFORM has told us does not exist. [details] is the gateway's
         * prose, logged so a support session can see what the platform actually said.
         */
        suspend fun discardVoidIdentity(details: String?) {
            Log.w(
                TAG,
                "The platform holds no record of this device (${details ?: "no details"}); discarding the " +
                    "stored identity so a pairing code can provision this phone again",
            )
            settingsRepository.clearConnectorIdentity()
            deviceIdentity.reset()
        }

        /** The holder's deliberate unprovision: identity, keypair and any unspent pairing code. */
        suspend fun unprovision() {
            Log.i(TAG, "Unprovisioning this device at the holder's request")
            settingsRepository.clearConnectorEnrolment()
            deviceIdentity.reset()
        }

        private companion object {
            const val TAG = "MCP:ConnectorProvisioning"
        }
    }
