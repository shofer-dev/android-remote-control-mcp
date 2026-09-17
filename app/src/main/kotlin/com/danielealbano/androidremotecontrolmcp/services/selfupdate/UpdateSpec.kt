package com.danielealbano.androidremotecontrolmcp.services.selfupdate

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One publishable build, as the platform describes it: where the APK is, what it must hash to, and
 * what version it claims to be.
 *
 * It arrives two ways and is the same triple both times — in the `params` of an `update_app`
 * action frame (the platform pushing an update), and in the `params` of an `update_info` frame
 * (the gateway answering this app's own `update_check`). One parser serves both, so a field the
 * gateway spells differently on one leg cannot be silently tolerated on the other.
 *
 * [version] is an opaque STRING and is compared for equality only. The platform's versions are
 * content-derived tags (`g953943d7f944`), so there is no ordering to read out of them and no
 * "newer than" this app could compute: the only question it can answer honestly is whether the
 * published version is the one already running.
 */
data class UpdateSpec(
    val url: String,
    val sha256: String,
    val version: String,
) {
    companion object {
        /**
         * Reads a spec out of a frame's `params`, or null when there is nothing usable there.
         *
         * Null covers three cases the callers deliberately treat differently: a malformed
         * `update_app` payload (answered [UpdateRefusal.BAD_PARAMS]), and — on the `update_info`
         * leg — both "the gateway published nothing" (the contract's absent/empty `version`) and
         * "the gateway answered something this app cannot act on". Both of those mean the same
         * thing to the UI, so they are not distinguished.
         *
         * The scheme check is HERE rather than in the downloader because a plain-`http` URL is a
         * malformed spec, not a transport failure: the APK's integrity rides on the hash and the
         * signature, but the URL itself can carry a capability token, and handing one to a
         * cleartext socket is a disclosure no later check can undo.
         */
        fun parse(params: JsonElement?): UpdateSpec? {
            val obj = params as? JsonObject ?: return null
            val url = obj.string("url")
            val sha256 = obj.string("sha256")
            val version = obj.string("version")
            // The https test subsumes the blank test for `url`: an empty string starts with nothing.
            val usable = url.startsWith(HTTPS_PREFIX) && sha256.isNotBlank() && version.isNotBlank()
            return if (usable) UpdateSpec(url = url, sha256 = sha256, version = version) else null
        }

        /**
         * A string field, or empty when absent, null, or not a primitive. Never throws on a bad
         * shape — a gateway bug must reach the caller as a typed refusal, not as an exception out
         * of an action handler.
         *
         * `contentOrNull` rather than `content` is load-bearing: `JsonNull` IS a [JsonPrimitive],
         * and its `content` is the four-character string `"null"` — so the obvious spelling would
         * read `"sha256": null` as a non-blank hash and let the spec through.
         */
        private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

        private const val HTTPS_PREFIX = "https://"
    }
}

/**
 * The typed reasons an `update_app` action can be refused, carried in the `action_result` frame's
 * `error` field. The platform branches on these, so the spellings are wire contract.
 */
object UpdateRefusal {
    /** `params.version` is the version already running. Nothing to do, and not an error. */
    const val ALREADY_CURRENT = "already-current"

    /** The APK could not be fetched — DNS, TLS, a non-2xx answer, a truncated body. */
    const val DOWNLOAD_FAILED = "download-failed"

    /** The bytes arrived but do not hash to `params.sha256`. */
    const val CHECKSUM_MISMATCH = "checksum-mismatch"

    /**
     * The OS installer refused before the process could be replaced — no session, no write, or a
     * commit that threw. A refusal REPORTED BY THE CALLBACK after commit usually cannot ride back
     * on the socket at all, because the install has already killed this process by then.
     */
    const val INSTALL_REFUSED = "install-refused"

    /**
     * The frame's `params` are not a usable [UpdateSpec] — a missing field, or a URL that is not
     * `https`. A protocol fault rather than a device one; it is reported rather than folded into
     * `download-failed` so a gateway bug cannot be read as a flaky network.
     */
    const val BAD_PARAMS = "bad-params"
}
