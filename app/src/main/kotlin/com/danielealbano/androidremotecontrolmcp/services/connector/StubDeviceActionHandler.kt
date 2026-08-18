package com.danielealbano.androidremotecontrolmcp.services.connector

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ActionName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wave-2 placeholder [DeviceActionHandler]: it logs the action and reports a `not-applicable`
 * failure for the destructive/stateful verbs, so the wire path (action → action_result) is
 * exercised end to end without a DeviceAdminReceiver existing yet. Wave 3 swaps this binding
 * for the real executors.
 *
 * `lock`/`wipe`/`locate`/`ring` are all recognised (so an unknown action is distinguishable
 * from an unimplemented one), but none is actually performed here — deliberately, since
 * wiping or locking a device from a stub would be the worst possible bug to ship.
 */
@Singleton
class StubDeviceActionHandler
    @Inject
    constructor() : DeviceActionHandler {
        override suspend fun execute(
            action: String,
            params: JsonElement?,
        ): ActionOutcome =
            when (action) {
                ActionName.LOCK, ActionName.WIPE, ActionName.LOCATE, ActionName.RING -> {
                    Log.i(TAG, "Device action '$action' received (stub; not executed until Wave 3). params=$params")
                    ActionOutcome.Failure(
                        error = "not-implemented",
                        details = "device action '$action' is not implemented in this build",
                    )
                }

                else -> {
                    Log.w(TAG, "Unknown device action '$action'")
                    ActionOutcome.Failure(
                        error = "unknown-action",
                        details = "unknown device action '$action'",
                    )
                }
            }

        companion object {
            private const val TAG = "MCP:DeviceAction"

            /** Convenience builder Wave 3 executors can reuse for a structured success payload. */
            fun okPayload(vararg pairs: Pair<String, String>): JsonElement =
                buildJsonObject {
                    for ((k, v) in pairs) put(k, JsonPrimitive(v))
                }
        }
    }
