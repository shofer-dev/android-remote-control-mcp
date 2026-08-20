package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectorJson
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `policy` frame's wire shape, pinned against the JSON `device-gateway` actually emits
 * (`internal/protocol` `PolicySnapshot` + `internal/policy`). Field spelling is the contract:
 * a renamed key does not fail, it silently decodes to a default — and a default here means a
 * device that thinks it may drive anything at any hour.
 */
class PolicyFrameSerializationTest {
    private val gatewayFrame =
        """
        {
          "type": "policy",
          "policy": {
            "version": "3f1a",
            "issued_at": "2026-08-20T09:00:00Z",
            "active_hours": "08:00-22:00",
            "drivable_app_posture": "allowlist-only",
            "drivable_apps": ["com.example.notes", "com.example.mail"],
            "rate_limit": { "commands": 120, "window_seconds": 60 },
            "paused": true
          }
        }
        """.trimIndent()

    @Test
    fun `the gateway's policy frame decodes field for field`() {
        val frame = ConnectorJson.decodeFromString(Frame.serializer(), gatewayFrame)
        assertEquals(FrameType.POLICY, frame.type)
        val policy = frame.policy
        assertNotNull(policy)
        requireNotNull(policy)
        assertEquals("3f1a", policy.version)
        assertEquals("2026-08-20T09:00:00Z", policy.issuedAt)
        assertEquals("08:00-22:00", policy.activeHours)
        assertEquals(DevicePolicy.Posture.ALLOWLIST_ONLY, policy.drivableAppPosture)
        assertEquals(listOf("com.example.notes", "com.example.mail"), policy.drivableApps)
        assertEquals(120, policy.rateLimit.commands)
        assertEquals(60, policy.rateLimit.windowSeconds)
        assertTrue(policy.paused)
    }

    @Test
    fun `a snapshot with an empty allowlist decodes to an empty list`() {
        val json =
            """
            {"type":"policy","policy":{"version":"a","issued_at":"t","active_hours":"",
            "drivable_app_posture":"device-list","drivable_apps":[],
            "rate_limit":{"commands":0,"window_seconds":0},"paused":false}}
            """.trimIndent()
        val policy = ConnectorJson.decodeFromString(Frame.serializer(), json).policy
        requireNotNull(policy)
        assertTrue(policy.drivableApps.isEmpty())
        assertFalse(policy.paused)
    }

    @Test
    fun `an unknown snapshot field is ignored rather than fatal`() {
        // The envelope evolves with the host leg; a device must not refuse to attach because
        // the gateway added a field it does not model yet.
        val json =
            """
            {"type":"policy","policy":{"version":"a","issued_at":"t","active_hours":"08:00-22:00",
            "drivable_app_posture":"device-list","drivable_apps":[],
            "rate_limit":{"commands":1,"window_seconds":1},"paused":false,"something_new":42}}
            """.trimIndent()
        val policy = ConnectorJson.decodeFromString(Frame.serializer(), json).policy
        requireNotNull(policy)
        assertEquals("08:00-22:00", policy.activeHours)
    }

    @Test
    fun `a non-policy frame carries no snapshot and emits no policy key`() {
        val encoded = ConnectorJson.encodeToString(Frame.serializer(), Frame(type = FrameType.PING))
        assertFalse(encoded.contains("policy"))
    }
}
