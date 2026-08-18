@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.connector.TermsConsentBroker
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Full-screen, OS-warning-style enrolment-terms consent screen (§3.3 / §6.1 "company
 * governance" acknowledgement). It presents the platform terms mid-handshake and records the
 * holder's REAL acceptance of the terms hash (consent must be a user action, never
 * auto-accepted); the same screen serves re-consent after a `terms-required` at attach (Gap B).
 *
 * It is deliberately hard to dismiss silently: the screen is full-screen (system bars hidden),
 * shows over the lock screen, and BACK does not dismiss it — the holder must make an explicit
 * choice via the Accept / Decline buttons. Those buttons stay ordinary tappable Compose buttons
 * so the emulator/adb test path can drive them with `input tap`.
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
        showOverLockScreen()
        makeFullScreen()

        // BACK must not silently dismiss the governance acknowledgement — force an explicit
        // Accept/Decline. Consuming the callback leaves Decline as the only way out.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Intentionally a no-op: the holder must choose explicitly.
                }
            },
        )

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

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun makeFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

private const val WARNING_PADDING_DP = 24
private const val TITLE_GAP_DP = 8
private const val SECTION_GAP_DP = 16
private const val ACTIONS_GAP_DP = 24

@Composable
private fun TermsScreen(
    text: String,
    reAcceptance: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(WARNING_PADDING_DP.dp),
        ) {
            WarningHeader(reAcceptance)
            Spacer(Modifier.height(SECTION_GAP_DP.dp))
            Text(
                text = text.ifBlank { stringResource(R.string.connector_terms_empty) },
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(ACTIONS_GAP_DP.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(onClick = onDecline) {
                    Text(stringResource(R.string.connector_terms_decline))
                }
                Button(
                    onClick = onAccept,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) {
                    Text(stringResource(R.string.connector_terms_accept))
                }
            }
        }
    }
}

@Composable
private fun WarningHeader(reAcceptance: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(WARNING_PADDING_DP.dp)) {
            Text(
                text = stringResource(R.string.connector_terms_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(TITLE_GAP_DP.dp))
            Text(
                text =
                    stringResource(
                        if (reAcceptance) {
                            R.string.connector_terms_reaccept_notice
                        } else {
                            R.string.connector_terms_warning
                        },
                    ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
