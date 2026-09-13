package com.danielealbano.androidremotecontrolmcp.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.activity.result.ActivityResultLauncher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The two facts every "open a Settings screen" call depends on.
 *
 * The flag rule is the one that cost a phone: `FLAG_ACTIVITY_NEW_TASK` was added unconditionally,
 * so a checklist button launched its target as a NEW TASK — which MIUI's background-start guard
 * drops in silence, and which no activity can receive a result from. The flag is correct only when
 * there is no Activity to launch from, and [findActivity] is what answers that through Compose's
 * wrapped context.
 */
@DisplayName("ActivityContext")
class ActivityContextTest {
    @Nested
    @DisplayName("findActivity")
    inner class FindActivity {
        @Test
        fun `returns null for a context that is not an activity`() {
            assertNull(mockk<Context>(relaxed = true).findActivity())
        }

        @Test
        fun `returns the activity itself`() {
            val activity = mockk<Activity>(relaxed = true)

            assertSame(activity, activity.findActivity())
        }

        @Test
        fun `unwraps a wrapped activity, which is what Compose hands out`() {
            val activity = mockk<Activity>(relaxed = true)
            val wrapper = mockk<ContextWrapper>(relaxed = true)
            every { wrapper.baseContext } returns activity

            assertSame(activity, wrapper.findActivity())
        }

        @Test
        fun `returns null for a wrapper chain that ends without an activity`() {
            val application = mockk<Context>(relaxed = true)
            val wrapper = mockk<ContextWrapper>(relaxed = true)
            every { wrapper.baseContext } returns application

            assertNull(wrapper.findActivity())
        }
    }

    @Nested
    @DisplayName("startSettingsActivity")
    inner class StartSettingsActivity {
        @Test
        fun `adds FLAG_ACTIVITY_NEW_TASK when there is no activity to launch from`() {
            val context = mockk<Context>(relaxed = true)
            val intent = mockk<Intent>(relaxed = true)

            assertTrue(context.startSettingsActivity(intent))

            verify { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            verify { context.startActivity(intent) }
        }

        @Test
        fun `does not add FLAG_ACTIVITY_NEW_TASK when launched from an activity`() {
            val activity = mockk<Activity>(relaxed = true)
            val intent = mockk<Intent>(relaxed = true)

            assertTrue(activity.startSettingsActivity(intent))

            verify(exactly = 0) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            verify { activity.startActivity(intent) }
        }

        @Test
        fun `does not add FLAG_ACTIVITY_NEW_TASK when the activity arrives wrapped`() {
            val activity = mockk<Activity>(relaxed = true)
            val wrapper = mockk<ContextWrapper>(relaxed = true)
            every { wrapper.baseContext } returns activity
            val intent = mockk<Intent>(relaxed = true)

            assertTrue(wrapper.startSettingsActivity(intent))

            verify(exactly = 0) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }

        @Test
        fun `reports false when the screen is absent from this build`() {
            val context = mockk<Context>(relaxed = true)
            val intent = mockk<Intent>(relaxed = true)
            every { context.startActivity(intent) } throws ActivityNotFoundException("no such screen")

            assertFalse(context.startSettingsActivity(intent))
        }

        @Test
        fun `reports false when the build refuses the launch`() {
            val context = mockk<Context>(relaxed = true)
            val intent = mockk<Intent>(relaxed = true)
            every { context.startActivity(intent) } throws SecurityException("not yours to open")

            assertFalse(context.startSettingsActivity(intent))
        }
    }

    /**
     * The launcher form, which is what a checklist row uses: it keeps the target in this activity's
     * task, so the row's owner hears about the return and can re-read the grant. Without it a
     * permission granted in Settings left its row red until the next watchdog tick.
     */
    @Nested
    @DisplayName("startSettingsActivity, through a result launcher")
    inner class StartSettingsActivityForResult {
        private val launcher: ActivityResultLauncher<Intent> = mockk(relaxed = true)
        private val intent: Intent = mockk(relaxed = true)

        @Test
        fun `launches through the result contract`() {
            assertTrue(launcher.startSettingsActivity(intent))

            verify { launcher.launch(intent) }
        }

        @Test
        fun `reports false when the screen is absent, so the caller can fall back`() {
            every { launcher.launch(intent) } throws ActivityNotFoundException("no such screen")

            assertFalse(launcher.startSettingsActivity(intent))
        }

        @Test
        fun `reports false when the build refuses the launch`() {
            every { launcher.launch(intent) } throws SecurityException("not yours to open")

            assertFalse(launcher.startSettingsActivity(intent))
        }
    }

    /**
     * Asked before a launch, so a screen a vendor left out becomes a different destination and
     * honest row text — never a button that dies on the press.
     */
    @Nested
    @DisplayName("canStartSettingsActivity")
    inner class CanStartSettingsActivity {
        private val context: Context = mockk(relaxed = true)
        private val packageManager: PackageManager = mockk(relaxed = true)
        private val intent: Intent = mockk(relaxed = true)

        @Test
        fun `true when the build has an activity for the intent`() {
            every { context.packageManager } returns packageManager
            every { packageManager.resolveActivity(intent, any<Int>()) } returns mockk<ResolveInfo>()

            assertTrue(context.canStartSettingsActivity(intent))
        }

        @Test
        fun `false when nothing answers the intent`() {
            every { context.packageManager } returns packageManager
            every { packageManager.resolveActivity(intent, any<Int>()) } returns null

            assertFalse(context.canStartSettingsActivity(intent))
        }
    }
}
