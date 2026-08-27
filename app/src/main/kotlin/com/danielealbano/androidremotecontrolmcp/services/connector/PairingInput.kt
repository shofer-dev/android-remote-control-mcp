package com.danielealbano.androidremotecontrolmcp.services.connector

import java.net.URLDecoder

/**
 * Normalisation and validation for the two facts a holder carries from the operator console to the
 * phone: the device-edge HOST and the one-time enrolment CODE.
 *
 * It exists as a pure object, away from both the dialog and the ViewModel, because the damage it
 * repairs happens off-device — a value read off a screen and retyped, or copied through a chat app
 * that wrapped it. Written straight through, every one of those produces a dial that fails with
 * something unhelpful: `wss://wss://host/ws/device` for a pasted URL, a host with a trailing
 * newline that resolves to nothing, a code the platform refuses because it carries a space. None of
 * them is a mistake the holder can see, so each is repaired here or refused here, before anything
 * is persisted.
 *
 * What is repaired silently (a paste is not an error):
 * - surrounding and embedded whitespace, in BOTH fields — a code chunked as `ABCD EFGH` on the
 *   console and a host copied with a trailing newline are the same accident;
 * - a full URL in the host field — scheme, path, query and any `user@` are dropped, leaving the
 *   authority, which is exactly what `wss://<edgeHost>/ws/device` needs;
 * - the host's case, which DNS does not distinguish.
 *
 * What is refused: a blank field, and a host that is not a host — so nothing that cannot dial is
 * ever written to [com.danielealbano.androidremotecontrolmcp.data.model.ConnectorConfig].
 *
 * The code's case is preserved: the platform mints it and is the only thing that can say whether
 * `abcd` and `ABCD` are the same code.
 */
object PairingInput {
    /**
     * The console's QR payload, `justceo-enrol:v1?host=<edge_host>&code=<code>`, decomposed.
     *
     * The app scans nothing — it has no barcode decoder — but the payload is still worth parsing:
     * a phone's own camera app decodes a QR into text, and the holder's next move is to paste that
     * text somewhere. Pasting it into the host field fills BOTH fields instead of failing
     * validation.
     */
    data class Pairing(
        val edgeHost: String,
        val code: String,
    )

    /** Why what the holder typed cannot be used. One per field, so each renders under its own. */
    enum class Error {
        /** No host at all. */
        HOST_BLANK,

        /** Something was typed, but it is not a host name (or host:port). */
        HOST_INVALID,

        /** No code at all. */
        CODE_BLANK,
    }

    /** The scheme of the console's pairing link. */
    const val URI_SCHEME = "justceo-enrol"

    private const val URI_VERSION = "v1"
    private const val PARAM_HOST = "host"
    private const val PARAM_CODE = "code"

    private val WHITESPACE = Regex("\\s+")

    /**
     * A DNS name or IPv4 literal, with an optional port. Deliberately not a full URI grammar: the
     * field's contract is "the host, and nothing else", and anything that needs more grammar than
     * this — a bracketed IPv6 literal, a path — is a value the console does not issue.
     */
    private val HOST_PATTERN = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?$")

    /**
     * Reduces whatever was typed or pasted into the host field to a bare `host` or `host:port`.
     *
     * Whitespace is removed first, so a value broken across lines by a chat client is repaired
     * rather than rejected. Then a scheme, a path, a query and any `user@` prefix are dropped in
     * turn — which is what turns a pasted `wss://devices.example.com/ws/device` into
     * `devices.example.com`.
     */
    fun normaliseHost(raw: String): String {
        val compact = raw.replace(WHITESPACE, "")
        val withoutScheme = compact.substringAfter("://", compact)
        val authority = withoutScheme.substringBefore('/').substringBefore('?')
        return authority.substringAfterLast('@').lowercase()
    }

    /**
     * Removes every whitespace character from the code, including the ones inside it: a console
     * that renders `ABCD EFGH` for legibility must not mint a code the phone then refuses.
     */
    fun normaliseCode(raw: String): String = raw.replace(WHITESPACE, "")

    /** What is wrong with an already-normalised host, or null when it can be dialled. */
    fun hostError(normalisedHost: String): Error? =
        when {
            normalisedHost.isEmpty() -> Error.HOST_BLANK
            !HOST_PATTERN.matches(normalisedHost) -> Error.HOST_INVALID
            else -> null
        }

    /** What is wrong with an already-normalised code, or null when it can be redeemed. */
    fun codeError(normalisedCode: String): Error? = Error.CODE_BLANK.takeIf { normalisedCode.isEmpty() }

    /**
     * The first thing wrong with an already-normalised pair, or null when both are usable. The
     * host is reported first because it is the field above.
     */
    fun validate(
        normalisedHost: String,
        normalisedCode: String,
    ): Error? = hostError(normalisedHost) ?: codeError(normalisedCode)

    /**
     * Decomposes the console's pairing link, or returns null for anything that is not one — an
     * unknown scheme, a version this app does not understand, or a link missing either fact.
     * Returning null rather than a partial [Pairing] is what lets a caller treat "this paste is a
     * pairing link" as a single yes/no question.
     */
    fun parsePairingUri(raw: String): Pairing? {
        val trimmed = raw.trim()
        val body = trimmed.substringAfter(':', "")
        val isPairingLink =
            trimmed.startsWith("$URI_SCHEME:", ignoreCase = true) &&
                body.substringBefore('?').equals(URI_VERSION, ignoreCase = true)
        if (!isPairingLink) return null
        val params = queryParameters(body.substringAfter('?', ""))
        val host = normaliseHost(params[PARAM_HOST].orEmpty())
        val code = normaliseCode(params[PARAM_CODE].orEmpty())
        return if (host.isEmpty() || code.isEmpty()) null else Pairing(edgeHost = host, code = code)
    }

    /**
     * Splits a query string into decoded parameters. A value that is not valid percent-encoding is
     * kept verbatim instead of throwing: the caller still has to validate what comes out, and a
     * link that is merely mis-escaped should fail as "that host does not look right" rather than
     * as nothing happening at all.
     */
    private fun queryParameters(query: String): Map<String, String> =
        query
            .split('&')
            .filter { it.contains('=') }
            .associate { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=')
                name.lowercase() to runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
            }
}
