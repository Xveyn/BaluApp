package com.baluhost.android.presentation.ui.screens.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.domain.model.MemoryHistory
import com.baluhost.android.domain.usecase.system.GetMemoryHistoryUseCase
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
class MemoryDetailViewModel @Inject constructor(
    private val getMemoryHistoryUseCase: GetMemoryHistoryUseCase,
    private val getSystemTelemetryUseCase: GetSystemTelemetryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryDetailUiState())
    val uiState: StateFlow<MemoryDetailUiState> = _uiState.asStateFlow()

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
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.memoryHistory == null)

            try {
                val historyResult = getMemoryHistoryUseCase()
                val telemetryResult = getSystemTelemetryUseCase()

                val history = when (historyResult) {
                    is Result.Success -> historyResult.data
                    is Result.Error -> {
                        Log.e("MemoryDetailVM", "Failed to load memory history", historyResult.exception)
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
                    memoryHistory = history ?: _uiState.value.memoryHistory,
                    currentUsed = telemetry?.memory?.usedBytes,
                    currentTotal = telemetry?.memory?.totalBytes,
                    currentPercent = telemetry?.memory?.usagePercent,
                    memoryType = telemetry?.memory?.type,
                    memorySpeed = telemetry?.memory?.speedMts,
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

data class MemoryDetailUiState(
    val isLoading: Boolean = false,
    val memoryHistory: MemoryHistory? = null,
    val currentUsed: Long? = null,
    val currentTotal: Long? = null,
    val currentPercent: Double? = null,
    val memoryType: String? = null,
    val memorySpeed: Int? = null,
    val error: String? = null
)
