package com.baluhost.android.presentation.ui.screens.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.remote.api.EnergyApi
import com.baluhost.android.data.remote.api.PluginApi
import com.baluhost.android.data.remote.dto.PluginConfigUpdateRequestDto
import com.baluhost.android.data.remote.dto.TapoPluginConfigDto
import com.baluhost.android.domain.model.EnergyDashboardFull
import com.baluhost.android.domain.usecase.system.GetEnergyDashboardFullUseCase
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PowerDetailViewModel @Inject constructor(
    private val getEnergyDashboardFullUseCase: GetEnergyDashboardFullUseCase,
    private val energyApi: EnergyApi,
    private val pluginApi: PluginApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(PowerDetailUiState())
    val uiState: StateFlow<PowerDetailUiState> = _uiState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private var pollingJob: Job? = null

    init {
        loadDevicesAndConfig()
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }

    private fun loadDevicesAndConfig() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // Load device list
                val response = energyApi.getSmartDevices()
                val powerDevices = response.devices
                    .filter { it.isActive && "power_monitor" in it.capabilities }
                    .map { PowerDevice(id = it.id, name = it.name, isOnline = it.isOnline) }

                // Load panel config
                val panelDeviceIds = try {
                    pluginApi.getTapoPluginConfig().config.panelDevices.toSet()
                } catch (_: Exception) {
                    emptySet()
                }

                // Default selected device: first device
                val selectedId = powerDevices.firstOrNull()?.id

                _uiState.value = _uiState.value.copy(
                    devices = powerDevices,
                    selectedDeviceId = selectedId,
                    panelDeviceIds = panelDeviceIds
                )

                // Load energy data for selected device
                if (selectedId != null) {
                    loadEnergyData(selectedId)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }

                startPolling()
            } catch (e: Exception) {
                Log.e("PowerDetailVM", "Failed to load devices", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    private fun loadEnergyData(deviceId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = _uiState.value.energyDashboard == null
            )

            try {
                when (val result = getEnergyDashboardFullUseCase(deviceId)) {
                    is Result.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            energyDashboard = result.data,
                            error = null
                        )
                    }
                    is Result.Error -> {
                        Log.e("PowerDetailVM", "Failed to load energy dashboard", result.exception)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = result.exception.message
                        )
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                val deviceId = _uiState.value.selectedDeviceId ?: continue
                loadEnergyData(deviceId)
            }
        }
    }

    fun selectDevice(deviceId: Int) {
        if (deviceId == _uiState.value.selectedDeviceId) return
        _uiState.value = _uiState.value.copy(
            selectedDeviceId = deviceId,
            energyDashboard = null
        )
        loadEnergyData(deviceId)
    }

    fun togglePanelDevice(deviceId: Int) {
        val current = _uiState.value.panelDeviceIds
        val updated = if (deviceId in current) current - deviceId else current + deviceId
        _uiState.value = _uiState.value.copy(
            panelDeviceIds = updated,
            isSavingPanel = true
        )

        viewModelScope.launch {
            try {
                pluginApi.updateTapoPluginConfig(
                    PluginConfigUpdateRequestDto(
                        config = TapoPluginConfigDto(panelDevices = updated.toList())
                    )
                )
                _snackbarEvent.emit("Dashboard aktualisiert")
            } catch (e: Exception) {
                Log.e("PowerDetailVM", "Failed to save panel config", e)
                // Revert on failure
                _uiState.value = _uiState.value.copy(panelDeviceIds = current)
                _snackbarEvent.emit("Speichern fehlgeschlagen")
            } finally {
                _uiState.value = _uiState.value.copy(isSavingPanel = false)
            }
        }
    }

    fun refresh() {
        val deviceId = _uiState.value.selectedDeviceId
        if (deviceId != null) {
            loadEnergyData(deviceId)
        } else {
            loadDevicesAndConfig()
        }
    }
}

data class PowerDevice(
    val id: Int,
    val name: String,
    val isOnline: Boolean
)

data class PowerDetailUiState(
    val isLoading: Boolean = false,
    val energyDashboard: EnergyDashboardFull? = null,
    val error: String? = null,
    val devices: List<PowerDevice> = emptyList(),
    val selectedDeviceId: Int? = null,
    val panelDeviceIds: Set<Int> = emptySet(),
    val isSavingPanel: Boolean = false
)
