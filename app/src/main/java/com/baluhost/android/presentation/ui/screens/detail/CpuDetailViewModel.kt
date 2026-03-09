package com.baluhost.android.presentation.ui.screens.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.domain.model.CpuHistory
import com.baluhost.android.domain.model.SystemInfo
import com.baluhost.android.domain.usecase.system.GetCpuHistoryUseCase
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
class CpuDetailViewModel @Inject constructor(
    private val getCpuHistoryUseCase: GetCpuHistoryUseCase,
    private val getSystemTelemetryUseCase: GetSystemTelemetryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(CpuDetailUiState())
    val uiState: StateFlow<CpuDetailUiState> = _uiState.asStateFlow()

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
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.cpuHistory == null)

            try {
                val historyResult = getCpuHistoryUseCase()
                val telemetryResult = getSystemTelemetryUseCase()

                val history = when (historyResult) {
                    is Result.Success -> historyResult.data
                    is Result.Error -> {
                        Log.e("CpuDetailVM", "Failed to load CPU history", historyResult.exception)
                        null
                    }
                    else -> null
                }

                val telemetry = when (telemetryResult) {
                    is Result.Success -> telemetryResult.data
                    is Result.Error -> null
                    else -> null
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    cpuHistory = history ?: _uiState.value.cpuHistory,
                    currentUsage = telemetry?.cpu?.usagePercent,
                    currentTemp = telemetry?.cpu?.temperatureCelsius,
                    currentFreq = telemetry?.cpu?.frequencyMhz,
                    cpuModel = telemetry?.cpu?.model,
                    cores = telemetry?.cpu?.cores,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun refresh() {
        loadData()
    }
}

data class CpuDetailUiState(
    val isLoading: Boolean = false,
    val cpuHistory: CpuHistory? = null,
    val currentUsage: Double? = null,
    val currentTemp: Double? = null,
    val currentFreq: Double? = null,
    val cpuModel: String? = null,
    val cores: Int? = null,
    val error: String? = null
)
