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
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.selfupdate.SelfUpdateState
import com.danielealbano.androidremotecontrolmcp.services.selfupdate.UpdateAvailability
import com.danielealbano.androidremotecontrolmcp.services.selfupdate.UpdateInstall
import com.danielealbano.androidremotecontrolmcp.services.selfupdate.UpdateSpec
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

/** Material's minimum touch target; Material 3 buttons default to 40dp, which is below it. */
private const val MIN_TOUCH_TARGET_DP = 48

/**
 * The holder's way to apply a newer build — and, when the platform pushed one, their way to see
 * that it is happening.
 *
 * The app is sideloaded, so without this card the only way to a new version is somebody fetching an
 * APK by hand. The card appears only when the platform has told this device that it publishes a
 * version it is not running; a device that is current shows nothing, because a permanent "You are
 * up to date" row is a line nobody reads and a line that is wrong the moment it is stale.
 *
 * It renders the INSTALL as well as the offer, and that is not decoration: the platform can push an
 * update at a phone nobody is touching, so the holder may open the app to find one already in
 * flight. Every state says what it is waiting for, including the one where Android has decided to
 * ask them — [UpdateInstall.AwaitingConfirmation] — because at that point the confirmation is in
 * the notification shade and the card is the only thing that can say so.
 *
 * The refresh control is always offered, even while the answer is "up to date": the question is
 * about the PLATFORM's state, and a holder who has just been told a build was published needs a way
 * to ask again without waiting for a reconnect.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectorUpdateCard(
    state: SelfUpdateState,
    onUpdate: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = state.availability as? UpdateAvailability.Available ?: return

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.connector_update_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.connector_update_available, available.spec.version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val progress = installMessage(state.install)
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = progress,
                    style = MaterialTheme.typography.bodyMedium,
                    color = installColour(state.install),
                )
            }

            Spacer(Modifier.height(8.dp))
            // FlowRow so the labels wrap instead of truncating on a narrow screen or at a large
            // font scale.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                val buttonModifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                TextButton(onClick = onRefresh, modifier = buttonModifier) {
                    Text(stringResource(R.string.connector_update_refresh))
                }
                Button(
                    onClick = onUpdate,
                    enabled = isIdle(state.install),
                    modifier = buttonModifier,
                ) {
                    Text(stringResource(R.string.connector_update_action))
                }
            }
        }
    }
}

/**
 * Whether the Update button may be pressed. A failed attempt is idle again on purpose: the usual
 * failure is a flaky download, and the remedy is the same button.
 */
private fun isIdle(install: UpdateInstall): Boolean = install is UpdateInstall.Idle || install is UpdateInstall.Failed

/** What the card says an in-flight update is doing, or null when nothing is happening. */
@Composable
private fun installMessage(install: UpdateInstall): String? =
    when (install) {
        is UpdateInstall.Idle -> null
        is UpdateInstall.Downloading -> stringResource(R.string.connector_update_downloading)
        is UpdateInstall.Installing -> stringResource(R.string.connector_update_installing)
        is UpdateInstall.AwaitingConfirmation -> stringResource(R.string.connector_update_confirm)
        is UpdateInstall.Failed -> stringResource(R.string.connector_update_failed, install.details)
    }

/** A failed attempt is the one message on this card that has to look like a problem. */
@Composable
private fun installColour(install: UpdateInstall): Color =
    if (install is UpdateInstall.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

private val PREVIEW_SPEC = UpdateSpec(url = "https://example.invalid/app.apk", sha256 = "ab", version = "g953943d7f944")

@Preview(showBackground = true, name = "An update is offered")
@Composable
private fun ConnectorUpdateCardAvailablePreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorUpdateCard(
            state = SelfUpdateState(availability = UpdateAvailability.Available(PREVIEW_SPEC)),
            onUpdate = {},
            onRefresh = {},
        )
    }
}

@Preview(showBackground = true, name = "Android is asking the holder to confirm")
@Composable
private fun ConnectorUpdateCardAwaitingConfirmationPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorUpdateCard(
            state =
                SelfUpdateState(
                    availability = UpdateAvailability.Available(PREVIEW_SPEC),
                    install = UpdateInstall.AwaitingConfirmation,
                ),
            onUpdate = {},
            onRefresh = {},
        )
    }
}

@Preview(showBackground = true, name = "The attempt failed")
@Composable
private fun ConnectorUpdateCardFailedPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorUpdateCard(
            state =
                SelfUpdateState(
                    availability = UpdateAvailability.Available(PREVIEW_SPEC),
                    install =
                        UpdateInstall.Failed(
                            error = "download-failed",
                            details = "the publisher answered HTTP 404",
                        ),
                ),
            onUpdate = {},
            onRefresh = {},
        )
    }
}
