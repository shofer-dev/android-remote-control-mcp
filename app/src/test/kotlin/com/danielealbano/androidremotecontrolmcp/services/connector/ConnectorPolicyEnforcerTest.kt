package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.data.model.ActiveHours
import com.danielealbano.androidremotecontrolmcp.data.model.DevicePolicy
import com.danielealbano.androidremotecontrolmcp.mcp.tools.CuratedToolSurface
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the pure last-hop policy decision (§6.4): active-hours refusal of acting tools, the
 * drivable-app allowlist for launch, and the fail-closed default for an uncurated tool.
 */
class ConnectorPolicyEnforcerTest {
    private fun launchParams(pkg: String) = buildJsonObject { put("package_id", pkg) }

    @Test
    fun `read tool is allowed outside active hours`() {
        val policy = DevicePolicy(activeHours = ActiveHours(9 * 60, 17 * 60))
        val decision = ConnectorPolicyEnforcer.decide(CuratedToolSurface.GET_SCREEN_STATE, null, policy, 3 * 60)
        assertEquals(ConnectorPolicyEnforcer.Decision.Allow, decision)
    }

    @Test
    fun `write tool is refused outside active hours`() {
        val policy = DevicePolicy(activeHours = ActiveHours(9 * 60, 17 * 60))
        val decision = ConnectorPolicyEnforcer.decide(CuratedToolSurface.TAP, null, policy, 3 * 60)
        val deny = assertInstanceOf(ConnectorPolicyEnforcer.Decision.Deny::class.java, decision)
        assertEquals(ConnectorPolicyEnforcer.CODE_OUTSIDE_ACTIVE_HOURS, deny.code)
    }

    @Test
    fun `write tool is allowed inside active hours`() {
        val policy = DevicePolicy(activeHours = ActiveHours(9 * 60, 17 * 60))
        val decision = ConnectorPolicyEnforcer.decide(CuratedToolSurface.TAP, null, policy, 12 * 60)
        assertEquals(ConnectorPolicyEnforcer.Decision.Allow, decision)
    }

    @Test
    fun `null active hours means always active`() {
        val policy = DevicePolicy(activeHours = null)
        val decision = ConnectorPolicyEnforcer.decide(CuratedToolSurface.TAP, null, policy, 3 * 60)
        assertEquals(ConnectorPolicyEnforcer.Decision.Allow, decision)
    }

    @Test
    fun `launch of a non-allowlisted app is refused`() {
        val policy = DevicePolicy(drivableAppAllowlist = setOf("com.example.allowed"))
        val decision =
            ConnectorPolicyEnforcer.decide(
                CuratedToolSurface.OPEN_APP,
                launchParams("com.example.forbidden"),
                policy,
                12 * 60,
            )
        val deny = assertInstanceOf(ConnectorPolicyEnforcer.Decision.Deny::class.java, decision)
        assertEquals(ConnectorPolicyEnforcer.CODE_APP_NOT_ALLOWED, deny.code)
    }

    @Test
    fun `launch of an allowlisted app is allowed`() {
        val policy = DevicePolicy(drivableAppAllowlist = setOf("com.example.allowed"))
        val decision =
            ConnectorPolicyEnforcer.decide(
                CuratedToolSurface.OPEN_APP,
                launchParams("com.example.allowed"),
                policy,
                12 * 60,
            )
        assertEquals(ConnectorPolicyEnforcer.Decision.Allow, decision)
    }

    @Test
    fun `null allowlist means any app may launch`() {
        val policy = DevicePolicy(drivableAppAllowlist = null)
        val decision =
            ConnectorPolicyEnforcer.decide(CuratedToolSurface.OPEN_APP, launchParams("com.anything"), policy, 12 * 60)
        assertEquals(ConnectorPolicyEnforcer.Decision.Allow, decision)
    }

    @Test
    fun `unknown tool fails closed as an acting tool`() {
        val policy = DevicePolicy(activeHours = ActiveHours(9 * 60, 17 * 60))
        val decision = ConnectorPolicyEnforcer.decide("some_uncurated_tool", null, policy, 3 * 60)
        assertInstanceOf(ConnectorPolicyEnforcer.Decision.Deny::class.java, decision)
    }

    @Test
    fun `active hours contains handles a window that wraps midnight`() {
        val overnight = ActiveHours(22 * 60, 6 * 60)
        assertTrue(overnight.contains(23 * 60))
        assertTrue(overnight.contains(2 * 60))
        assertFalse(overnight.contains(12 * 60))
    }

    @Test
    fun `active hours with equal start and end is always active`() {
        val allDay = ActiveHours(0, 0)
        assertTrue(allDay.contains(0))
        assertTrue(allDay.contains(12 * 60))
        assertTrue(allDay.contains(23 * 60 + 59))
    }
}
