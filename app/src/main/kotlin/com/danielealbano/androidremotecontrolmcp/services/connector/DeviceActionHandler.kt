package com.danielealbano.androidremotecontrolmcp.services.connector

import kotlinx.serialization.json.JsonElement

/**
 * The seam for the four device-action frames the gateway can push on the same socket:
 * `lock`, `wipe`, `locate`, `ring` (wire spec §6). `pause`/`resume` are gateway-local and
 * never arrive, so they are not part of this contract.
 *
 * The production implementation is [PlatformDeviceActionHandler]: DeviceAdminReceiver-backed
 * executors — lock/wipe via `DevicePolicyManager`, locate via the flavor-neutral
 * `LocationProvider`, ring via `AudioManager`. Keeping the seam as an interface means the
 * connector's frame routing does not depend on how an action is carried out.
 *
 * The gateway does not correlate or await an `action_result` — it logs it (wire spec §6.3) —
 * but sending one is the declared contract, so a handler returns an [ActionOutcome] that the
 * connector renders into the `action_result` frame.
 */
interface DeviceActionHandler {
    /**
     * Executes a device action. [action] is one of [protocol.ActionName]; [params] is the
     * caller-supplied, schema-less parameter object (may be null — no action defines a
     * params schema in the platform today, wire spec Q5). Never throws for a normal failure:
     * return [ActionOutcome.Failure] so the connector can report `action_result{error}`.
     */
    suspend fun execute(
        action: String,
        params: JsonElement?,
    ): ActionOutcome
}

/**
 * The result of a device action, rendered by the connector into an `action_result` frame:
 * [Success] → `{payload}`, [Failure] → `{error, details}`.
 */
sealed interface ActionOutcome {
    data class Success(
        val payload: JsonElement? = null,
    ) : ActionOutcome

    data class Failure(
        val error: String,
        val details: String? = null,
    ) : ActionOutcome
}
