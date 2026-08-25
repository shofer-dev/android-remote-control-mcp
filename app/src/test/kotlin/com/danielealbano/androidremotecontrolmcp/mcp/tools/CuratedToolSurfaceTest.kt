package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the §4 curated tool surface: the allowlist is exactly the intended set, groups are
 * read/write per the §4 table, and folding the allowlist onto a config enables ONLY the curated
 * tools — so `tools/list` returns the curated set and every deliberately-excluded tool
 * (file/camera/clipboard/location-as-a-tool/raw-intent and the granular variants) is off.
 */
class CuratedToolSurfaceTest {
    private val expectedCurated =
        setOf(
            "get_screen_state",
            "find_nodes",
            "list_apps",
            "notification_list",
            "get_sim_info",
            "tap_node",
            "tap",
            "long_press",
            "swipe",
            "scroll",
            "press_key",
            "type_append_text",
            "open_app",
            "close_app",
            "open_uri",
            "notification_dismiss",
        )

    private val excludedSample =
        listOf(
            "get_location",
            "send_intent",
            "read_file",
            "write_file",
            "delete_file",
            "take_camera_photo",
            "save_camera_video",
            "set_clipboard",
            "get_clipboard",
            "double_tap",
            "pinch",
            "custom_gesture",
            "notification_reply",
            "notification_action",
            "get_node_details",
            "click_node",
            "scroll_to_node",
            "press_home",
            "press_back",
            "wait_for_node",
            "wait_for_idle",
            "get_device_logs",
            "share_file_via_web",
        )

    @Test
    fun `allowlist is exactly the intended curated set`() {
        assertEquals(expectedCurated, CuratedToolSurface.ALLOWLIST)
    }

    @Test
    fun `groups cover every allowlisted tool`() {
        assertEquals(CuratedToolSurface.ALLOWLIST, CuratedToolSurface.GROUPS.keys)
    }

    @Test
    fun `read and write groups match the section 4 table`() {
        val reads = setOf("get_screen_state", "find_nodes", "list_apps", "notification_list", "get_sim_info")
        reads.forEach { assertEquals(CuratedToolSurface.ToolGroup.READ, CuratedToolSurface.groupOf(it), it) }
        (expectedCurated - reads).forEach {
            assertEquals(CuratedToolSurface.ToolGroup.WRITE, CuratedToolSurface.groupOf(it), it)
        }
    }

    @Test
    fun `folding the allowlist enables only curated tools`() {
        val curated = ToolPermissionsConfig().intersectAllowed(CuratedToolSurface.ALLOWLIST)
        expectedCurated.forEach { assertTrue(curated.isToolEnabled(it), "$it should be enabled") }
        excludedSample.forEach { assertFalse(curated.isToolEnabled(it), "$it should be disabled") }
    }

    @Test
    fun `a runtime disable subtracts further from the curated set`() {
        val curated =
            ToolPermissionsConfig(disabledTools = setOf("open_app"))
                .intersectAllowed(CuratedToolSurface.ALLOWLIST)
        assertFalse(curated.isToolEnabled("open_app"))
        assertTrue(curated.isToolEnabled("tap"))
    }

    @Test
    fun `a runtime allowlist cannot widen past the curated floor`() {
        val curated =
            ToolPermissionsConfig(allowedTools = setOf("get_location", "tap"))
                .intersectAllowed(CuratedToolSurface.ALLOWLIST)
        // get_location is not curated, so the intersection drops it; tap survives.
        assertFalse(curated.isToolEnabled("get_location"))
        assertTrue(curated.isToolEnabled("tap"))
    }
}
