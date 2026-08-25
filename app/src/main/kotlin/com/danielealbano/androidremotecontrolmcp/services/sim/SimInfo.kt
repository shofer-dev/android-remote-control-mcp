package com.danielealbano.androidremotecontrolmcp.services.sim

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One active subscription as read from `SubscriptionManager`, reduced to plain data so the
 * classification and the JSON rendering are pure and JVM-testable (the same shape
 * `ScreenshotAnnotator.ScaledBounds` uses to keep framework types out of the tested path).
 *
 * [number] is nullable ON PURPOSE. The MSISDN is only present when the CARRIER programmed it into
 * the SIM's EF_MSISDN elementary file, which a large fraction of carriers never do — so a blank
 * or null number is the ordinary, expected case, not a fault (see [SimInfoResult]).
 *
 * [iccId] may be blank on a retail (user) build even with `READ_PHONE_NUMBERS`: the full ICCID is
 * gated behind privileged/carrier access on recent Android, so the reader returns whatever the
 * platform hands back and never treats its absence as an error.
 */
data class SimSubscription(
    val subscriptionId: Int,
    val number: String?,
    val carrierName: String?,
    val iccId: String?,
)

/**
 * The per-subscription number provisioning state, kept separate from the number itself so the
 * host can branch on it without string-matching a nullable field.
 */
enum class NumberStatus(
    val wire: String,
) {
    /** The carrier programmed the MSISDN into the SIM and we could read it. */
    PROVISIONED("provisioned"),

    /**
     * The SIM is present and readable but carries no MSISDN. NOT an error — this is exactly the
     * case the tethered-number feature falls back to typed+SMS verification for.
     */
    NOT_PROVISIONED("number-not-provisioned-on-sim"),
}

/**
 * The outcome of reading the tethered SIM's own number, as a closed set of typed states.
 *
 * Every state below is a NORMAL return, rendered to the host as JSON with a `status` field — none
 * is an MCP error. The distinction matters because the host's `read_sim_number` action branches on
 * the outcome: [PermissionNotGranted] means "re-run the provisioning grant", [NoActiveSim] means
 * "there is no SIM to read", and a [Subscriptions] result whose entries are all
 * [NumberStatus.NOT_PROVISIONED] is the legitimate cue to fall back to SMS verification. Collapsing
 * any of these into an error would make a routine, recoverable state look like a device fault.
 */
sealed interface SimInfoResult {
    val status: String

    /** `READ_PHONE_NUMBERS` is not held; the provisioning grant must run. */
    data object PermissionNotGranted : SimInfoResult {
        override val status = "permission-not-granted"
    }

    /** No active subscription at all — no SIM, or the modem is off (airplane mode). */
    data object NoActiveSim : SimInfoResult {
        override val status = "no-active-sim"
    }

    /**
     * One or more active subscriptions. Multi-SIM devices return every active sub; the host picks
     * (typically the one whose number is provisioned, else the first).
     */
    data class Subscriptions(
        val subscriptions: List<SimSubscription>,
    ) : SimInfoResult {
        override val status = "ok"
    }

    /** Serialises the outcome to the JSON the tool returns. See the per-field notes inline. */
    fun toJson(): String =
        json.encodeToString(
            kotlinx.serialization.json.JsonElement
                .serializer(),
            toJsonElement(),
        )

    private fun toJsonElement() =
        buildJsonObject {
            put("status", status)
            if (this@SimInfoResult is Subscriptions) {
                put(
                    "subscriptions",
                    buildJsonArray {
                        subscriptions.forEach { add(it.toJsonElement()) }
                    },
                )
            }
        }

    companion object {
        private val json = kotlinx.serialization.json.Json { encodeDefaults = true }

        /**
         * The pure classification, extracted so its whole matrix — permission missing, no SIM,
         * single SIM provisioned, single SIM unprovisioned, dual SIM — is unit-testable without a
         * `SubscriptionManager`.
         *
         * [permissionGranted] is checked FIRST and short-circuits: an ungranted read returns an
         * empty subscription list on some builds and throws on others, so neither empty-list nor a
         * caught exception may be read as "no SIM" — only the permission check answers that.
         */
        fun evaluate(
            permissionGranted: Boolean,
            subscriptions: List<SimSubscription>,
        ): SimInfoResult =
            when {
                !permissionGranted -> PermissionNotGranted
                subscriptions.isEmpty() -> NoActiveSim
                else -> Subscriptions(subscriptions)
            }

        /** The number's provisioning state — blank and null both mean the carrier did not set it. */
        fun numberStatus(number: String?): NumberStatus =
            if (number.isNullOrBlank()) NumberStatus.NOT_PROVISIONED else NumberStatus.PROVISIONED
    }
}

private fun SimSubscription.toJsonElement() =
    buildJsonObject {
        put("subscription_id", subscriptionId)
        put("number_status", SimInfoResult.numberStatus(number).wire)
        // A blank number is normalised to JSON null so the host never has to distinguish "" from
        // absent; number_status already carries the meaning.
        val normalisedNumber = number?.takeIf { it.isNotBlank() }
        if (normalisedNumber != null) put("number", normalisedNumber) else put("number", null as String?)
        put("carrier_name", carrierName?.takeIf { it.isNotBlank() })
        put("iccid", iccId?.takeIf { it.isNotBlank() })
    }
