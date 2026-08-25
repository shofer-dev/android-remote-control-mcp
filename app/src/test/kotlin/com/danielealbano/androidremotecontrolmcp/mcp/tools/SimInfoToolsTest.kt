package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.sim.SimInfoReader
import com.danielealbano.androidremotecontrolmcp.services.sim.SimInfoResult
import com.danielealbano.androidremotecontrolmcp.services.sim.SimSubscription
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The `get_sim_info` tool hands the reader's typed outcome back as PLAIN JSON — no untrusted
 * banner — because phone-service parses the text directly. These pin that the wire text is exactly
 * the reader's JSON so the host can `JSON.parse(content[0].text)`.
 */
@DisplayName("GetSimInfoHandler")
class SimInfoToolsTest {
    @Test
    fun `an ok result is returned as parseable JSON with no banner`() {
        val handler =
            GetSimInfoHandler(
                reader(
                    SimInfoResult.Subscriptions(
                        listOf(
                            SimSubscription(1, "+306912345678", "TestCarrier", "8930101234567890123"),
                        ),
                    ),
                ),
            )

        val text = (handler.execute(null).content.single() as TextContent).text
        // No prompt-injection banner would break the parse; assert it parses and is correct.
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        assertEquals(
            "+306912345678",
            obj["subscriptions"]!!
                .jsonArray
                .single()
                .jsonObject["number"]!!
                .jsonPrimitive.content,
        )
        assertFalse(text.contains("untrusted", ignoreCase = true))
    }

    @Test
    fun `permission-not-granted is a normal JSON result, not an error`() {
        val handler = GetSimInfoHandler(reader(SimInfoResult.PermissionNotGranted))

        val result = handler.execute(null)

        // isError is nullable; a normal result leaves it null/false.
        assertFalse(result.isError == true)
        val obj = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
        assertEquals("permission-not-granted", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a number-not-provisioned SIM comes through as an ok subscription`() {
        val handler =
            GetSimInfoHandler(
                reader(
                    SimInfoResult.Subscriptions(
                        listOf(SimSubscription(1, null, "TestCarrier", "8930101111111111111")),
                    ),
                ),
            )

        val obj =
            Json
                .parseToJsonElement((handler.execute(null).content.single() as TextContent).text)
                .jsonObject
        val sub = obj["subscriptions"]!!.jsonArray.single().jsonObject
        assertEquals("number-not-provisioned-on-sim", sub["number_status"]!!.jsonPrimitive.content)
    }

    private fun reader(result: SimInfoResult) = SimInfoReader { result }
}

/** A fun-interface conversion for the reader so the test needs no mocking framework. */
private fun SimInfoReader(block: () -> SimInfoResult): SimInfoReader =
    object : SimInfoReader {
        override fun read(): SimInfoResult = block()
    }
