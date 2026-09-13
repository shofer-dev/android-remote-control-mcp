@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.permissions.RemedyCapabilities
import com.danielealbano.androidremotecontrolmcp.services.permissions.RemedyDestination
import com.danielealbano.androidremotecontrolmcp.services.permissions.RemedyRouter
import com.danielealbano.androidremotecontrolmcp.services.permissions.RequiredPermission
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectorKeepAliveHintCard
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectorPairingCard
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectorPairingDialog
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectorStatusCard
import com.danielealbano.androidremotecontrolmcp.ui.components.PermissionsHintCard
import com.danielealbano.androidremotecontrolmcp.ui.components.ScreenStreamCard
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConnectorViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PermissionAuditViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ScreenStreamViewModel
import com.danielealbano.androidremotecontrolmcp.utils.OemKeepAliveSettings
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import com.danielealbano.androidremotecontrolmcp.utils.canStartSettingsActivity
import com.danielealbano.androidremotecontrolmcp.utils.startSettingsActivity

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
            PermissionsAudit(
                viewModel = permissionAuditViewModel,
                onNavigateToPermissions = onNavigateToPermissions,
            )

            // Above the status card, because on an unpaired phone the status card can only report
            // that there is nothing to report — and pairing is the one action that changes it.
            ConnectorPairing(isEnrolled = connectorState.isEnrolled, viewModel = connectorViewModel)

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
 * The permissions checklist, with everything it needs to act on itself.
 *
 * Three things live here rather than in [ServerScreen] because they are one mechanism:
 *
 * - the RE-READ on every resume, which is what makes a grant made outside the app clear its row.
 *   On a racked phone most grants arrive over adb from the host box (`dpm set-active-admin`,
 *   `dumpsys deviceidle whitelist +pkg`, `settings put secure`), and the app-wide foreground hook
 *   in `McpApplication` cannot see those: it fires on a process background→foreground transition,
 *   which an adb grant to an app already in the foreground never causes;
 * - the result LAUNCHER, which re-reads the audit however the system screen ends — granted,
 *   cancelled, or closed instantly by a vendor build that never really showed it;
 * - the build's CAPABILITIES, read once, because which system prompts exist cannot change while
 *   the screen is up and asking `PackageManager` per recomposition would be a resolution storm.
 */
@Composable
private fun PermissionsAudit(
    viewModel: PermissionAuditViewModel,
    onNavigateToPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val auditState by viewModel.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val capabilities = remember(context) { remedyCapabilities(context) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refresh()
        }

    if (!auditState.cardVisible) return

    PermissionsHintCard(
        missing = auditState.missing,
        onFix = { permission ->
            openRemedy(
                permission = permission,
                capabilities = capabilities,
                openRuntimePermissions = onNavigateToPermissions,
                context = context,
                launcher = launcher,
            )
        },
        onDismiss = viewModel::dismiss,
        notes = remedyNotes(capabilities),
    )
    Spacer(Modifier.height(16.dp))
}

/**
 * The pairing prompt and the dialog it opens.
 *
 * The two have DIFFERENT lifetimes, and that is the point. The prompt is gone the moment the phone
 * holds an identity, because there is nothing left to prompt for — but the dialog is not, or a
 * pairing would disappear at the exact instant it succeeded: `phoneId` is persisted when the
 * platform answers `enrolled`, one step BEFORE the attach, so tying the dialog to the same
 * condition would take it off screen mid-handshake and never show the holder that it worked.
 *
 * The open/closed flag lives here rather than in [ServerScreen] because nothing else on the screen
 * has any business knowing whether a dialog is up.
 */
@Composable
private fun ConnectorPairing(
    isEnrolled: Boolean,
    viewModel: ConnectorViewModel,
) {
    var open by rememberSaveable { mutableStateOf(false) }

    if (!isEnrolled) {
        ConnectorPairingCard(onPair = { open = true })
        Spacer(Modifier.height(16.dp))
    }

    if (open) {
        ConnectorPairingHost(viewModel = viewModel, onClose = { open = false })
    }
}

/**
 * The pairing dialog while it is open. Separate so the progress projection — and the one-second
 * ticker behind it — is subscribed to only while somebody is looking at it.
 *
 * Closing CANCELS the attempt in the ViewModel, which forgets that an attempt was made and not the
 * pairing itself, so re-opening later starts at the scanner instead of re-showing a stale outcome.
 */
@Composable
private fun ConnectorPairingHost(
    viewModel: ConnectorViewModel,
    onClose: () -> Unit,
) {
    val progress by viewModel.pairingProgress.collectAsStateWithLifecycle()
    ConnectorPairingDialog(
        progress = progress,
        onSubmit = viewModel::pair,
        onRetry = viewModel::cancelPairing,
        onDismiss = {
            onClose()
            viewModel.cancelPairing()
        },
    )
}

/** Which of the two system PROMPTS this build actually has, measured once per screen. */
private fun remedyCapabilities(context: Context): RemedyCapabilities =
    RemedyCapabilities(
        deviceAdminPrompt =
            context.canStartSettingsActivity(OemKeepAliveSettings.deviceAdminActivationIntent(context)),
        batteryExemptionPrompt =
            context.canStartSettingsActivity(OemKeepAliveSettings.batteryExemptionIntent(context)),
    )

/**
 * The extra sentence a row needs when its Fix lands the holder NEAR the control instead of on it.
 *
 * Silence is the common case, and it has to be: a checklist that explains how to do by hand what
 * the button just did teaches the holder to stop reading.
 */
private fun remedyNotes(capabilities: RemedyCapabilities): Map<RequiredPermission, Int> =
    buildMap {
        RequiredPermission.entries.forEach { permission ->
            val note = manualNote(RemedyRouter.destinationFor(permission.remedy, capabilities))
            if (note != null) put(permission, note)
        }
    }

/**
 * What a destination that cannot finish the grant has to admit.
 *
 * Exhaustive with no `else`, so a new destination is a compile error here rather than a silent
 * "no note" — the failure mode being a row that quietly sends the holder somewhere they cannot act.
 */
private fun manualNote(destination: RemedyDestination): Int? =
    when (destination) {
        RemedyDestination.DEVICE_ADMIN_LIST -> R.string.permission_audit_manual_device_admin

        RemedyDestination.BATTERY_OPTIMIZATION_LIST -> R.string.permission_audit_manual_battery

        RemedyDestination.APP_PERMISSIONS,
        RemedyDestination.ACCESSIBILITY_SETTINGS,
        RemedyDestination.DEVICE_ADMIN_PROMPT,
        RemedyDestination.NOTIFICATION_LISTENER_SETTINGS,
        RemedyDestination.BATTERY_EXEMPTION_PROMPT,
        -> null
    }

/**
 * Sends the holder to the one place this grant is actually made.
 *
 * A RUNTIME permission routes to the app's OWN permissions screen rather than to a system settings
 * page, because that screen can still invoke the runtime dialog — and because a holder who has
 * revoked one permission has usually revoked several, so showing the full list is more use than
 * deep-linking to one row.
 *
 * Everything else goes through [launcher] rather than `startActivity`, for two reasons that are
 * both about the screen coming BACK: the audit re-reads itself on the result, and a launcher's
 * target stays in this activity's task — which is the launch a vendor background-start guard does
 * not silently drop (see `startSettingsActivity`). A launch that cannot start falls back to this
 * app's own details page, which exists on every build.
 */
private fun openRemedy(
    permission: RequiredPermission,
    capabilities: RemedyCapabilities,
    openRuntimePermissions: () -> Unit,
    context: Context,
    launcher: ActivityResultLauncher<Intent>,
) {
    val intent =
        when (RemedyRouter.destinationFor(permission.remedy, capabilities)) {
            RemedyDestination.APP_PERMISSIONS -> {
                openRuntimePermissions()
                return
            }

            RemedyDestination.ACCESSIBILITY_SETTINGS -> {
                PermissionUtils.accessibilitySettingsIntent()
            }

            RemedyDestination.DEVICE_ADMIN_PROMPT -> {
                OemKeepAliveSettings.deviceAdminActivationIntent(context)
            }

            RemedyDestination.DEVICE_ADMIN_LIST -> {
                OemKeepAliveSettings.deviceAdminListIntent()
            }

            RemedyDestination.NOTIFICATION_LISTENER_SETTINGS -> {
                PermissionUtils.notificationListenerSettingsIntent()
            }

            RemedyDestination.BATTERY_EXEMPTION_PROMPT -> {
                OemKeepAliveSettings.batteryExemptionIntent(context)
            }

            RemedyDestination.BATTERY_OPTIMIZATION_LIST -> {
                OemKeepAliveSettings.batteryOptimizationListIntent()
            }
        }
    if (launcher.startSettingsActivity(intent)) return
    OemKeepAliveSettings.openAppDetails(context)
}
