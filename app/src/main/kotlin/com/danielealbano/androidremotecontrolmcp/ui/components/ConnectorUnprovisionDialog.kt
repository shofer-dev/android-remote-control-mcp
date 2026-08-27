// Matches ConnectorStatusCard.kt: composables are PascalCase by convention, and an @Preview
// function is only ever called by the tooling, which detekt cannot see.
@file:Suppress("FunctionNaming", "UnusedPrivateMember")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

/**
 * The confirmation in front of [ConnectorStatusCard]'s Unprovision control.
 *
 * The act it guards is destructive and cannot be undone on the phone, so the dialog states BOTH
 * halves plainly rather than asking "are you sure": what stops being true (this phone forgets its
 * platform identity and its pairing key, and is no longer a platform device until it is
 * provisioned again) and what this control has no power over (the platform's own record of the
 * device and everything already recorded about it). An operator reaching for this after deleting
 * a device record needs to know the two are separate — that is the whole reason the phone could
 * be stranded in the first place.
 */
@Composable
fun ConnectorUnprovisionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connector_card_unprovision_title)) },
        text = { Text(stringResource(R.string.connector_card_unprovision_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.connector_card_unprovision_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.connector_card_unprovision_cancel))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ConnectorUnprovisionDialogPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorUnprovisionDialog(onConfirm = {}, onDismiss = {})
    }
}
