package com.danielealbano.androidremotecontrolmcp.services.sim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The SIM-info classification and its JSON, as a matrix. The Android read
 * ([SimInfoReaderImpl]) is a thin adapter over these pure functions, so every branch the host
 * `read_sim_number` action depends on — permission missing, no SIM, single provisioned, single
 * unprovisioned, dual SIM — is pinned here without a `SubscriptionManager`.
 */
@DisplayName("SimInfoResult")
class SimInfoResultTest {
    @Nested
    @DisplayName("evaluate")
    inner class Evaluate {
        @Test
        fun `permission not granted short-circuits before any subscription is read`() {
            // Even if a build handed back subs, an ungranted read must report the permission state
            // so the host re-runs the grant rather than trusting the numbers.
            val result = SimInfoResult.evaluate(permissionGranted = false, subscriptions = listOf(SINGLE))

            assertEquals(SimInfoResult.PermissionNotGranted, result)
            assertEquals("permission-not-granted", result.status)
        }

        @Test
        fun `granted with no subscriptions is no-active-sim, not an error`() {
            val result = SimInfoResult.evaluate(permissionGranted = true, subscriptions = emptyList())

            assertEquals(SimInfoResult.NoActiveSim, result)
            assertEquals("no-active-sim", result.status)
        }

        @Test
        fun `a single provisioned SIM is returned`() {
            val result = SimInfoResult.evaluate(permissionGranted = true, subscriptions = listOf(SINGLE))

            val subs = (result as SimInfoResult.Subscriptions).subscriptions
            assertEquals(1, subs.size)
            assertEquals("+306912345678", subs.single().number)
        }

        @Test
        fun `a dual-SIM device returns every active subscription`() {
            val result =
                SimInfoResult.evaluate(
                    permissionGranted = true,
                    subscriptions = listOf(SINGLE, SECOND),
                )

            val subs = (result as SimInfoResult.Subscriptions).subscriptions
            assertEquals(2, subs.size)
            assertEquals(listOf(1, 2), subs.map { it.subscriptionId })
        }

        @Test
        fun `a SIM with no programmed number is still returned, as a subscription`() {
            // The whole point: number-not-provisioned is a SUBSCRIPTION, not a failure — the host
            // needs the carrier/iccid to decide, and this is the SMS-fallback cue.
            val result = SimInfoResult.evaluate(permissionGranted = true, subscriptions = listOf(UNPROVISIONED))

            assertTrue(result is SimInfoResult.Subscriptions)
            assertEquals("ok", result.status)
        }
    }

    @Nested
    @DisplayName("numberStatus")
    inner class NumberStatusOf {
        @Test
        fun `a real number is provisioned`() {
            assertEquals(NumberStatus.PROVISIONED, SimInfoResult.numberStatus("+306912345678"))
        }

        @Test
        fun `a null number is not provisioned`() {
            assertEquals(NumberStatus.NOT_PROVISIONED, SimInfoResult.numberStatus(null))
        }

        @Test
        fun `a blank number is not provisioned`() {
            // Some carriers write an empty EF_MSISDN; a blank string is not a number.
            assertEquals(NumberStatus.NOT_PROVISIONED, SimInfoResult.numberStatus("   "))
        }
    }

    @Nested
    @DisplayName("toJson")
    inner class ToJson {
        @Test
        fun `permission-not-granted carries only the status`() {
            val obj = parse(SimInfoResult.PermissionNotGranted.toJson())

            assertEquals("permission-not-granted", obj["status"]!!.jsonPrimitive.content)
            assertNull(obj["subscriptions"])
        }

        @Test
        fun `no-active-sim carries only the status`() {
            val obj = parse(SimInfoResult.NoActiveSim.toJson())

            assertEquals("no-active-sim", obj["status"]!!.jsonPrimitive.content)
            assertNull(obj["subscriptions"])
        }

        @Test
        fun `a provisioned subscription serialises number and provisioned status`() {
            val obj = parse(SimInfoResult.Subscriptions(listOf(SINGLE)).toJson())

            assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
            val sub = obj["subscriptions"]!!.jsonArray.single().jsonObject
            assertEquals(1, sub["subscription_id"]!!.jsonPrimitive.content.toInt())
            assertEquals("+306912345678", sub["number"]!!.jsonPrimitive.content)
            assertEquals("provisioned", sub["number_status"]!!.jsonPrimitive.content)
            assertEquals("TestCarrier", sub["carrier_name"]!!.jsonPrimitive.content)
            assertEquals("8930101234567890123", sub["iccid"]!!.jsonPrimitive.content)
        }

        @Test
        fun `an unprovisioned subscription serialises a JSON null number and the typed status`() {
            val obj = parse(SimInfoResult.Subscriptions(listOf(UNPROVISIONED)).toJson())

            val sub = obj["subscriptions"]!!.jsonArray.single().jsonObject
            assertEquals(JsonNull, sub["number"])
            assertEquals("number-not-provisioned-on-sim", sub["number_status"]!!.jsonPrimitive.content)
            // Carrier/iccid are still present so the host can act on the SIM without its number.
            assertEquals("TestCarrier", sub["carrier_name"]!!.jsonPrimitive.content)
        }

        @Test
        fun `a blank number serialises as JSON null, never an empty string`() {
            val obj = parse(SimInfoResult.Subscriptions(listOf(SINGLE.copy(number = "   "))).toJson())

            val sub = obj["subscriptions"]!!.jsonArray.single().jsonObject
            assertEquals(JsonNull, sub["number"])
            assertEquals("number-not-provisioned-on-sim", sub["number_status"]!!.jsonPrimitive.content)
        }

        @Test
        fun `a blank carrier or iccid serialises as JSON null`() {
            val obj =
                parse(
                    SimInfoResult
                        .Subscriptions(listOf(SINGLE.copy(carrierName = "", iccId = "  ")))
                        .toJson(),
                )

            val sub = obj["subscriptions"]!!.jsonArray.single().jsonObject
            assertEquals(JsonNull, sub["carrier_name"])
            assertEquals(JsonNull, sub["iccid"])
        }

        @Test
        fun `the output is a single clean JSON object the host can parse directly`() {
            // The tool returns this via textResult (no untrusted banner), so it must parse as-is.
            val raw = SimInfoResult.Subscriptions(listOf(SINGLE, SECOND)).toJson()

            val obj = Json.parseToJsonElement(raw).jsonObject
            assertEquals(2, obj["subscriptions"]!!.jsonArray.size)
        }
    }

    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private companion object {
        val SINGLE =
            SimSubscription(
                subscriptionId = 1,
                number = "+306912345678",
                carrierName = "TestCarrier",
                iccId = "8930101234567890123",
            )
        val SECOND =
            SimSubscription(
                subscriptionId = 2,
                number = "+306998765432",
                carrierName = "OtherCarrier",
                iccId = "8930109999999999999",
            )
        val UNPROVISIONED =
            SimSubscription(
                subscriptionId = 3,
                number = null,
                carrierName = "TestCarrier",
                iccId = "8930101111111111111",
            )
    }
}
