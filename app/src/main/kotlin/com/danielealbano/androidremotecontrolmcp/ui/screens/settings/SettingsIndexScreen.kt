@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsIndexScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_settings)) },
            windowInsets = WindowInsets(0),
        )
        SettingsEntriesColumn(
            onNavigate = onNavigate,
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * The settings the holder of a PLATFORM-attached device can act on.
 *
 * The standalone-mode entries are deliberately absent rather than deleted: the local Ktor
 * server's address and port, and the event channel's outbound webhook, still exist and are still
 * reachable over the adb configuration broadcast for debugging, but neither runs when the device
 * is attached, so offering either as a setting would invite a holder to change something that
 * does nothing. The device slug went the same way for a stronger reason — it prefixes the MCP
 * tool names the connector serves over the relay, so editing it would silently break dispatch
 * from the platform.
 *
 * What remains all governs behaviour the connector DOES exercise: which tools a relayed command
 * may reach, the OS permissions those tools need, and the storage locations and download limits
 * the file tools operate under.
 */
@Composable
private fun SettingsEntriesColumn(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SettingsEntry(
            icon = Icons.Default.Build,
            title = stringResource(R.string.settings_mcp_tools_title),
            subtitle = stringResource(R.string.settings_mcp_tools_subtitle),
            onClick = { onNavigate(SettingsRoute.McpTools.route) },
        )
        SettingsEntry(
            icon = Icons.Default.AdminPanelSettings,
            title = stringResource(R.string.settings_permissions_title),
            subtitle = stringResource(R.string.settings_permissions_subtitle),
            onClick = { onNavigate(SettingsRoute.Permissions.route) },
        )
        SettingsEntry(
            icon = Icons.Default.Folder,
            title = stringResource(R.string.settings_storage_title),
            subtitle = stringResource(R.string.settings_storage_subtitle),
            onClick = { onNavigate(SettingsRoute.Storage.route) },
        )
    }
}

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
