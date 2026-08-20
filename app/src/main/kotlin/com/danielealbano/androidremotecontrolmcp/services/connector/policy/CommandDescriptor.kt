package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import com.danielealbano.androidremotecontrolmcp.mcp.tools.CuratedToolSurface
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the enforcer needs to know about one relayed `cmd` payload, extracted without decoding
 * the MCP message properly. Pure and total: every failure yields a descriptor rather than an
 * exception, so a malformed payload takes the same path as a well-formed one and is refused
 * (or passed to the SDK, which will produce the JSON-RPC error) rather than crashing the
 * connector's frame loop.
 *
 * The **tool base name** is the interesting part. Tool names on the wire carry a
 * configuration-derived prefix — `android_` or `android_<slug>_`
 * (`McpToolUtils.buildToolNamePrefix`) — so the enforcer cannot compare against a literal.
 * It resolves the name by matching the LONGEST curated base name that the tool name ends
 * with, which is what keeps `tap` from swallowing `tap_node` and `notification_list` from
 * colliding with `notification_dismiss`. A name matching no curated base resolves to null;
 * that is not an error here (the tool is not registered, so the SDK will refuse it), it just
 * means the group-based rules have nothing to key on.
 */
data class CommandDescriptor(
    /** The JSON-RPC method, e.g. `tools/call`, `tools/list`, `initialize`. */
    val method: String?,
    /** The full, prefixed tool name from `params.name`, when the method is `tools/call`. */
    val toolName: String?,
    /** The curated base name (`open_app`, `tap_node`, …), or null if it is not curated. */
    val toolBaseName: String?,
    /** `params.arguments`, when present. */
    val arguments: JsonObject?,
) {
    /** True when this payload actually asks the device to do something. */
    val isToolCall: Boolean get() = method == METHOD_TOOLS_CALL

    /** The curated group of the tool, or null when the tool is not curated. */
    val group: CuratedToolSurface.ToolGroup? get() = toolBaseName?.let { CuratedToolSurface.groupOf(it) }

    /**
     * The package this call targets, when the call names one: `open_app`'s `package_id`. Used
     * by the allowlist and the structural denylist, which must refuse a LAUNCH by its target
     * rather than by whatever happens to be in the foreground when it arrives.
     */
    val targetPackage: String?
        get() =
            if (toolBaseName == CuratedToolSurface.LAUNCH_APP) {
                stringArgument("package_id")
            } else {
                null
            }

    /**
     * The URI this call opens, when it opens one (`open_uri`'s `uri`). A deep link can launch
     * an arbitrary app, so it is inspected for a denied target the same way a launch is.
     */
    val targetUri: String?
        get() =
            if (toolBaseName == CuratedToolSurface.OPEN_URI) {
                stringArgument("uri")
            } else {
                null
            }

    /** Reads one non-blank string argument by name, or null when it is absent or not a string. */
    private fun stringArgument(name: String): String? {
        val raw = arguments?.get(name) ?: return null
        val text = raw.stringOrNull()?.trim().orEmpty()
        return text.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val METHOD_TOOLS_CALL = "tools/call"

        /**
         * Curated base names ordered longest-first, so suffix matching resolves the most
         * specific name. Computed once — the curated surface is a compile-time constant.
         */
        private val BASE_NAMES_LONGEST_FIRST: List<String> =
            CuratedToolSurface.ALLOWLIST.sortedByDescending { it.length }

        /** Resolves a prefixed tool name to its curated base name, or null. */
        fun baseNameOf(toolName: String?): String? {
            val name = toolName?.trim().orEmpty()
            if (name.isEmpty()) return null
            return BASE_NAMES_LONGEST_FIRST.firstOrNull { base ->
                name == base || name.endsWith("_$base")
            }
        }

        /** Parses a relay `cmd` payload. Never throws. */
        fun parse(payload: JsonElement?): CommandDescriptor {
            val root = payload as? JsonObject ?: return EMPTY
            val method = root["method"]?.stringOrNull()
            val params = root["params"] as? JsonObject
            val toolName = params?.get("name")?.stringOrNull()
            return CommandDescriptor(
                method = method,
                toolName = toolName,
                toolBaseName = baseNameOf(toolName),
                arguments = params?.get("arguments") as? JsonObject,
            )
        }

        private val EMPTY = CommandDescriptor(null, null, null, null)

        /** Reads a JSON value as a string, or null when it is not a string primitive. */
        private fun JsonElement.stringOrNull(): String? =
            runCatching {
                val primitive = jsonPrimitive
                if (primitive.isString) primitive.content else null
            }.getOrNull()
    }
}
