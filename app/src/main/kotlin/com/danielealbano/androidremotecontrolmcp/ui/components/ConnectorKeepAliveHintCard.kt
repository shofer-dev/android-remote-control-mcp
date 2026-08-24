// Matches ConnectorStatusCard.kt: composables are PascalCase by convention, and an @Preview
// function is only ever called by the tooling, which detekt cannot see.
@file:Suppress("FunctionNaming", "UnusedPrivateMember")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

/** Material's minimum touch target; Material 3 text buttons default below it. */
private const val MIN_TOUCH_TARGET_DP = 48

/**
 * The one-time hint that the two OEM settings which keep a background connector alive are the
 * holder's to grant — vendor autostart, and exemption from battery optimisation.
 *
 * It is deliberately a low-key surface card rather than a dialog or a warning: neither setting is
 * required for the app to work, nothing is blocked on them, and a modal that interrupts a phone's
 * first attachment to say "your OEM might kill this later" is the wrong trade. Dismissing it is
 * remembered for good (`connector_keep_alive_hint_dismissed`), and it is shown only once the
 * device is enrolled — before that there is no connector to keep alive.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectorKeepAliveHintCard(
    onOpenAutostart: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.connector_keepalive_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.connector_keepalive_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // FlowRow so the three labels wrap instead of truncating on a narrow screen or at a
            // large font scale.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                val buttonModifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                TextButton(onClick = onDismiss, modifier = buttonModifier) {
                    Text(stringResource(R.string.connector_keepalive_dismiss))
                }
                TextButton(onClick = onOpenBatterySettings, modifier = buttonModifier) {
                    Text(stringResource(R.string.connector_keepalive_battery))
                }
                TextButton(onClick = onOpenAutostart, modifier = buttonModifier) {
                    Text(stringResource(R.string.connector_keepalive_autostart))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectorKeepAliveHintCardPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorKeepAliveHintCard(
            onOpenAutostart = {},
            onOpenBatterySettings = {},
            onDismiss = {},
        )
    }
}
