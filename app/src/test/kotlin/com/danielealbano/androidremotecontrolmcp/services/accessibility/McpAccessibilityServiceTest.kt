@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("McpAccessibilityService cache-invalidation wiring")
class McpAccessibilityServiceTest {
    @Nested
    @DisplayName("triggersCacheInvalidation")
    inner class TriggersCacheInvalidation {
        @Test
        @DisplayName("window state changed (rotation / activity transition) triggers invalidation")
        fun `window state changed triggers invalidation`() {
            assertTrue(triggersCacheInvalidation(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        }

        @Test
        @DisplayName("windows changed (keyboard show / hide) triggers invalidation")
        fun `windows changed triggers invalidation`() {
            assertTrue(triggersCacheInvalidation(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        }

        @Test
        @DisplayName("window content changed does NOT trigger invalidation (too frequent)")
        fun `window content changed does not trigger invalidation`() {
            assertFalse(triggersCacheInvalidation(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        }

        @ParameterizedTest
        @ValueSource(
            ints = [
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            ],
        )
        @DisplayName("unrelated event types do NOT trigger invalidation")
        fun `unrelated event types do not trigger invalidation`(eventType: Int) {
            assertFalse(triggersCacheInvalidation(eventType))
        }
    }

    @Nested
    @DisplayName("scheduleCacheInvalidationIfNeeded")
    inner class ScheduleCacheInvalidationIfNeeded {
        @Test
        @DisplayName("schedules the debouncer on a triggering event")
        fun `schedules on a triggering event`() {
            val debouncer = mockk<CacheInvalidationDebouncer>(relaxed = true)

            scheduleCacheInvalidationIfNeeded(AccessibilityEvent.TYPE_WINDOWS_CHANGED, debouncer)

            verify(exactly = 1) { debouncer.schedule() }
        }

        @Test
        @DisplayName("does NOT schedule on a non-triggering event")
        fun `does not schedule on a non-triggering event`() {
            val debouncer = mockk<CacheInvalidationDebouncer>(relaxed = true)

            scheduleCacheInvalidationIfNeeded(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                debouncer,
            )

            verify(exactly = 0) { debouncer.schedule() }
        }

        @Test
        @DisplayName("is a safe no-op when the debouncer is null")
        fun `is a safe no-op when the debouncer is null`() {
            assertDoesNotThrow {
                scheduleCacheInvalidationIfNeeded(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null)
            }
        }
    }

    @Nested
    @DisplayName("invalidateCache")
    inner class InvalidateCache {
        @Test
        @DisplayName("clears the provided cache")
        fun `clears the provided cache`() {
            val cache = mockk<AccessibilityNodeCache>(relaxed = true)

            invalidateCache(cache)

            verify(exactly = 1) { cache.clear() }
        }

        @Test
        @DisplayName("is a safe no-op when the cache is null")
        fun `is a safe no-op when the cache is null`() {
            assertDoesNotThrow { invalidateCache(null) }
        }
    }

    @Nested
    @DisplayName("isDrivableForeground")
    inner class IsDrivableForeground {
        @Test
        @DisplayName("a null package is not drivable")
        fun `null package is not drivable`() {
            assertFalse(isDrivableForeground(null, OWN_PACKAGE))
        }

        @Test
        @DisplayName("a blank package is not drivable")
        fun `blank package is not drivable`() {
            assertFalse(isDrivableForeground("   ", OWN_PACKAGE))
        }

        @Test
        @DisplayName("this app's own package is not drivable")
        fun `own package is not drivable`() {
            assertFalse(isDrivableForeground(OWN_PACKAGE, OWN_PACKAGE))
        }

        @Test
        @DisplayName("SystemUI is not drivable")
        fun `systemui is not drivable`() {
            assertFalse(isDrivableForeground(SYSTEMUI, OWN_PACKAGE))
        }

        @Test
        @DisplayName("any other app is drivable")
        fun `other app is drivable`() {
            assertTrue(isDrivableForeground(CHROME, OWN_PACKAGE))
        }
    }

    @Nested
    @DisplayName("resolveForegroundPackage")
    inner class ResolveForegroundPackage {
        @Test
        @DisplayName("returns the topmost application window's package")
        fun `returns the topmost application window`() {
            val windows =
                listOf(
                    appWindow(pkg = CHROME, layer = 1),
                    appWindow(pkg = MAPS, layer = 5),
                )

            val resolved =
                resolveForegroundPackage(windows, OWN_PACKAGE, { fail("root not needed") }, null)

            assertEquals(MAPS, resolved)
        }

        @Test
        @DisplayName("skips this app's own overlay window in favour of the app beneath")
        fun `skips own overlay window`() {
            val windows =
                listOf(
                    appWindow(pkg = OWN_PACKAGE, layer = 10),
                    appWindow(pkg = CHROME, layer = 5),
                )

            val resolved =
                resolveForegroundPackage(windows, OWN_PACKAGE, { fail("root not needed") }, null)

            assertEquals(CHROME, resolved)
        }

        @Test
        @DisplayName("skips a SystemUI window in favour of the app beneath")
        fun `skips systemui window`() {
            val windows =
                listOf(
                    appWindow(pkg = SYSTEMUI, layer = 10),
                    appWindow(pkg = CHROME, layer = 5),
                )

            val resolved =
                resolveForegroundPackage(windows, OWN_PACKAGE, { fail("root not needed") }, null)

            assertEquals(CHROME, resolved)
        }

        @Test
        @DisplayName("ignores non-application windows (e.g. the IME)")
        fun `ignores non application windows`() {
            val windows =
                listOf(
                    appWindow(
                        pkg = IME,
                        layer = 20,
                        type = AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                    ),
                    appWindow(pkg = CHROME, layer = 5),
                )

            val resolved =
                resolveForegroundPackage(windows, OWN_PACKAGE, { fail("root not needed") }, null)

            assertEquals(CHROME, resolved)
        }

        @Test
        @DisplayName("an active window breaks a layer tie")
        fun `active window breaks a layer tie`() {
            val windows =
                listOf(
                    appWindow(pkg = CHROME, layer = 5, active = false),
                    appWindow(pkg = MAPS, layer = 5, active = true),
                )

            val resolved =
                resolveForegroundPackage(windows, OWN_PACKAGE, { fail("root not needed") }, null)

            assertEquals(MAPS, resolved)
        }

        @Test
        @DisplayName("recycles the window root nodes it inspects")
        fun `recycles window root nodes`() {
            val root = node(CHROME)
            val window =
                mockk<AccessibilityWindowInfo> {
                    every { type } returns AccessibilityWindowInfo.TYPE_APPLICATION
                    every { layer } returns 1
                    every { isActive } returns true
                    every { isFocused } returns true
                    every { this@mockk.root } returns root
                }

            resolveForegroundPackage(listOf(window), OWN_PACKAGE, { null }, null)

            verify(exactly = 1) { root.recycle() }
        }

        @Test
        @DisplayName("falls back to the active window root when the window list is empty")
        fun `falls back to active window root when window list empty`() {
            val root = node(CHROME)

            val resolved = resolveForegroundPackage(emptyList(), OWN_PACKAGE, { root }, null)

            assertEquals(CHROME, resolved)
            verify(exactly = 1) { root.recycle() }
        }

        @Test
        @DisplayName("falls back to the cached package when the active root is not drivable")
        fun `falls back to cached when active root not drivable`() {
            val root = node(OWN_PACKAGE)

            val resolved = resolveForegroundPackage(emptyList(), OWN_PACKAGE, { root }, CACHED)

            assertEquals(CACHED, resolved)
            verify(exactly = 1) { root.recycle() }
        }

        @Test
        @DisplayName("falls back to the cached package when nothing else resolves")
        fun `falls back to cached when nothing else resolves`() {
            val resolved = resolveForegroundPackage(emptyList(), OWN_PACKAGE, { null }, CACHED)

            assertEquals(CACHED, resolved)
        }

        @Test
        @DisplayName("returns null when nothing resolves and there is no cached value")
        fun `returns null when nothing resolves`() {
            val resolved = resolveForegroundPackage(emptyList(), OWN_PACKAGE, { null }, null)

            assertNull(resolved)
        }

        @Test
        @DisplayName("the window path wins over the active-root fallback")
        fun `window path wins over fallback`() {
            val windows = listOf(appWindow(pkg = CHROME, layer = 1))

            val resolved =
                resolveForegroundPackage(windows, OWN_PACKAGE, { fail("fallback not needed") }, null)

            assertEquals(CHROME, resolved)
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private fun node(pkg: String): AccessibilityNodeInfo =
        mockk {
            every { packageName } returns pkg
            every { recycle() } just Runs
        }

    private fun appWindow(
        pkg: String,
        layer: Int,
        active: Boolean = false,
        type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
    ): AccessibilityWindowInfo =
        mockk {
            every { this@mockk.type } returns type
            every { this@mockk.layer } returns layer
            every { isActive } returns active
            every { isFocused } returns active
            every { root } returns node(pkg)
        }

    private companion object {
        const val OWN_PACKAGE = "com.danielealbano.androidremotecontrolmcp"
        const val SYSTEMUI = "com.android.systemui"
        const val CHROME = "com.android.chrome"
        const val MAPS = "com.google.android.apps.maps"
        const val IME = "com.google.android.inputmethod.latin"
        const val CACHED = "com.cached.example"
    }
}
