@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.DeviceEventConfig
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.DeviceEventViewModel

/**
 * The holder's half of the device-event plane: one switch per category, all on by default.
 *
 * Every row says what it reports in plain words, because this is the surface on which somebody
 * decides how much of their handset's life their organisation sees. The intro says the other half
 * out loud as well — the org can narrow this further and can never widen it — since a switch that
 * is ON while the org has the category disabled reports nothing, and a holder who did not know that
 * would read the silence as a broken phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceEventsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeviceEventViewModel = hiltViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_device_events_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.device_events_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            categoryRows.forEach { row ->
                DeviceEventRow(
                    title = stringResource(row.titleRes),
                    detail = stringResource(row.detailRes),
                    checked = config.permits(row.category),
                    onCheckedChange = { viewModel.setCategoryEnabled(row.category, it) },
                )
            }
        }
    }
}

@Composable
private fun DeviceEventRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

/**
 * One row per category, in the vocabulary's own order.
 *
 * A table rather than five hand-written blocks so the screen cannot drift from
 * [DeviceEventCategory]: adding a category is one entry here, and [DeviceEventConfig.permits]
 * already makes the read exhaustive.
 */
private data class DeviceEventRowSpec(
    val category: DeviceEventCategory,
    @param:StringRes val titleRes: Int,
    @param:StringRes val detailRes: Int,
)

private val categoryRows =
    listOf(
        DeviceEventRowSpec(
            DeviceEventCategory.NOTIFICATION,
            R.string.device_events_notification,
            R.string.device_events_notification_detail,
        ),
        DeviceEventRowSpec(
            DeviceEventCategory.CALL,
            R.string.device_events_call,
            R.string.device_events_call_detail,
        ),
        DeviceEventRowSpec(
            DeviceEventCategory.SMS,
            R.string.device_events_sms,
            R.string.device_events_sms_detail,
        ),
        DeviceEventRowSpec(
            DeviceEventCategory.CONNECTIVITY,
            R.string.device_events_connectivity,
            R.string.device_events_connectivity_detail,
        ),
        DeviceEventRowSpec(
            DeviceEventCategory.BATTERY,
            R.string.device_events_battery,
            R.string.device_events_battery_detail,
        ),
    )
