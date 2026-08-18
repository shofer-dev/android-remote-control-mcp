package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The per-tool / per-parameter kill switch applied at registration time.
 *
 * Two independent gates compose:
 * - [allowedTools] is a POSITIVE allowlist. When non-null a tool is registered only if its
 *   base name is in the set, so a tool the platform never sanctioned — including one a future
 *   upstream merge adds — can never slip through by default. `null` means "no allowlist", the
 *   pre-curation behaviour where every tool is enabled subject only to [disabledTools]. This is
 *   how the §4 curated surface is enforced as a hard floor at the registration site
 *   ([com.danielealbano.androidremotecontrolmcp.mcp.tools.CuratedToolSurface]), independent of
 *   whatever DataStore holds — a runtime config can subtract MORE but never re-add a
 *   non-curated tool.
 * - [disabledTools] / [disabledParams] are the existing NEGATIVE gates (turn a specific tool or
 *   parameter off). They still apply on top of the allowlist.
 */
data class ToolPermissionsConfig(
    val disabledTools: Set<String> = emptySet(),
    val disabledParams: Map<String, Set<String>> = emptyMap(),
    val allowedTools: Set<String>? = null,
) {
    fun isToolEnabled(toolName: String): Boolean {
        val allowed = allowedTools == null || toolName in allowedTools
        return allowed && toolName !in disabledTools
    }

    fun isParamEnabled(
        toolName: String,
        paramName: String,
    ): Boolean = paramName !in (disabledParams[toolName] ?: emptySet())

    /**
     * Intersects this config's allowlist with [allow], producing a config whose surface is at
     * most [allow]. Called by the tool-server factory to fold the §4 curated allowlist onto any
     * runtime config: a runtime allowlist (a future policy snapshot) can only narrow the set
     * further, never widen it past the curated floor.
     */
    fun intersectAllowed(allow: Set<String>): ToolPermissionsConfig {
        val narrowed = allowedTools?.intersect(allow) ?: allow
        return copy(allowedTools = narrowed)
    }

    fun toJson(): String =
        buildJsonObject {
            put("disabledTools", buildJsonArray { disabledTools.forEach { add(it) } })
            put(
                "disabledParams",
                buildJsonObject {
                    disabledParams.forEach { (tool, params) ->
                        put(tool, buildJsonArray { params.forEach { add(it) } })
                    }
                },
            )
            allowedTools?.let { allowed ->
                put("allowedTools", buildJsonArray { allowed.forEach { add(it) } })
            }
        }.toString()

    companion object {
        fun fromJson(json: String): ToolPermissionsConfig? =
            try {
                val obj = Json.parseToJsonElement(json).jsonObject
                val disabledTools =
                    obj["disabledTools"]
                        ?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.toSet()
                        ?: emptySet()
                val disabledParams =
                    obj["disabledParams"]
                        ?.jsonObject
                        ?.mapValues { (_, v) ->
                            v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                        }
                        ?: emptyMap()
                val allowedTools =
                    obj["allowedTools"]
                        ?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.toSet()
                ToolPermissionsConfig(
                    disabledTools = disabledTools,
                    disabledParams = disabledParams,
                    allowedTools = allowedTools,
                )
            } catch (_: kotlinx.serialization.SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }

        fun fromJsonOrDefault(json: String?): ToolPermissionsConfig =
            if (json == null) ToolPermissionsConfig() else fromJson(json) ?: ToolPermissionsConfig()
    }
}
