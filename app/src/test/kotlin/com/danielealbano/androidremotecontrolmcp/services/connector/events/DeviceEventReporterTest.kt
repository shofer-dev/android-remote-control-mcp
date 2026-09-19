package com.danielealbano.androidremotecontrolmcp.services.connector.events

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.DeviceEventConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.DevicePolicy
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.EventAppFilter
import com.danielealbano.androidremotecontrolmcp.services.connector.policy.EventPolicy
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reporter's whole job: merge the sources, apply the gate, emit frames.
 *
 * Driven by FAKE sources, because the real ones are OS registrations and the thing under test is
 * not the registration — it is that nothing leaves the phone unless both filters said yes, and that
 * what does leave carries the three fields the gateway reads.
 */
@DisplayName("DeviceEventReporter")
class DeviceEventReporterTest {
    private class FakeSource(
        override val category: DeviceEventCategory,
        private val emissions: List<DeviceEvent>,
    ) : DeviceEventSource {
        override fun events(): Flow<DeviceEvent> = flowOf(*emissions.toTypedArray())
    }

    private fun batteryEvent(level: Int) =
        DeviceEvent(
            category = DeviceEventCategory.BATTERY,
            occurredAtMillis = 1_757_000_000_000,
            payload = EventPayloads.battery(BatteryState(level, charging = false)),
        )

    private fun notificationEvent(packageName: String) =
        DeviceEvent(
            category = DeviceEventCategory.NOTIFICATION,
            occurredAtMillis = 1_757_000_000_000,
            payload = EventPayloads.notification(testNotification(packageName = packageName), key = "k"),
            subjectPackage = packageName,
        )

    private fun repositoryWith(config: DeviceEventConfig): SettingsRepository =
        mockk<SettingsRepository>().also {
            every { it.deviceEventConfig } returns MutableStateFlow(config)
            coEvery { it.getDeviceEventConfig() } returns config
        }

    private fun reporter(
        sources: List<DeviceEventSource>,
        holder: DeviceEventConfig = DeviceEventConfig(),
        policy: DevicePolicy? = null,
    ) = DeviceEventReporter(sources, repositoryWith(holder)) { policy }

    @Test
    fun `an allowed event becomes an event frame carrying category, occurred_at and payload`() =
        runTest {
            val source = FakeSource(DeviceEventCategory.BATTERY, listOf(batteryEvent(42)))
            reporter(listOf(source)).reports().test {
                val frame = awaitItem()
                assertEquals(FrameType.EVENT, frame.type)
                assertEquals("battery", frame.category)
                assertEquals("2025-09-04T15:33:20Z", frame.occurredAt)
                assertEquals(
                    "42",
                    frame.payload
                        ?.jsonObject
                        ?.get("level")
                        ?.jsonPrimitive
                        ?.content,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a category the holder switched off emits no frame`() =
        runTest {
            val source = FakeSource(DeviceEventCategory.BATTERY, listOf(batteryEvent(42)))
            val holder = DeviceEventConfig().with(DeviceEventCategory.BATTERY, enabled = false)
            reporter(listOf(source), holder = holder).reports().test {
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a category the platform disabled emits no frame`() =
        runTest {
            val source = FakeSource(DeviceEventCategory.BATTERY, listOf(batteryEvent(42)))
            val policy = DevicePolicy(events = EventPolicy(categories = mapOf("battery" to false)))
            reporter(listOf(source), policy = policy).reports().test {
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the notification allowlist is applied per event, not per category`() =
        runTest {
            // The blocked app's notification is withheld while the allowed one goes out on the same
            // source, which is the whole point of gating at the event rather than at the source.
            val source =
                FakeSource(
                    DeviceEventCategory.NOTIFICATION,
                    listOf(notificationEvent("com.example.games"), notificationEvent("com.example.mail")),
                )
            val policy =
                DevicePolicy(
                    events = EventPolicy(notificationApps = EventAppFilter(allow = listOf("com.example.mail"))),
                )
            reporter(listOf(source), policy = policy).reports().test {
                val frame = awaitItem()
                assertEquals(
                    "com.example.mail",
                    frame.payload
                        ?.jsonObject
                        ?.get("package")
                        ?.jsonPrimitive
                        ?.content,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ───────────────── the plane must never be silent AND invisible ─────────────────
    //
    // Found live on the bench: the connector attached, every other watch started, and NOT ONE
    // source registered with the OS — no receiver, no network callback, no exception, no log. The
    // cause was ordering: `reports()` read the holder's toggles (a suspending DataStore read)
    // BEFORE starting the sources, so a read that did not return disabled the whole plane
    // invisibly for the life of the socket. These tests pin the shape, not the symptom.

    /** A source that records whether it was ever collected — i.e. whether it reached the OS. */
    private class RecordingSource(
        override val category: DeviceEventCategory,
        private val emissions: List<DeviceEvent> = emptyList(),
        private val failWith: Throwable? = null,
    ) : DeviceEventSource {
        val registered = AtomicBoolean(false)

        override fun events(): Flow<DeviceEvent> =
            flow {
                // Stands in for registerReceiver / registerTelephonyCallback / registerDefault…
                registered.set(true)
                failWith?.let { throw it }
                emissions.forEach { emit(it) }
                awaitCancellation()
            }
    }

    private fun repositoryWhoseConfig(config: Flow<DeviceEventConfig>): SettingsRepository =
        mockk<SettingsRepository>().also { every { it.deviceEventConfig } returns config }

    @Test
    fun `every source registers even when the holder config never arrives`() =
        runTest {
            // THE REGRESSION. A settings read that never returns must cost the plane its EVENTS,
            // never its REGISTRATIONS — a source that never registered can never report, even once
            // the read finally lands.
            val sources = DeviceEventCategory.entries.map { RecordingSource(it) }
            val reporter = DeviceEventReporter(sources, repositoryWhoseConfig(flow { awaitCancellation() })) { null }
            reporter.reports().test {
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            sources.forEach { assertTrue(it.registered.get(), "${it.category} should have registered with the OS") }
        }

    @Test
    fun `a holder config read that throws still registers and still reports`() =
        runTest {
            // Falling back to the documented all-on default, because a phone that quietly stopped
            // reporting looks exactly like a phone with nothing to report. The platform policy is
            // still the other conjunct, so the org's narrowing survives the fallback.
            val source = RecordingSource(DeviceEventCategory.BATTERY, listOf(batteryEvent(42)))
            val failing = flow<DeviceEventConfig> { throw IllegalStateException("datastore is broken") }
            DeviceEventReporter(listOf(source), repositoryWhoseConfig(failing)) { null }.reports().test {
                assertEquals("battery", awaitItem().category)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(source.registered.get())
        }

    @Test
    fun `a source that fails does not silence the other four`() =
        runTest {
            // All five share one producer scope, so without per-source isolation one vendor ROM
            // refusing a registration would take the whole plane down — and surface as an unhandled
            // exception in the connector's scope rather than as a report about one category.
            val broken = RecordingSource(DeviceEventCategory.CALL, failWith = IllegalStateException("no radio"))
            val healthy = RecordingSource(DeviceEventCategory.BATTERY, listOf(batteryEvent(42)))
            val repo = repositoryWhoseConfig(MutableStateFlow(DeviceEventConfig()))
            DeviceEventReporter(listOf(broken, healthy), repo) { null }.reports().test {
                assertEquals("battery", awaitItem().category)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(broken.registered.get())
            assertTrue(healthy.registered.get())
        }

    @Test
    fun `every source is collected, not just the first`() =
        runTest {
            val sources =
                listOf(
                    FakeSource(DeviceEventCategory.BATTERY, listOf(batteryEvent(42))),
                    FakeSource(DeviceEventCategory.NOTIFICATION, listOf(notificationEvent("com.example.mail"))),
                )
            reporter(sources).reports().test {
                val categories = setOf(awaitItem().category, awaitItem().category)
                assertEquals(setOf("battery", "notification"), categories)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
