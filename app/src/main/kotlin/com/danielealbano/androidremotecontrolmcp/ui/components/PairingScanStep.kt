// Matches ConnectorStatusCard.kt: composables are PascalCase by convention, which detekt's
// FunctionNaming cannot see past.
@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.connector.PairingInput
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils

/** How tall the viewfinder is: enough to aim with, small enough to leave the dialog a dialog. */
private const val VIEWFINDER_HEIGHT_DP = 240

/** Material's minimum touch target; Material 3 buttons default to 40dp, which is below it. */
private const val MIN_TOUCH_TARGET_DP = 48

/**
 * The scanner, or the reason it cannot run — the primary way a holder pairs a phone.
 *
 * The CAMERA permission is asked for HERE, at the moment it is used, and with the rationale on
 * screen BEFORE the system dialog rather than after it. The way out is on screen the whole time:
 * "enter the details by hand" is visible whether the camera is granted, refused or never asked
 * for, because a refused permission must not dead-end the only way to pair a remote phone.
 */
@Composable
internal fun PairingScanStep(
    onScanned: (PairingInput.Pairing) -> Unit,
    onManual: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(PermissionUtils.isCameraPermissionGranted(context)) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            granted = allowed
            refused = !allowed
        }

    Column {
        if (granted) {
            Text(
                text = stringResource(R.string.connector_pair_scan_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            PairingQrScanner(
                onScanned = onScanned,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(VIEWFINDER_HEIGHT_DP.dp)
                        .semantics {
                            contentDescription = context.getString(R.string.connector_pair_scan_viewfinder)
                        },
            )
        } else {
            CameraPermissionPrompt(
                refused = refused,
                onAllow = { launcher.launch(Manifest.permission.CAMERA) },
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onManual,
            modifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
        ) {
            Text(stringResource(R.string.connector_pair_manual_link))
        }
    }
}

/**
 * Why the camera is wanted, and the one control that grants it. After a refusal the text changes to
 * say where the grant now has to be made, because a second tap on a permanently denied permission
 * shows the holder nothing at all.
 */
@Composable
private fun CameraPermissionPrompt(
    refused: Boolean,
    onAllow: () -> Unit,
) {
    Text(
        text =
            if (refused) {
                stringResource(R.string.connector_pair_camera_denied)
            } else {
                stringResource(R.string.connector_pair_camera_rationale)
            },
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onAllow,
        modifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
    ) {
        Text(stringResource(R.string.connector_pair_camera_allow))
    }
}
