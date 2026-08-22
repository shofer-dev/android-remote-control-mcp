@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectorStatusCard
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConnectorViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

/**
 * The device's home screen. In platform mode the ONE thing it has to answer is whether the
 * platform currently holds this device, so the connector card is the whole surface; the
 * standalone-mode cards (the local MCP server, the event channel, the LAN address to point a
 * client at) are not shown, because every one of them reads "stopped" on a device that is fully
 * attached and working.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onNavigateToPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    connectorViewModel: ConnectorViewModel = hiltViewModel(),
) {
    val connectorState by connectorViewModel.uiState.collectAsStateWithLifecycle()

    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val isNotificationPermissionGranted by viewModel.isNotificationPermissionGranted.collectAsStateWithLifecycle()
    val isCameraPermissionGranted by viewModel.isCameraPermissionGranted.collectAsStateWithLifecycle()
    val isMicrophonePermissionGranted by viewModel.isMicrophonePermissionGranted.collectAsStateWithLifecycle()
    val isNotificationListenerEnabled by viewModel.isNotificationListenerEnabled.collectAsStateWithLifecycle()

    val hasAllPermissions =
        isAccessibilityEnabled &&
            isNotificationPermissionGranted &&
            isCameraPermissionGranted &&
            isMicrophonePermissionGranted &&
            isNotificationListenerEnabled

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_server)) },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            if (!hasAllPermissions) {
                PermissionWarningCard(onClick = onNavigateToPermissions)
                Spacer(Modifier.height(16.dp))
            }

            ConnectorStatusCard(state = connectorState)
        }
    }
}

@Composable
private fun PermissionWarningCard(onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.permission_warning_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
