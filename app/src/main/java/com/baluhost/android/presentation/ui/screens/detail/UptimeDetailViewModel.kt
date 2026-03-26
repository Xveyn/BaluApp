package com.baluhost.android.presentation.ui.screens.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.domain.model.CurrentUptime
import com.baluhost.android.domain.model.UptimeHistory
import com.baluhost.android.domain.usecase.system.GetCurrentUptimeUseCase
import com.baluhost.android.domain.usecase.system.GetUptimeHistoryUseCase
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
class UptimeDetailViewModel @Inject constructor(
    private val getCurrentUptimeUseCase: GetCurrentUptimeUseCase,
    private val getUptimeHistoryUseCase: GetUptimeHistoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(UptimeDetailUiState())
    val uiState: StateFlow<UptimeDetailUiState> = _uiState.asStateFlow()

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

    fun selectTimeRange(timeRange: String) {
        _uiState.value = _uiState.value.copy(selectedTimeRange = timeRange)
        loadData()
        startPolling()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.currentUptime == null)

            try {
                val currentResult = getCurrentUptimeUseCase()
                val historyResult = getUptimeHistoryUseCase(_uiState.value.selectedTimeRange)

                val current = when (currentResult) {
                    is Result.Success -> currentResult.data
                    is Result.Error -> {
                        Log.e("UptimeDetailVM", "Failed to load current uptime", currentResult.exception)
                        null
                    }
                    else -> null
                }

                val history = when (historyResult) {
                    is Result.Success -> historyResult.data
                    is Result.Error -> {
                        Log.e("UptimeDetailVM", "Failed to load uptime history", historyResult.exception)
                        null
                    }
                    else -> null
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentUptime = current ?: _uiState.value.currentUptime,
                    uptimeHistory = history ?: _uiState.value.uptimeHistory,
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

data class UptimeDetailUiState(
    val isLoading: Boolean = false,
    val currentUptime: CurrentUptime? = null,
    val uptimeHistory: UptimeHistory? = null,
    val selectedTimeRange: String = "1h",
    val error: String? = null
)
