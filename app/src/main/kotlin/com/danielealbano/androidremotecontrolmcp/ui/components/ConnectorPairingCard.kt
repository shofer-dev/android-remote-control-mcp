// Matches the other connector components: composables are PascalCase by convention, and an
// @Preview function is only ever called by the tooling, which detekt cannot see.
@file:Suppress("FunctionNaming", "UnusedPrivateMember")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

/** Material's minimum touch target; Material 3 buttons default to 40dp, which is below it. */
private const val MIN_TOUCH_TARGET_DP = 48

/**
 * The way in for a phone that nobody has paired yet.
 *
 * It exists because a REMOTE phone has no other one. A tethered or emulated device is configured
 * over a cable by its host, which sends the edge host and the enrolment code as an adb broadcast; a
 * phone in someone's hand has no host, no cable and nobody at a terminal, so without this card the
 * only surface it can show is a connector reporting that it is not enrolled and offering a Start
 * button that cannot help.
 *
 * It is shown on exactly the condition that makes it true — the device holds no platform identity —
 * rather than on a particular connector status. Status alone would miss the case it exists for: a
 * freshly installed app has never run its connector service, so it reports `Stopped` rather than
 * `NotEnrolled`, and gating on the latter would hide pairing precisely on a new phone. The same
 * predicate keeps it visible after a refused code and after an unprovision, which are the other two
 * moments a holder needs it, and hides it the instant the phone is enrolled.
 */
@Composable
fun ConnectorPairingCard(
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.connector_pair_card_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.connector_pair_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onPair,
                modifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
            ) {
                Text(stringResource(R.string.connector_pair_card_action))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectorPairingCardPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorPairingCard(onPair = {})
    }
}
