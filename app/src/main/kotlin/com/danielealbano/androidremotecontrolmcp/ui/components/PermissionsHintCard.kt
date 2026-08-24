// Matches ConnectorStatusCard.kt: composables are PascalCase by convention, and an @Preview
// function is only ever called by the tooling, which detekt cannot see.
@file:Suppress("FunctionNaming", "UnusedPrivateMember")

package com.danielealbano.androidremotecontrolmcp.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.permissions.PermissionRemedy
import com.danielealbano.androidremotecontrolmcp.services.permissions.RequiredPermission
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

/** Material's minimum touch target; Material 3 text buttons default below it. */
private const val MIN_TOUCH_TARGET_DP = 48

private const val ROW_ICON_SIZE_DP = 20

/**
 * The permissions audit, as a card: every grant this device is missing, why it matters in the
 * holder's own terms, and a button that goes to the exact place it is granted.
 *
 * It is a sibling of [ConnectorKeepAliveHintCard] and shares its visual language, but NOT its
 * dismissal semantics — see
 * [com.danielealbano.androidremotecontrolmcp.services.permissions.PermissionAuditor]. The
 * keep-alive card is advice about a risk and can be dismissed forever; this one reports a fault
 * that is true right now, so "Not now" lasts until the next evaluation and no longer.
 *
 * Battery optimisation appears as a row with no button, pointing at the keep-alive card below
 * it. That is a deliberate cross-reference rather than a duplicated control: the same destination
 * with less explanation would be a worse version of a card the holder can already see.
 */
@Composable
fun PermissionsHintCard(
    missing: List<RequiredPermission>,
    onFix: (RequiredPermission) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasOperational = missing.any { it.isOperational }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (hasOperational) Icons.Default.Error else Icons.Default.Warning,
                    contentDescription = null,
                    tint =
                        if (hasOperational) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.permission_audit_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text =
                    if (hasOperational) {
                        stringResource(R.string.permission_audit_body)
                    } else {
                        stringResource(R.string.permission_audit_body_tools)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            missing.forEach { permission ->
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                MissingPermissionRow(permission = permission, onFix = { onFix(permission) })
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
                ) {
                    Text(stringResource(R.string.permission_audit_dismiss))
                }
            }
        }
    }
}

@Composable
private fun MissingPermissionRow(
    permission: RequiredPermission,
    onFix: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            tint =
                if (permission.isOperational) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(ROW_ICON_SIZE_DP.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(permission.labelRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(permission.reasonRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (permission.remedy == PermissionRemedy.KEEP_ALIVE_CARD) {
            Text(
                text = stringResource(R.string.permission_audit_see_below),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TextButton(
                onClick = onFix,
                modifier = Modifier.defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
            ) {
                Text(stringResource(R.string.permission_audit_fix))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionsHintCardOperationalPreview() {
    AndroidRemoteControlMcpTheme {
        PermissionsHintCard(
            missing =
                listOf(
                    RequiredPermission.ACCESSIBILITY_SERVICE,
                    RequiredPermission.BATTERY_OPTIMIZATION_EXEMPTION,
                    RequiredPermission.CAMERA,
                ),
            onFix = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionsHintCardToolsOnlyPreview() {
    AndroidRemoteControlMcpTheme {
        PermissionsHintCard(
            missing = listOf(RequiredPermission.CAMERA, RequiredPermission.MICROPHONE),
            onFix = {},
            onDismiss = {},
        )
    }
}
