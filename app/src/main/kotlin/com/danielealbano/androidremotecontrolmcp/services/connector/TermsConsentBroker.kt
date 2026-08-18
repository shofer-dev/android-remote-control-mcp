package com.danielealbano.androidremotecontrolmcp.services.connector

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mediates the mid-handshake terms consent between the [PlatformConnector] (background) and
 * the [com.danielealbano.androidremotecontrolmcp.ui.ConnectorTermsActivity] (foreground).
 *
 * The protocol forces consent to happen INSIDE the handshake: `enroll` → the gateway returns
 * `terms{terms_text, terms_hash}` → the device must send `accept{code, terms_hash}` on the
 * same connection (wire spec §3). There is no way to pre-fetch and pre-accept. So when the
 * connector receives `terms`, it publishes a [PendingTerms] here and suspends on a deferred;
 * the activity renders the text, the user makes a REAL choice (tap Accept / Decline — never
 * auto-accepted), and [submitDecision] completes the deferred so the connector proceeds.
 *
 * A single `@Singleton` broker is the rendezvous because the activity is a separate process
 * component from the service and cannot be handed a coroutine continuation directly.
 */
@Singleton
class TermsConsentBroker
    @Inject
    constructor() {
        private val _pending = MutableStateFlow<PendingTerms?>(null)

        /** The terms awaiting a decision, or null. Observed by the terms activity. */
        val pending: StateFlow<PendingTerms?> = _pending.asStateFlow()

        @Volatile private var decision: CompletableDeferred<Boolean>? = null

        /**
         * Publishes terms for the user to decide on and returns a deferred that completes with
         * the user's choice (true = accepted). If a request is already pending it is superseded
         * (the newest terms win) and the old deferred is failed so its awaiter unwinds.
         */
        @Synchronized
        fun request(terms: PendingTerms): CompletableDeferred<Boolean> {
            decision?.takeIf { !it.isCompleted }?.cancel()
            val deferred = CompletableDeferred<Boolean>()
            decision = deferred
            _pending.value = terms
            return deferred
        }

        /** Records the user's decision for [termsHash]; ignored if it does not match the pending. */
        @Synchronized
        fun submitDecision(
            termsHash: String,
            accepted: Boolean,
        ) {
            val current = _pending.value
            if (current == null || current.termsHash != termsHash) return
            decision?.complete(accepted)
            decision = null
            _pending.value = null
        }

        /** The published terms the user must decide on. */
        data class PendingTerms(
            val termsText: String,
            val termsHash: String,
            /** True when raised by a `terms-required` at attach — see the wire spec Q2 dead-end. */
            val reAcceptance: Boolean = false,
        )
    }
