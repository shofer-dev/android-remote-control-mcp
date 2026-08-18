@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.connector.TermsConsentBroker
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Presents the enrolment terms mid-handshake and records the user's REAL acceptance of the
 * terms hash (wire spec §3; consent must be a user action, never auto-accepted). Wave 3
 * replaces this with the full-screen OS-warning-style screen; this is the functional plain
 * version so enrolment completes end to end for testing.
 *
 * The terms to show come from [TermsConsentBroker.pending]; the decision is reported through
 * [TermsConsentBroker.submitDecision], which unblocks the connector's suspended handshake. If
 * the pending request clears (superseded or already answered), the activity finishes.
 */
@AndroidEntryPoint
class ConnectorTermsActivity : ComponentActivity() {
    @Inject lateinit var termsBroker: TermsConsentBroker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidRemoteControlMcpTheme {
                val pending by termsBroker.pending.collectAsState()
                val terms = pending
                if (terms == null) {
                    finish()
                } else {
                    TermsScreen(
                        text = terms.termsText,
                        reAcceptance = terms.reAcceptance,
                        onAccept = {
                            termsBroker.submitDecision(terms.termsHash, accepted = true)
                            finish()
                        },
                        onDecline = {
                            termsBroker.submitDecision(terms.termsHash, accepted = false)
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TermsScreen(
    text: String,
    reAcceptance: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Scaffold { inner ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(PaddingValues(24.dp)),
        ) {
            Text(text = stringResource(R.string.connector_terms_title))
            Spacer(Modifier.height(16.dp))
            if (reAcceptance) {
                Text(
                    text =
                        "The platform requires terms re-acceptance. Note: an already-enrolled " +
                            "device cannot re-consent without a fresh pairing code.",
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = text.ifBlank { "No terms text was provided by the platform." },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(onClick = onDecline) {
                    Text(stringResource(R.string.connector_terms_decline))
                }
                Button(onClick = onAccept) {
                    Text(stringResource(R.string.connector_terms_accept))
                }
            }
        }
    }
}
