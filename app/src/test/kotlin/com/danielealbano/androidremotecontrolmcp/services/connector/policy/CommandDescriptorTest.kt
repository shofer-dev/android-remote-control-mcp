package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import com.danielealbano.androidremotecontrolmcp.mcp.tools.CuratedToolSurface
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parsing the relayed MCP payload. The prefix is configuration-derived
 * (`android_` or `android_<slug>_`), so the enforcer resolves the tool by SUFFIX — and the
 * suffix match has to be longest-first or `tap` swallows `tap_node` and every gesture on a
 * node is evaluated as the wrong verb.
 */
class CommandDescriptorTest {
    private fun toolCall(
        name: String,
        arguments: Map<String, String> = emptyMap(),
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "tools/call")
        putJsonObject("params") {
            put("name", name)
            putJsonObject("arguments") {
                arguments.forEach { (k, v) -> put(k, v) }
            }
        }
    }

    @Test
    fun `an unprefixed curated name resolves`() {
        assertEquals("open_app", CommandDescriptor.baseNameOf("open_app"))
    }

    @Test
    fun `the default prefix is stripped`() {
        assertEquals("open_app", CommandDescriptor.baseNameOf("android_open_app"))
    }

    @Test
    fun `a device-slug prefix is stripped`() {
        assertEquals("open_app", CommandDescriptor.baseNameOf("android_pixel7_open_app"))
    }

    @Test
    fun `the longest curated suffix wins`() {
        assertEquals("tap_node", CommandDescriptor.baseNameOf("android_tap_node"))
        assertEquals("tap", CommandDescriptor.baseNameOf("android_tap"))
        assertEquals("notification_list", CommandDescriptor.baseNameOf("android_notification_list"))
        assertEquals("notification_dismiss", CommandDescriptor.baseNameOf("android_notification_dismiss"))
    }

    @Test
    fun `an uncurated tool resolves to no base name`() {
        assertNull(CommandDescriptor.baseNameOf("android_send_intent"))
        assertNull(CommandDescriptor.baseNameOf(""))
        assertNull(CommandDescriptor.baseNameOf(null))
    }

    @Test
    fun `a tools call is recognised and its group resolved`() {
        val command = CommandDescriptor.parse(toolCall("android_tap", mapOf("x" to "1")))
        assertTrue(command.isToolCall)
        assertEquals(CuratedToolSurface.ToolGroup.WRITE, command.group)
    }

    @Test
    fun `a read tool resolves to the read group`() {
        val command = CommandDescriptor.parse(toolCall("android_get_screen_state"))
        assertEquals(CuratedToolSurface.ToolGroup.READ, command.group)
    }

    @Test
    fun `session methods are not tool calls`() {
        val initialize =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
            }
        assertFalse(CommandDescriptor.parse(initialize).isToolCall)
    }

    @Test
    fun `the launch target package is read from the arguments`() {
        val command = CommandDescriptor.parse(toolCall("android_open_app", mapOf("package_id" to "com.example.notes")))
        assertEquals("com.example.notes", command.targetPackage)
    }

    @Test
    fun `only a launch names a target package`() {
        val command = CommandDescriptor.parse(toolCall("android_tap", mapOf("package_id" to "com.example.notes")))
        assertNull(command.targetPackage)
    }

    @Test
    fun `the open_uri target is read from the arguments`() {
        val command = CommandDescriptor.parse(toolCall("android_open_uri", mapOf("uri" to "https://example.com")))
        assertEquals("https://example.com", command.targetUri)
    }

    @Test
    fun `a malformed payload parses to an empty descriptor instead of throwing`() {
        assertFalse(CommandDescriptor.parse(null).isToolCall)
        assertFalse(CommandDescriptor.parse(JsonNull).isToolCall)
        val noParams =
            buildJsonObject {
                put("method", "tools/call")
            }
        val command = CommandDescriptor.parse(noParams)
        assertTrue(command.isToolCall)
        assertNull(command.toolBaseName)
        assertNull(command.targetPackage)
    }
}
