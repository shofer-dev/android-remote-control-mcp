package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.DeviceEventConfig
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The holder's per-category toggles as they are PERSISTED.
 *
 * `SettingsRepositoryImpl` stores this config as the JSON produced here under its own DataStore key
 * (`device_event_config`), so the round-trip below is the persistence mechanism itself — the same
 * reasoning `EventChannelSettingsTest` records for the channel's blob.
 */
@DisplayName("DeviceEventSettings")
class DeviceEventSettingsTest {
    @Nested
    @DisplayName("defaults")
    inner class Defaults {
        @Test
        fun `every category is on by default`() {
            // The plane's whole value is that an enrolled phone REPORTS; a holder who wants less
            // narrows it. A default-off config would make the feature invisible on every handset.
            val config = DeviceEventConfig()
            DeviceEventCategory.entries.forEach { category ->
                assertTrue(config.permits(category), "$category should default to on")
            }
        }

        @Test
        fun `an absent blob is the all-on default`() {
            assertEquals(DeviceEventConfig(), DeviceEventConfig.fromJsonOrDefault(null))
            assertEquals(DeviceEventConfig(), DeviceEventConfig.fromJsonOrDefault(""))
        }

        @Test
        fun `an unreadable blob falls back to the default rather than to silence`() {
            // A phone that quietly stopped reporting looks exactly like a phone with nothing to
            // report, and the holder never asked for that. The platform's policy is still the other
            // conjunct, so the org's narrowing survives this fallback.
            val restored = DeviceEventConfig.fromJsonOrDefault("{not json at all")
            assertEquals(DeviceEventConfig(), restored)
            assertTrue(restored.permits(DeviceEventCategory.SMS))
        }
    }

    @Nested
    @DisplayName("round-trip")
    inner class RoundTrip {
        @Test
        fun `every category survives a write and a read`() {
            DeviceEventCategory.entries.forEach { category ->
                val written = DeviceEventConfig().with(category, enabled = false)
                val restored = DeviceEventConfig.fromJsonOrDefault(written.toJson())
                assertEquals(written, restored)
                assertFalse(restored.permits(category), "$category should still be off after a round-trip")
            }
        }

        @Test
        fun `the persisted keys are the five category names`() {
            val json = DeviceEventConfig().toJson()
            DeviceEventCategory.entries.forEach { category ->
                assertTrue(json.contains("\"${category.wire}\""), "the blob should carry ${category.wire}")
            }
        }

        @Test
        fun `a blob written by an older build keeps its values and defaults the rest`() {
            // Forward compatibility in the direction that actually happens here: the platform ships
            // the APK, so a newly added category must not reset the four a holder already chose.
            val restored = DeviceEventConfig.fromJsonOrDefault("""{"notification":false,"call":false}""")
            assertFalse(restored.permits(DeviceEventCategory.NOTIFICATION))
            assertFalse(restored.permits(DeviceEventCategory.CALL))
            assertTrue(restored.permits(DeviceEventCategory.SMS))
            assertTrue(restored.permits(DeviceEventCategory.BATTERY))
        }

        @Test
        fun `an unknown key from a newer build is ignored rather than fatal`() {
            val restored = DeviceEventConfig.fromJsonOrDefault("""{"notification":false,"geofence":true}""")
            assertFalse(restored.permits(DeviceEventCategory.NOTIFICATION))
            assertTrue(restored.permits(DeviceEventCategory.CALL))
        }
    }

    @Nested
    @DisplayName("with()")
    inner class With {
        @Test
        fun `switching one category leaves the other four alone`() {
            val config = DeviceEventConfig().with(DeviceEventCategory.CALL, enabled = false)
            assertFalse(config.permits(DeviceEventCategory.CALL))
            assertTrue(config.permits(DeviceEventCategory.NOTIFICATION))
            assertTrue(config.permits(DeviceEventCategory.SMS))
            assertTrue(config.permits(DeviceEventCategory.CONNECTIVITY))
            assertTrue(config.permits(DeviceEventCategory.BATTERY))
        }

        @Test
        fun `switching a category back on restores it`() {
            val config =
                DeviceEventConfig()
                    .with(DeviceEventCategory.BATTERY, enabled = false)
                    .with(DeviceEventCategory.BATTERY, enabled = true)
            assertEquals(DeviceEventConfig(), config)
        }
    }
}
