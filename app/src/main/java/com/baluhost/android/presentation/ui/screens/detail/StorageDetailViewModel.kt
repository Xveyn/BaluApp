package com.baluhost.android.presentation.ui.screens.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.domain.model.RaidArray
import com.baluhost.android.domain.model.SmartDeviceInfo
import com.baluhost.android.domain.usecase.system.GetRaidStatusUseCase
import com.baluhost.android.domain.usecase.system.GetSmartStatusUseCase
import com.baluhost.android.domain.usecase.system.GetSystemTelemetryUseCase
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StorageDetailViewModel @Inject constructor(
    private val getSmartStatusUseCase: GetSmartStatusUseCase,
    private val getRaidStatusUseCase: GetRaidStatusUseCase,
    private val getSystemTelemetryUseCase: GetSystemTelemetryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(StorageDetailUiState())
    val uiState: StateFlow<StorageDetailUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        loadData()
        startPolling()
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                loadData()
            }
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = _uiState.value.smartDevices.isEmpty() && _uiState.value.raidArrays.isEmpty()
            )

            var smartDevices: List<SmartDeviceInfo>? = null
            var raidArrays: List<RaidArray>? = null
            var totalUsed: Long? = null
            var totalCapacity: Long? = null
            var usagePercent: Double? = null
            val errors = mutableListOf<String>()

            try {
                when (val result = getSmartStatusUseCase()) {
                    is Result.Success -> {
                        smartDevices = result.data.devices
                    }
                    is Result.Error -> {
                        Log.e("StorageDetailVM", "Failed to load SMART status", result.exception)
                        errors.add("SMART data unavailable")
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e("StorageDetailVM", "SMART status exception", e)
                errors.add("SMART data unavailable")
            }

            try {
                when (val result = getRaidStatusUseCase()) {
                    is Result.Success -> {
                        raidArrays = result.data
                    }
                    is Result.Error -> {
                        Log.e("StorageDetailVM", "Failed to load RAID status", result.exception)
                        errors.add("RAID data unavailable")
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e("StorageDetailVM", "RAID status exception", e)
                errors.add("RAID data unavailable")
            }

            try {
                when (val result = getSystemTelemetryUseCase()) {
                    is Result.Success -> {
                        totalUsed = result.data.disk.usedBytes
                        totalCapacity = result.data.disk.totalBytes
                        usagePercent = result.data.disk.usagePercent
                    }
                    is Result.Error -> {
                        Log.e("StorageDetailVM", "Failed to load telemetry", result.exception)
                        errors.add("Storage totals unavailable")
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e("StorageDetailVM", "Telemetry exception", e)
                errors.add("Storage totals unavailable")
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                smartDevices = smartDevices ?: _uiState.value.smartDevices,
                raidArrays = raidArrays ?: _uiState.value.raidArrays,
                totalUsed = totalUsed ?: _uiState.value.totalUsed,
                totalCapacity = totalCapacity ?: _uiState.value.totalCapacity,
                usagePercent = usagePercent ?: _uiState.value.usagePercent,
                error = errors.joinToString("; ").ifEmpty { null }
            )
        }
    }

    fun refresh() {
        loadData()
    }
}

data class StorageDetailUiState(
    val isLoading: Boolean = false,
    val smartDevices: List<SmartDeviceInfo> = emptyList(),
    val raidArrays: List<RaidArray> = emptyList(),
    val totalUsed: Long = 0L,
    val totalCapacity: Long = 0L,
    val usagePercent: Double = 0.0,
    val error: String? = null
)
