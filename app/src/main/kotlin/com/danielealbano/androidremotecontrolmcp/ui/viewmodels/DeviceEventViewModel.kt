package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.DeviceEventConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.DeviceEventCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the Device reporting screen renders: the holder's five per-category switches.
 *
 * It holds no state of its own — the repository is the authority and this is a projection, the same
 * shape as [ChannelViewModel] next door. The ONE method takes the category as a typed value rather
 * than exposing five near-identical setters, so a sixth category becomes a row in the screen and
 * nothing here.
 */
@HiltViewModel
class DeviceEventViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val config: StateFlow<DeviceEventConfig> =
            settingsRepository.deviceEventConfig
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DeviceEventConfig())

        /** Switches one category on or off. Takes effect on a live socket without a reconnect. */
        fun setCategoryEnabled(
            category: DeviceEventCategory,
            enabled: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateDeviceEventCategoryEnabled(category, enabled)
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5000L
        }
    }
