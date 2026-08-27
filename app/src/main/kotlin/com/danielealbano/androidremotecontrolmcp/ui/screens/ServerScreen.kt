@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.permissions.PermissionRemedy
import com.danielealbano.androidremotecontrolmcp.services.permissions.RequiredPermission
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectorKeepAliveHintCard
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectorStatusCard
import com.danielealbano.androidremotecontrolmcp.ui.components.PermissionsHintCard
import com.danielealbano.androidremotecontrolmcp.ui.components.ScreenStreamCard
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConnectorViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PermissionAuditViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ScreenStreamViewModel
import com.danielealbano.androidremotecontrolmcp.utils.OemKeepAliveSettings
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils

/**
 * The device's home screen. In platform mode the ONE thing it has to answer is whether the
 * platform currently holds this device, so the connector card is the whole surface; the
 * standalone-mode cards (the local MCP server, the event channel, the LAN address to point a
 * client at) are not shown, because every one of them reads "stopped" on a device that is fully
 * attached and working.
 *
 * Above it sits the NEEDS-ATTENTION area: the permissions audit when something required is
 * missing, and — once enrolled — the keep-alive hint. The order is deliberate. A device that
 * cannot be driven because accessibility is off will happily report "Connected", so the fault has
 * to be the first thing on the screen rather than something found by going looking for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onNavigateToPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    connectorViewModel: ConnectorViewModel = hiltViewModel(),
    permissionAuditViewModel: PermissionAuditViewModel = hiltViewModel(),
    screenStreamViewModel: ScreenStreamViewModel = hiltViewModel(),
) {
    val connectorState by connectorViewModel.uiState.collectAsStateWithLifecycle()
    val keepAliveHintVisible by connectorViewModel.keepAliveHintVisible.collectAsStateWithLifecycle()
    val screenStreamArmed by screenStreamViewModel.armed.collectAsStateWithLifecycle()
    val auditState by permissionAuditViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            if (auditState.cardVisible) {
                PermissionsHintCard(
                    missing = auditState.missing,
                    onFix = { permission ->
                        openRemedy(
                            permission = permission,
                            openRuntimePermissions = onNavigateToPermissions,
                            context = context,
                        )
                    },
                    onDismiss = permissionAuditViewModel::dismiss,
                )
                Spacer(Modifier.height(16.dp))
            }

            ConnectorStatusCard(
                state = connectorState,
                onStart = connectorViewModel::start,
                onStop = connectorViewModel::stop,
                onUnprovision = connectorViewModel::unprovision,
            )

            Spacer(Modifier.height(16.dp))
            // Sits under the connector card because it is only meaningful for a phone the platform
            // holds: arming a device nothing is attached to arms it for nobody.
            ScreenStreamCard(
                armed = screenStreamArmed,
                onArm = screenStreamViewModel::arm,
                onDisarm = screenStreamViewModel::disarm,
            )

            if (keepAliveHintVisible) {
                Spacer(Modifier.height(16.dp))
                ConnectorKeepAliveHintCard(
                    onOpenAutostart = { OemKeepAliveSettings.openAutostart(context) },
                    onOpenBatterySettings = { OemKeepAliveSettings.openBatteryOptimization(context) },
                    onDismiss = connectorViewModel::dismissKeepAliveHint,
                )
            }
        }
    }
}

/**
 * Sends the holder to the one place this grant is actually made.
 *
 * A RUNTIME permission routes to the app's OWN permissions screen rather than to a system
 * settings page, because that screen can still invoke the runtime dialog — and because a holder
 * who has revoked one permission has usually revoked several, so showing the full list is more
 * use than deep-linking to one row. [PermissionRemedy.KEEP_ALIVE_CARD] never reaches here: the
 * card renders a cross-reference for it instead of a button.
 */
private fun openRemedy(
    permission: RequiredPermission,
    openRuntimePermissions: () -> Unit,
    context: Context,
) {
    when (permission.remedy) {
        PermissionRemedy.RUNTIME_REQUEST -> openRuntimePermissions()
        PermissionRemedy.ACCESSIBILITY_SETTINGS -> PermissionUtils.openAccessibilitySettings(context)
        PermissionRemedy.DEVICE_ADMIN_ACTIVATION -> OemKeepAliveSettings.openDeviceAdminActivation(context)
        PermissionRemedy.NOTIFICATION_LISTENER_SETTINGS -> PermissionUtils.openNotificationListenerSettings(context)
        PermissionRemedy.KEEP_ALIVE_CARD -> OemKeepAliveSettings.openBatteryOptimization(context)
    }
}
