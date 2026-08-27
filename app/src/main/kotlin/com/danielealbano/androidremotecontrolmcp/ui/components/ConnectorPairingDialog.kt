// Matches ConnectorStatusCard.kt: composables are PascalCase by convention, and an @Preview
// function is only ever called by the tooling, which detekt cannot see.
@file:Suppress("FunctionNaming", "UnusedPrivateMember")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PairingProgress

/** Matches the surrounding body text rather than dominating it. */
private const val PROGRESS_INDICATOR_SIZE_DP = 20

/** Material's minimum touch target; Material 3 buttons default to 40dp, which is below it. */
private const val MIN_TOUCH_TARGET_DP = 48

/**
 * Where a holder pairs a phone with the platform.
 *
 * **Scanning is the primary path, and typing is the fallback.** A one-time enrolment code is a
 * string with no redundancy in it, read off one screen and typed into another on a phone keyboard;
 * a single wrong character spends nothing, explains nothing, and looks exactly like an expired
 * code. Scanning removes the transcription entirely. The fallback is not decoration either — a code
 * read out over a phone call, a camera that will not focus on a bright monitor, and a declined
 * camera permission are all ordinary, and none of them may be a dead end for the only way to pair a
 * remote phone. So the manual form is reachable from the scanner in one tap, always, including when
 * the camera permission has been refused.
 *
 * The dialog reports the connector's OWN [PairingProgress] rather than a spinner of its own, so
 * what the holder sees is what the phone is actually doing — including the platform's refusal
 * verbatim, because "expired" and "already used" call for different next steps and only one of them
 * is a trip back to the console.
 */
@Composable
fun ConnectorPairingDialog(
    progress: PairingProgress,
    onSubmit: (host: String, code: String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val form = rememberSaveable(saver = PairingFormState.Saver) { PairingFormState() }
    if (progress is PairingProgress.Idle) {
        PairingEntryDialog(form = form, onSubmit = onSubmit, onDismiss = onDismiss)
    } else {
        PairingOutcomeDialog(progress = progress, onRetry = onRetry, onDismiss = onDismiss)
    }
}

/** The dialog before anything has been submitted: scan, or type. */
@Composable
private fun PairingEntryDialog(
    form: PairingFormState,
    onSubmit: (host: String, code: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connector_pair_title)) },
        text = {
            when (form.step) {
                PairingStep.SCAN -> {
                    PairingScanStep(
                        onScanned = { onSubmit(it.edgeHost, it.code) },
                        onManual = { form.step = PairingStep.MANUAL },
                    )
                }

                PairingStep.MANUAL -> {
                    PairingManualStep(form = form, onScan = { form.step = PairingStep.SCAN })
                }
            }
        },
        confirmButton = {
            // Scanning submits itself the moment it reads a code, so a confirm button exists only
            // for the typed path.
            if (form.step == PairingStep.MANUAL) {
                TextButton(
                    onClick = { onSubmit(form.normalisedHost, form.normalisedCode) },
                    enabled = form.canSubmit,
                ) {
                    Text(stringResource(R.string.connector_pair_submit))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.connector_pair_cancel))
            }
        },
    )
}

/** The typed fallback: the same two facts, entered by hand. */
@Composable
private fun PairingManualStep(
    form: PairingFormState,
    onScan: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = form.host,
            onValueChange = form::onHostTyped,
            label = { Text(stringResource(R.string.connector_pair_host_label)) },
            placeholder = { Text(stringResource(R.string.connector_pair_host_placeholder)) },
            isError = form.hostInvalid,
            supportingText = {
                if (form.hostInvalid) {
                    Text(stringResource(R.string.connector_pair_host_invalid))
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.code,
            onValueChange = { form.code = it },
            label = { Text(stringResource(R.string.connector_pair_code_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.connector_pair_manual_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onScan, modifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)) {
            Text(stringResource(R.string.connector_pair_scan_link))
        }
    }
}

/** The dialog once a pairing has been submitted: what the connector is doing about it. */
@Composable
private fun PairingOutcomeDialog(
    progress: PairingProgress,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val closeButton: (@Composable () -> Unit)? =
        if (progress is PairingProgress.Paired) {
            // "Done" already closes it; a second button that also closes it says nothing.
            null
        } else {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.connector_pair_close))
                }
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (progress is PairingProgress.Refused) {
                        R.string.connector_pair_failed
                    } else {
                        R.string.connector_pair_title
                    },
                ),
            )
        },
        text = { PairingOutcomeBody(progress) },
        confirmButton = {
            when (progress) {
                is PairingProgress.Paired -> {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.connector_pair_done)) }
                }

                is PairingProgress.Refused -> {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.connector_pair_retry)) }
                }

                else -> {
                    Unit
                }
            }
        },
        dismissButton = closeButton,
    )
}

/** A spinner while the handshake is in motion, and the connector's own words for where it is. */
@Composable
private fun PairingOutcomeBody(progress: PairingProgress) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (progress is PairingProgress.Starting || progress is PairingProgress.InProgress) {
            CircularProgressIndicator(modifier = Modifier.size(PROGRESS_INDICATOR_SIZE_DP.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(text = outcomeText(progress), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun outcomeText(progress: PairingProgress): String =
    when (progress) {
        is PairingProgress.Idle -> ""

        is PairingProgress.Starting -> stringResource(R.string.connector_pair_starting)

        is PairingProgress.InProgress -> progress.label

        is PairingProgress.Paired -> stringResource(R.string.connector_pair_paired)

        // The platform's own sentence when it gave one: an expired code and a spent one send the
        // holder to different places, and a generic failure sends them to the wrong one.
        is PairingProgress.Refused -> progress.reason ?: stringResource(R.string.connector_pair_failed_generic)
    }

@Preview(showBackground = true)
@Composable
private fun ConnectorPairingDialogEnrollingPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorPairingDialog(
            progress = PairingProgress.InProgress("Enrolling…"),
            onSubmit = { _, _ -> },
            onRetry = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectorPairingDialogRefusedPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorPairingDialog(
            progress = PairingProgress.Refused("that pairing code has already been used"),
            onSubmit = { _, _ -> },
            onRetry = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectorPairingDialogPairedPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorPairingDialog(
            progress = PairingProgress.Paired,
            onSubmit = { _, _ -> },
            onRetry = {},
            onDismiss = {},
        )
    }
}
