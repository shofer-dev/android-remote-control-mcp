package com.danielealbano.androidremotecontrolmcp.services.connector.events

import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * The five payload bodies, built as pure functions of plain values.
 *
 * They are separated from the sources that feed them because the KEY SPELLINGS are the contract
 * (`phone-gateway/internal/protocol/deviceevent.go` declares them as prose — the gateway is a
 * courier and parses none of them, so nothing between this file and the bus subscriber would ever
 * notice a rename). Pure functions mean the spellings are pinned by a unit test rather than by a
 * device somebody remembered to look at.
 *
 * Absent text is the EMPTY STRING, never a JSON null. A notification with no title and one with a
 * null title are the same fact to a reader, and two spellings of one fact is how a subscriber ends
 * up with a branch for each.
 */
object EventPayloads {
    /**
     * `{package, app_name, title, text, posted_at, key}`
     *
     * Takes the extractor's own [NotificationData] rather than six loose values, so the mapping
     * cannot silently pair the wrong string with the wrong key. [key] stays separate because it is
     * the framework's raw `StatusBarNotification` key, which [NotificationData] deliberately does
     * not carry — its identifier is the opaque hash the MCP tool surface hands out.
     */
    fun notification(
        data: NotificationData,
        key: String,
    ): JsonObject =
        buildJsonObject {
            put("package", data.packageName)
            put("app_name", data.appName)
            put("title", data.title.orEmpty())
            put("text", data.text.orEmpty())
            put("posted_at", rfc3339(data.timestamp))
            put("key", key)
        }

    /**
     * `{state, number}` — [state] is one of
     * [com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceCallState].
     *
     * [number] is EMPTY on every modern Android, and deliberately so: see [CallEventSource] for why
     * this app does not hold `READ_CALL_LOG`. The key is still emitted so the shape is stable for a
     * subscriber and so the answer reads as "not disclosed" rather than "this build forgot".
     */
    fun call(
        state: String,
        number: String,
    ): JsonObject =
        buildJsonObject {
            put("state", state)
            put("number", number)
        }

    /** `{from, body, received_at}` */
    fun sms(
        from: String,
        body: String,
        receivedAtMillis: Long,
    ): JsonObject =
        buildJsonObject {
            put("from", from)
            put("body", body)
            put("received_at", rfc3339(receivedAtMillis))
        }

    /** `{transport, online}` */
    fun connectivity(state: ConnectivityState): JsonObject =
        buildJsonObject {
            put("transport", state.transport)
            put("online", state.online)
        }

    /** `{level, charging}` — `level` is an integer percentage, 0-100. */
    fun battery(state: BatteryState): JsonObject =
        buildJsonObject {
            put("level", state.level)
            put("charging", state.charging)
        }

    /**
     * Epoch millis as RFC3339 in UTC, which is what every timestamp on this plane carries.
     *
     * [Instant.toString] emits the `…Z` form Go's `time.RFC3339` layout parses, dropping the
     * fractional part when it is zero — both spellings are valid RFC3339 and both round-trip.
     */
    fun rfc3339(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()
}
