package com.danielealbano.androidremotecontrolmcp.services.connector.events

import com.danielealbano.androidremotecontrolmcp.data.model.DeviceEventConfig
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.DevicePolicy
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.EventAppFilter
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.EventPolicy
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The forwarding intersection (`docs/phone/device_events.md` §2): holder AND platform.
 *
 * Every case here is a disclosure decision, which is why they are tested exhaustively rather than
 * sampled — a gate that fails open sends something the holder or the org said not to send, and
 * nothing downstream can take it back.
 */
@DisplayName("EventGate")
class EventGateTest {
    private val allOn = DeviceEventConfig()

    private fun policyWith(
        categories: Map<String, Boolean> = emptyMap(),
        notificationApps: EventAppFilter = EventAppFilter(),
    ) = DevicePolicy(events = EventPolicy(categories = categories, notificationApps = notificationApps))

    /** The snapshot the gateway really sends: all five keys, every one enabled. */
    private fun allCategoriesEnabled() = DeviceEventCategory.entries.associate { it.wire to true }

    @Nested
    @DisplayName("the default is everything")
    inner class Defaults {
        @Test
        fun `every category forwards with default toggles and no policy yet`() {
            // The OPPOSITE posture from the command plane, deliberately: reporting is advisory, so
            // a snapshot that has not arrived must not blind the plane for the life of a socket.
            DeviceEventCategory.entries.forEach { category ->
                assertTrue(
                    EventGate.allows(category, allOn, policy = null, packageName = "com.example"),
                    "$category should forward when nothing has narrowed it",
                )
            }
        }

        @Test
        fun `a policy with no events section forwards everything`() {
            // An absent `events` object decodes to the default EventPolicy — empty map, empty
            // lists — which must read as permitted rather than as a fleet-wide silence.
            val policy = DevicePolicy(version = "v1")
            DeviceEventCategory.entries.forEach { category ->
                assertTrue(EventGate.allows(category, allOn, policy, "com.example"))
            }
        }

        @Test
        fun `a category key the gateway did not render forwards`() {
            // An older gateway rendering four of five keys is not an org decision about the fifth.
            val policy = policyWith(categories = mapOf(DeviceEventCategory.CALL.wire to true))
            assertTrue(EventGate.allows(DeviceEventCategory.BATTERY, allOn, policy))
        }
    }

    @Nested
    @DisplayName("the holder's conjunct")
    inner class Holder {
        @Test
        fun `a category the holder switched off never forwards, whatever the platform says`() {
            val policy = policyWith(categories = allCategoriesEnabled())
            DeviceEventCategory.entries.forEach { category ->
                val holder = allOn.with(category, enabled = false)
                assertFalse(
                    EventGate.allows(category, holder, policy, "com.example"),
                    "$category must not forward once the holder switched it off",
                )
            }
        }

        @Test
        fun `switching one category off leaves the other four alone`() {
            val holder = allOn.with(DeviceEventCategory.SMS, enabled = false)
            assertFalse(EventGate.allows(DeviceEventCategory.SMS, holder, null))
            assertTrue(EventGate.allows(DeviceEventCategory.NOTIFICATION, holder, null, "com.example"))
            assertTrue(EventGate.allows(DeviceEventCategory.CALL, holder, null))
            assertTrue(EventGate.allows(DeviceEventCategory.CONNECTIVITY, holder, null))
            assertTrue(EventGate.allows(DeviceEventCategory.BATTERY, holder, null))
        }
    }

    @Nested
    @DisplayName("the platform's conjunct")
    inner class Platform {
        @Test
        fun `a category the platform disabled never forwards, whatever the holder says`() {
            DeviceEventCategory.entries.forEach { category ->
                val policy = policyWith(categories = allCategoriesEnabled() + (category.wire to false))
                assertFalse(
                    EventGate.allows(category, allOn, policy, "com.example"),
                    "$category must not forward once the platform disabled it",
                )
            }
        }

        @Test
        fun `disabling one category leaves the other four alone`() {
            val policy = policyWith(categories = allCategoriesEnabled() + (DeviceEventCategory.CALL.wire to false))
            assertFalse(EventGate.allows(DeviceEventCategory.CALL, allOn, policy))
            assertTrue(EventGate.allows(DeviceEventCategory.BATTERY, allOn, policy))
            assertTrue(EventGate.allows(DeviceEventCategory.NOTIFICATION, allOn, policy, "com.example"))
        }
    }

    @Nested
    @DisplayName("the notification per-app filter")
    inner class NotificationApps {
        private val filtered =
            policyWith(
                categories = allCategoriesEnabled(),
                notificationApps = EventAppFilter(allow = listOf("com.example.mail")),
            )

        @Test
        fun `empty lists forward every app`() {
            val policy = policyWith(categories = allCategoriesEnabled())
            assertTrue(EventGate.allows(DeviceEventCategory.NOTIFICATION, allOn, policy, "com.anything"))
        }

        @Test
        fun `a non-empty allow list is the whole gate`() {
            assertTrue(EventGate.allows(DeviceEventCategory.NOTIFICATION, allOn, filtered, "com.example.mail"))
            assertFalse(EventGate.allows(DeviceEventCategory.NOTIFICATION, allOn, filtered, "com.example.games"))
        }

        @Test
        fun `a block list subtracts from whatever survived`() {
            val policy =
                policyWith(
                    categories = allCategoriesEnabled(),
                    notificationApps = EventAppFilter(block = listOf("com.example.games")),
                )
            assertTrue(EventGate.allows(DeviceEventCategory.NOTIFICATION, allOn, policy, "com.example.mail"))
            assertFalse(EventGate.allows(DeviceEventCategory.NOTIFICATION, allOn, policy, "com.example.games"))
        }

        @Test
        fun `block subtracts from allow when a package is in both`() {
            val policy =
                policyWith(
                    categories = allCategoriesEnabled(),
                    notificationApps =
                        EventAppFilter(
                            allow = listOf("com.example.mail", "com.example.chat"),
                            block = listOf("com.example.chat"),
                        ),
                )
            assertTrue(EventGate.allows(DeviceEventCategory.NOTIFICATION, allOn, policy, "com.example.mail"))
            assertFalse(EventGate.allows(DeviceEventCategory.NOTIFICATION, allOn, policy, "com.example.chat"))
        }

        @Test
        fun `an unnameable package is refused by an allow list and permitted by a block list`() {
            assertFalse(EventGate.allows(DeviceEventCategory.NOTIFICATION, allOn, filtered, packageName = null))
            val blocking =
                policyWith(
                    categories = allCategoriesEnabled(),
                    notificationApps = EventAppFilter(block = listOf("com.example.games")),
                )
            assertTrue(EventGate.allows(DeviceEventCategory.NOTIFICATION, allOn, blocking, packageName = null))
        }

        @Test
        fun `the app filter does not touch the other four categories`() {
            // An allowlist naming one mail app must not silence the battery, which names no app at
            // all — that would turn a privacy decision into a fleet-wide blackout.
            assertTrue(EventGate.allows(DeviceEventCategory.BATTERY, allOn, filtered))
            assertTrue(EventGate.allows(DeviceEventCategory.CALL, allOn, filtered))
            assertTrue(EventGate.allows(DeviceEventCategory.SMS, allOn, filtered))
            assertTrue(EventGate.allows(DeviceEventCategory.CONNECTIVITY, allOn, filtered))
        }
    }

    @Nested
    @DisplayName("reading the decision off an event")
    inner class FromEvent {
        @Test
        fun `the event overload uses the event's own subject package`() {
            val event =
                DeviceEvent(
                    category = DeviceEventCategory.NOTIFICATION,
                    occurredAtMillis = 0,
                    payload = EventPayloads.notification(testNotification(packageName = "com.example.games"), "k"),
                    subjectPackage = "com.example.games",
                )
            val policy =
                policyWith(
                    categories = allCategoriesEnabled(),
                    notificationApps = EventAppFilter(allow = listOf("com.example.mail")),
                )
            assertFalse(EventGate.allows(event, allOn, policy))
        }
    }
}
