@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.PermissionRequesters
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TopLevelRoute
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

@Composable
fun MainScreen(
    permissionRequesters: PermissionRequesters,
    openPermissionsOnLaunch: Boolean = false,
    viewModel: MainViewModel = hiltViewModel(),
) {
    // Seeded rather than applied in a LaunchedEffect: the deep link decides where the FIRST frame
    // lands, so routing it as an effect would flash the Connector tab before jumping away.
    // rememberSaveable then makes a rotation keep wherever the holder navigated to since.
    var selectedTabRoute by rememberSaveable {
        mutableStateOf(if (openPermissionsOnLaunch) TopLevelRoute.Settings.route else TopLevelRoute.Server.route)
    }
    var pendingSettingsRoute by rememberSaveable {
        mutableStateOf<String?>(if (openPermissionsOnLaunch) SettingsRoute.Permissions.route else null)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(TopLevelRoute.Server, Icons.Default.Dns, stringResource(R.string.tab_server)),
                    Triple(TopLevelRoute.Settings, Icons.Default.Settings, stringResource(R.string.tab_settings)),
                    Triple(TopLevelRoute.About, Icons.Default.Info, stringResource(R.string.tab_about)),
                ).forEach { (route, icon, label) ->
                    NavigationBarItem(
                        selected = selectedTabRoute == route.route,
                        onClick = { selectedTabRoute = route.route },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { paddingValues ->
        when (selectedTabRoute) {
            TopLevelRoute.Server.route -> {
                ServerScreen(
                    onNavigateToPermissions = {
                        pendingSettingsRoute = SettingsRoute.Permissions.route
                        selectedTabRoute = TopLevelRoute.Settings.route
                    },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            TopLevelRoute.Settings.route -> {
                SettingsScreen(
                    onRequestNotificationPermission = permissionRequesters.onRequestNotification,
                    onRequestCameraPermission = permissionRequesters.onRequestCamera,
                    onRequestMicrophonePermission = permissionRequesters.onRequestMicrophone,
                    onRequestLocationPermission = permissionRequesters.onRequestLocation,
                    pendingRoute = pendingSettingsRoute,
                    onPendingRouteConsumed = { pendingSettingsRoute = null },
                    modifier = Modifier.padding(paddingValues),
                    viewModel = viewModel,
                )
            }

            TopLevelRoute.About.route -> {
                AboutScreen(modifier = Modifier.padding(paddingValues))
            }

            else -> {
                ServerScreen(
                    onNavigateToPermissions = {
                        pendingSettingsRoute = SettingsRoute.Permissions.route
                        selectedTabRoute = TopLevelRoute.Settings.route
                    },
                    modifier = Modifier.padding(paddingValues),
                )
            }
        }
    }
}
