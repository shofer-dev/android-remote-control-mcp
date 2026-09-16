package com.danielealbano.androidremotecontrolmcp.services.vendor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The selection rule, and the null that keeps a dead button off the screen.
 *
 * Both halves are worth pinning because both were got wrong before the seam existed: the autostart
 * component was hard-coded to MIUI's, and the control that opened it was offered on every phone —
 * so a stock-Android holder pressed a button that resolved to nothing at all.
 */
@DisplayName("VendorProfiles")
class VendorProfilesTest {
    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { context.packageManager } returns packageManager
    }

    /** What a phone that ships the vendor's screen answers, whatever it calls itself. */
    private fun screenResolves() {
        every { packageManager.resolveActivity(any<Intent>(), any<Int>()) } returns mockk<ResolveInfo>()
    }

    /** What every phone without it answers — stock Android, and a Xiaomi that dropped the app. */
    private fun nothingResolves() {
        every { packageManager.resolveActivity(any<Intent>(), any<Int>()) } returns null
    }

    @Nested
    @DisplayName("selection")
    inner class Selection {
        @Test
        fun `a resolvable autostart screen selects the profile that names it`() {
            screenResolves()

            assertSame(MiuiVendorProfile, VendorProfiles.forDevice(context, "xiaomi"))
        }

        @Test
        fun `the manufacturer hint only orders the candidates, it does not decide`() {
            screenResolves()

            // A brand nobody listed, shipping a screen somebody did: still served.
            assertSame(MiuiVendorProfile, VendorProfiles.forDevice(context, "acme"))
        }

        @Test
        fun `a matching manufacturer with nothing resolving still falls to generic`() {
            nothingResolves()

            assertSame(GenericVendorProfile, VendorProfiles.forDevice(context, "xiaomi"))
        }

        @Test
        fun `an unknown manufacturer with nothing resolving falls to generic`() {
            nothingResolves()

            assertSame(GenericVendorProfile, VendorProfiles.forDevice(context, "google"))
        }

        @Test
        fun `an empty manufacturer is a hint like any other, not a failure`() {
            screenResolves()

            assertSame(MiuiVendorProfile, VendorProfiles.forDevice(context, ""))
        }

        @Test
        fun `forThisDevice reaches the same answer for the running build`() {
            nothingResolves()

            assertSame(GenericVendorProfile, VendorProfiles.forThisDevice(context))
        }

        @Test
        fun `the generic profile claims no vendor screen, which is an answer and not a gap`() {
            assertTrue(GenericVendorProfile.autostartComponents.isEmpty())
            assertTrue(GenericVendorProfile.manufacturers.isEmpty())
        }
    }

    @Nested
    @DisplayName("autostartIntent")
    inner class AutostartIntent {
        @Test
        fun `is an intent when the vendor screen resolves`() {
            screenResolves()

            assertNotNull(VendorProfiles.autostartIntent(context))
        }

        @Test
        fun `is null when no vendor screen resolves`() {
            nothingResolves()

            assertNull(VendorProfiles.autostartIntent(context))
        }
    }

    /**
     * The gate a surface offering the control asks. It is the card's whole decision: false means
     * `ConnectorKeepAliveHintCard` is handed a null callback and renders neither the autostart
     * button nor the sentence about it.
     */
    @Nested
    @DisplayName("hasAutostartScreen")
    inner class HasAutostartScreen {
        @Test
        fun `true when the vendor screen resolves, so the control is offered`() {
            screenResolves()

            assertTrue(VendorProfiles.hasAutostartScreen(context))
        }

        @Test
        fun `false when nothing resolves, so the control is absent rather than dead`() {
            nothingResolves()

            assertFalse(VendorProfiles.hasAutostartScreen(context))
        }
    }
}
