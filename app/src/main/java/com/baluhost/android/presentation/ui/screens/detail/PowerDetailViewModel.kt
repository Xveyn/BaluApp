package com.baluhost.android.presentation.ui.screens.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.domain.model.EnergyDashboardFull
import com.baluhost.android.domain.usecase.system.GetEnergyDashboardFullUseCase
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
class PowerDetailViewModel @Inject constructor(
    private val getEnergyDashboardFullUseCase: GetEnergyDashboardFullUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PowerDetailUiState())
    val uiState: StateFlow<PowerDetailUiState> = _uiState.asStateFlow()

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
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.energyDashboard == null)

            try {
                val result = getEnergyDashboardFullUseCase()
                when (result) {
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

    fun refresh() {
        loadData()
    }
}

data class PowerDetailUiState(
    val isLoading: Boolean = false,
    val energyDashboard: EnergyDashboardFull? = null,
    val error: String? = null
)
