package com.danielealbano.androidremotecontrolmcp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.danielealbano.androidremotecontrolmcp.ui.screens.MainScreen
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var microphonePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        notificationPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        cameraPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        microphonePermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        locationPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        val openPermissions = intent?.getBooleanExtra(EXTRA_OPEN_PERMISSIONS, false) == true

        setContent {
            AndroidRemoteControlMcpTheme {
                MainScreen(
                    permissionRequesters =
                        PermissionRequesters(
                            onRequestNotification = ::requestNotificationPermission,
                            onRequestCamera = ::requestCameraPermission,
                            onRequestMicrophone = ::requestMicrophonePermission,
                            onRequestLocation = ::requestLocationPermission,
                        ),
                    openPermissionsOnLaunch = openPermissions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionStatus(this)
    }
    // NOTE: No onPause() needed for broadcast receiver since we use StateFlow
    // for server status, which is collected in MainViewModel via viewModelScope.

    /**
     * Requests the POST_NOTIFICATIONS runtime permission.
     */
    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Requests the CAMERA runtime permission.
     */
    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    /**
     * Requests the RECORD_AUDIO runtime permission.
     */
    private fun requestMicrophonePermission() {
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    companion object {
        /** Set by [permissionsIntent] to land the holder on the permissions screen. */
        private const val EXTRA_OPEN_PERMISSIONS = "open_permissions"

        /**
         * The permissions audit notification's tap target: this activity, routed straight to the
         * permissions screen.
         *
         * `CLEAR_TOP` on a `standard`-launchMode activity restarts it with THIS intent, so the
         * extra is read in `onCreate` and there is no `onNewIntent` path to keep in sync. That is
         * why the flag is here rather than `SINGLE_TOP`: a Compose tree that has to be re-routed
         * from a second entry point is a second source of truth for which tab is showing.
         */
        fun permissionsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_PERMISSIONS, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
