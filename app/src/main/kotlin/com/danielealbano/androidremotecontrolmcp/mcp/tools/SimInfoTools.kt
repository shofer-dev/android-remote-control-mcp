// One handler + its register free-function, grouped as *Tools.kt like the rest of the surface
// (LocationTools.kt is the single-handler precedent).
@file:Suppress("MatchingDeclarationName")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.services.sim.SimInfoReader
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * MCP tool `get_sim_info` — reads the tethered SIM's own identity (number, ICCID, carrier, sub id).
 *
 * This is the reliable rung for provenance-verifying a racked SIM's MSISDN without SMS. The host's
 * `read_sim_number` action calls it as the TOP of its ladder, above the two adb rungs that fail on
 * a retail user build (`content query .../siminfo` needs privileged phone state; `service call
 * iphonesubinfo` has a build-specific transaction index).
 *
 * READ-only and returns PLAIN JSON (not the untrusted-wrapped text the perception tools use),
 * because the consumer is phone-service parsing structured fields, not an agent reading prose — a
 * warning banner would break `JSON.parse`. The result is a closed set of typed statuses
 * ([com.danielealbano.androidremotecontrolmcp.services.sim.SimInfoResult]); every one is a normal
 * return, never an MCP error, so the host branches on `status` rather than on a thrown failure:
 *
 * - `ok` — one entry per active SIM under `subscriptions[]`; each has `subscription_id`,
 *   `carrier_name`, `iccid`, `number` (nullable), and `number_status`.
 * - `number-not-provisioned-on-sim` (per subscription) — the SIM is fine but the carrier never
 *   wrote the MSISDN; this is the case that legitimately falls back to typed+SMS.
 * - `permission-not-granted` — `READ_PHONE_NUMBERS` is not held; re-run the provisioning grant.
 * - `no-active-sim` — no SIM / modem off.
 */
class GetSimInfoHandler(
    private val simInfoReader: SimInfoReader,
) {
    @Suppress("UnusedParameter")
    fun execute(arguments: JsonObject?): CallToolResult = McpToolUtils.textResult(simInfoReader.read().toJson())

    fun register(
        server: Server,
        toolNamePrefix: String,
    ) {
        server.addTool(
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reads this device's own SIM identity: phone number (MSISDN), ICCID, carrier " +
                    "name, and subscription id, for every active SIM. Returns JSON with a 'status' " +
                    "field: 'ok' with a 'subscriptions' array; 'permission-not-granted'; or " +
                    "'no-active-sim'. When a SIM carries no number (the carrier did not program it) " +
                    "the entry's 'number' is null and 'number_status' is " +
                    "'number-not-provisioned-on-sim' — this is a normal outcome, not an error.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = listOf(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_sim_info"
    }
}

/** Registers the SIM-info tool with the given [Server]. */
fun registerSimInfoTools(
    server: Server,
    simInfoReader: SimInfoReader,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(GetSimInfoHandler.TOOL_NAME)) {
        GetSimInfoHandler(simInfoReader).register(server, toolNamePrefix)
    }
}
