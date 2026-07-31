package com.baluhost.android.presentation.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.domain.usecase.power.GetAlwaysAwakeUseCase
import com.baluhost.android.domain.usecase.power.SetAlwaysAwakeUseCase
import com.baluhost.android.util.Clock
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class AlwaysAwakeUiState(
    val isLoading: Boolean = true,
    val enabled: Boolean = false,
    /** Null while the override is permanent — or while it is off entirely. */
    val until: Instant? = null,
    val isSaving: Boolean = false,
    val loadError: String? = null
)

@HiltViewModel
class AlwaysAwakeViewModel @Inject constructor(
    private val getAlwaysAwakeUseCase: GetAlwaysAwakeUseCase,
    private val setAlwaysAwakeUseCase: SetAlwaysAwakeUseCase,
    private val clock: Clock
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlwaysAwakeUiState())
    val uiState: StateFlow<AlwaysAwakeUiState> = _uiState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)
            when (val result = getAlwaysAwakeUseCase()) {
                is Result.Success -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    enabled = result.data.enabled,
                    until = result.data.until,
                    loadError = null
                )
                is Result.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadError = result.exception.message ?: "Laden fehlgeschlagen"
                )
                else -> {}
            }
        }
    }

    /** @param hours null selects the permanent override. */
    fun setPreset(hours: Long?) {
        val until = hours?.let { clock.now().plus(Duration.ofHours(it)) }
        save(enabled = true, until = until)
    }

    fun setCustom(until: Instant) {
        val now = clock.now()
        val message = when {
            !until.isAfter(now) -> "Der Zeitpunkt muss in der Zukunft liegen"
            until.isBefore(now.plus(MIN_HORIZON)) -> "Mindestens 5 Minuten in der Zukunft"
            until.isAfter(now.plus(MAX_HORIZON)) -> "Höchstens 7 Tage im Voraus"
            else -> null
        }
        if (message != null) {
            // Caught here so the user gets a sentence instead of the server's 422.
            // The server enforces the future and the seven days itself; the five
            // minutes are ours, so an expiry is not already lapsing as it is set.
            viewModelScope.launch { _snackbarEvent.emit(message) }
            return
        }
        save(enabled = true, until = until)
    }

    fun disable() = save(enabled = false, until = null)

    private fun save(enabled: Boolean, until: Instant?) {
        val previous = _uiState.value
        viewModelScope.launch {
            _uiState.value = previous.copy(isSaving = true)
            when (val result = setAlwaysAwakeUseCase(enabled, until)) {
                is Result.Success -> _uiState.value = previous.copy(
                    isSaving = false,
                    enabled = result.data.enabled,
                    until = result.data.until
                )
                is Result.Error -> {
                    // Put the old state back: the screen must not claim a change
                    // the server never accepted.
                    _uiState.value = previous.copy(isSaving = false)
                    _snackbarEvent.emit(result.exception.message ?: "Speichern fehlgeschlagen")
                }
                else -> {}
            }
        }
    }

    private companion object {
        val MIN_HORIZON: Duration = Duration.ofMinutes(5)
        val MAX_HORIZON: Duration = Duration.ofDays(7)
    }
}
