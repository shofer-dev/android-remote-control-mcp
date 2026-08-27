@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorStatus
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConnectorUiState
import com.danielealbano.androidremotecontrolmcp.utils.DurationFormat

private const val STATUS_DOT_SIZE_DP = 12
private const val ANIMATION_DURATION_MS = 300

/** Material's minimum touch target; Material 3 buttons default to 40dp, which is below it. */
private const val MIN_TOUCH_TARGET_DP = 48

/**
 * The device's attachment to the platform, as the PLATFORM sees it.
 *
 * The headline reads the already-grounded [ConnectorUiState.status], so it says "Reconnecting"
 * the moment the gateway stops answering rather than when a socket finally reports itself closed.
 * The heartbeat row underneath is what makes that claim checkable by eye: it is the age of the
 * last answer the platform gave, ticking, and it keeps ticking while the state reads
 * "Reconnecting" so the holder can see how long the silence has lasted.
 *
 * The card also CONTROLS the connector, because a status card that can only report "Stopped" on a
 * phone whose OEM just killed the service leaves the holder with nothing to do but reboot. Stop is
 * an explicit veto the self-heal paths respect
 * ([com.danielealbano.androidremotecontrolmcp.services.connector.ConnectorAutoStart]), so the card
 * says so underneath rather than leaving a deliberate stop looking like a failure.
 *
 * **Unprovision** is the third control and the destructive one: it forgets this phone's platform
 * identity so it can be provisioned again. It lives HERE, beside the status, rather than in a
 * settings screen, because the person who needs it is the one reading a refusal on this very card
 * — so it is offered for as long as the device holds an identity ([ConnectorUiState.isEnrolled]),
 * which includes every halted state and every attach error. It is confirmed, and the confirmation
 * says what it does and what it does not touch.
 */
@Composable
fun ConnectorStatusCard(
    state: ConnectorUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUnprovision: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingUnprovision by rememberSaveable { mutableStateOf(false) }
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.connector_card_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(12.dp))

            StatusHeadline(state.status, state.countdownMillis)

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            ConnectorDetails(state)

            Spacer(Modifier.height(12.dp))
            LifecycleControl(
                isRunning = state.isRunning,
                canUnprovision = state.isEnrolled,
                onStart = onStart,
                onStop = onStop,
                onUnprovision = { confirmingUnprovision = true },
            )
        }
    }

    if (confirmingUnprovision) {
        ConnectorUnprovisionDialog(
            onConfirm = {
                confirmingUnprovision = false
                onUnprovision()
            },
            onDismiss = { confirmingUnprovision = false },
        )
    }
}

/**
 * The card's facts, in the order a holder reads them: which platform, which device, whether it is
 * enrolled, and the two ages that make "Connected" falsifiable. The deliberate-stop note lives
 * here too, because a connector that is down on purpose looks identical to one that is down by
 * accident unless something says so.
 */
@Composable
private fun ConnectorDetails(state: ConnectorUiState) {
    DetailRow(
        label = stringResource(R.string.connector_card_host),
        value = state.edgeHost.ifBlank { stringResource(R.string.connector_card_unknown) },
    )
    DetailRow(
        label = stringResource(R.string.connector_card_device),
        value = state.deviceIdShort.ifBlank { stringResource(R.string.connector_card_unknown) },
    )
    DetailRow(
        label = stringResource(R.string.connector_card_enrolled),
        value =
            if (state.isEnrolled) {
                stringResource(R.string.connector_card_yes)
            } else {
                stringResource(R.string.connector_card_no)
            },
    )
    DetailRow(
        label = stringResource(R.string.connector_card_heartbeat),
        value = agoOrUnknown(state.lastServerHeartbeatAgoMillis),
    )
    DetailRow(
        label = stringResource(R.string.connector_card_uptime),
        value =
            state.attachUptimeMillis?.let { DurationFormat.short(it) }
                ?: stringResource(R.string.connector_card_unknown),
    )

    if (state.stoppedByUser && !state.isRunning) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.connector_card_stopped_by_user),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The card's controls. Start/Stop is one button in whichever direction is currently meaningful —
 * a filled button for Start (the action a holder looking at a dead connector wants) and an
 * outlined one for Stop (deliberate, not routine).
 *
 * Unprovision sits opposite them, as a plain text button in the error colour: available whenever
 * there is an identity to forget, but never the button a thumb lands on by accident.
 */
@Composable
private fun LifecycleControl(
    isRunning: Boolean,
    canUnprovision: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUnprovision: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val buttonModifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
        if (canUnprovision) {
            TextButton(onClick = onUnprovision, modifier = buttonModifier) {
                Text(
                    text = stringResource(R.string.connector_card_unprovision),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Spacer(Modifier.width(0.dp))
        }
        if (isRunning) {
            OutlinedButton(onClick = onStop, modifier = buttonModifier) {
                Text(stringResource(R.string.connector_card_stop))
            }
        } else {
            Button(onClick = onStart, modifier = buttonModifier) {
                Text(stringResource(R.string.connector_card_start))
            }
        }
    }
}

@Composable
private fun agoOrUnknown(ageMillis: Long?): String =
    if (ageMillis == null) {
        stringResource(R.string.connector_card_unknown)
    } else {
        stringResource(R.string.connector_card_ago, DurationFormat.short(ageMillis))
    }

@Composable
private fun StatusHeadline(
    status: ConnectorStatus,
    countdownMillis: Long?,
) {
    val statusColor = statusColor(status, isSystemInDarkTheme())
    val animatedColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
        label = "connectorStatusColor",
    )
    val headline = status.notificationLabel
    val detail =
        (status as? ConnectorStatus.Halted)?.reason
            ?: countdownMillis?.let { stringResource(R.string.connector_card_retry_in, DurationFormat.short(it)) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            modifier =
                Modifier
                    .size(STATUS_DOT_SIZE_DP.dp)
                    .semantics {
                        contentDescription = "Platform connector status: $headline"
                    },
        ) {
            drawCircle(color = animatedColor)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = headline, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Green only for a link the platform has confirmed and is still answering on; amber for anything
 * in motion; red for a state that will not resolve without the holder doing something.
 */
private fun statusColor(
    status: ConnectorStatus,
    isDarkTheme: Boolean,
): Color =
    when (status) {
        is ConnectorStatus.Connected -> if (isDarkTheme) Color(0xFF81C784) else Color(0xFF4CAF50)
        is ConnectorStatus.Paused -> if (isDarkTheme) Color(0xFF64B5F6) else Color(0xFF1E88E5)
        is ConnectorStatus.Halted -> if (isDarkTheme) Color(0xFFEF5350) else Color(0xFFF44336)
        is ConnectorStatus.Stopped -> if (isDarkTheme) Color(0xFFEF5350) else Color(0xFFF44336)
        is ConnectorStatus.NeedsConfig, is ConnectorStatus.NotEnrolled -> Color.Gray
        else -> if (isDarkTheme) Color(0xFFFFD54F) else Color(0xFFFFC107)
    }

@Preview(showBackground = true)
@Composable
private fun ConnectorStatusCardConnectedPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorStatusCard(
            state =
                ConnectorUiState(
                    status = ConnectorStatus.Connected(attachedSinceMillis = 0, lastServerHeartbeatMillis = 0),
                    deviceIdShort = "7f3ab21c",
                    edgeHost = "devices.justceo.ai",
                    isEnrolled = true,
                    lastServerHeartbeatAgoMillis = 3_000,
                    attachUptimeMillis = 3_725_000,
                ),
            onStart = {},
            onStop = {},
            onUnprovision = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectorStatusCardStoppedByUserPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorStatusCard(
            state =
                ConnectorUiState(
                    status = ConnectorStatus.Stopped,
                    deviceIdShort = "7f3ab21c",
                    edgeHost = "devices.justceo.ai",
                    isEnrolled = true,
                    stoppedByUser = true,
                ),
            onStart = {},
            onStop = {},
            onUnprovision = {},
        )
    }
}

/**
 * A halted card, in the state Unprovision exists for: an enrolled device the platform refuses
 * because it no longer holds a record of it. The control is visible precisely here.
 */
@Preview(showBackground = true)
@Composable
private fun ConnectorStatusCardHaltedPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectorStatusCard(
            state =
                ConnectorUiState(
                    status = ConnectorStatus.AttachRejected("credential-service refused: unknown-device"),
                    deviceIdShort = "7f3ab21c",
                    edgeHost = "devices.justceo.ai",
                    isEnrolled = true,
                ),
            onStart = {},
            onStop = {},
            onUnprovision = {},
        )
    }
}
