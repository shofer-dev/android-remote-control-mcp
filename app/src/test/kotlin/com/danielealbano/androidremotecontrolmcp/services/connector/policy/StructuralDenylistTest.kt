package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two refusals no publication can widen. These assertions exist so a future change that
 * "simplifies" the denylist into the policy snapshot fails here first.
 */
class StructuralDenylistTest {
    private val own = "com.danielealbano.androidremotecontrolmcp"

    @Test
    fun `the OS Settings app is denied`() {
        assertTrue(StructuralDenylist.isDenied("com.android.settings", own))
        assertTrue(StructuralDenylist.isDenied("com.android.settings.intelligence", own))
        assertTrue(StructuralDenylist.isDenied("com.google.android.settings", own))
    }

    @Test
    fun `the system UI is denied`() {
        assertTrue(StructuralDenylist.isDenied("com.android.systemui", own))
    }

    @Test
    fun `this app's own package is denied`() {
        assertTrue(StructuralDenylist.isDenied(own, own))
    }

    @Test
    fun `a debug build's own package is denied by its own id, not by a hard-coded name`() {
        val debug = "$own.debug"
        assertTrue(StructuralDenylist.isDenied(debug, debug))
        // …and the release id is not denied when the debug build is running, which is what
        // proves the entry comes from BuildConfig rather than a literal.
        assertFalse(StructuralDenylist.isDenied(own, debug))
    }

    @Test
    fun `an OEM Settings package resolved at runtime is denied`() {
        val resolved = setOf("com.oneplus.settings")
        assertTrue(StructuralDenylist.isDenied("com.oneplus.settings", own, resolved))
        assertFalse(StructuralDenylist.isDenied("com.oneplus.settings", own))
    }

    @Test
    fun `an ordinary app is not denied`() {
        assertFalse(StructuralDenylist.isDenied("com.example.notes", own))
    }

    @Test
    fun `an absent package is not denied here`() {
        // "We could not tell which app is in the foreground" is a different failure with a
        // different answer; PolicyEnforcer refuses it as an unknown foreground.
        assertFalse(StructuralDenylist.isDenied(null, own))
        assertFalse(StructuralDenylist.isDenied("  ", own))
    }

    @Test
    fun `the denied set always contains the caller's own package`() {
        assertTrue(StructuralDenylist.denied(own).contains(own))
        assertTrue(StructuralDenylist.denied(own).containsAll(StructuralDenylist.AOSP_SETTINGS_PACKAGES))
    }
}
