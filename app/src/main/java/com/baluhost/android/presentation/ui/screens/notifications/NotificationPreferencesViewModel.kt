package com.baluhost.android.presentation.ui.screens.notifications

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.remote.dto.CategoryPreference
import com.baluhost.android.data.remote.dto.NotificationPreferencesUpdate
import com.baluhost.android.domain.usecase.notification.GetNotificationPreferencesUseCase
import com.baluhost.android.domain.usecase.notification.UpdateNotificationPreferencesUseCase
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationPreferencesViewModel @Inject constructor(
    private val getPreferencesUseCase: GetNotificationPreferencesUseCase,
    private val updatePreferencesUseCase: UpdateNotificationPreferencesUseCase
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val isSaving: Boolean = false,
        val error: String? = null,
        val pushEnabled: Boolean = true,
        val inAppEnabled: Boolean = true,
        val quietHoursEnabled: Boolean = false,
        val quietHoursStart: String = "22:00",
        val quietHoursEnd: String = "07:00",
        val minPriority: Int = 0,
        val categoryPreferences: Map<String, CategoryPreference> = emptyMap()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = getPreferencesUseCase()) {
                is Result.Success -> {
                    val prefs = result.data
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        pushEnabled = prefs.pushEnabled,
                        inAppEnabled = prefs.inAppEnabled,
                        quietHoursEnabled = prefs.quietHoursEnabled,
                        quietHoursStart = prefs.quietHoursStart?.take(5) ?: "22:00",
                        quietHoursEnd = prefs.quietHoursEnd?.take(5) ?: "07:00",
                        minPriority = prefs.minPriority,
                        categoryPreferences = prefs.categoryPreferences ?: emptyMap(),
                        error = null
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Einstellungen konnten nicht geladen werden"
                    )
                    Log.e("NotifPrefsVM", "Load failed", result.exception)
                }
                is Result.Loading -> {}
            }
        }
    }

    fun updatePushEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(pushEnabled = enabled)
        saveUpdate(NotificationPreferencesUpdate(pushEnabled = enabled))
    }

    fun updateInAppEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(inAppEnabled = enabled)
        saveUpdate(NotificationPreferencesUpdate(inAppEnabled = enabled))
    }

    fun updateQuietHoursEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(quietHoursEnabled = enabled)
        saveUpdate(NotificationPreferencesUpdate(
            quietHoursEnabled = enabled,
            quietHoursStart = if (enabled) _uiState.value.quietHoursStart else null,
            quietHoursEnd = if (enabled) _uiState.value.quietHoursEnd else null
        ))
    }

    fun updateQuietHoursStart(time: String) {
        _uiState.value = _uiState.value.copy(quietHoursStart = time)
        saveUpdate(NotificationPreferencesUpdate(quietHoursStart = time))
    }

    fun updateQuietHoursEnd(time: String) {
        _uiState.value = _uiState.value.copy(quietHoursEnd = time)
        saveUpdate(NotificationPreferencesUpdate(quietHoursEnd = time))
    }

    fun updateMinPriority(priority: Int) {
        _uiState.value = _uiState.value.copy(minPriority = priority)
        saveUpdate(NotificationPreferencesUpdate(minPriority = priority))
    }

    fun updateCategoryPreference(category: String, push: Boolean, inApp: Boolean) {
        val updated = _uiState.value.categoryPreferences.toMutableMap()
        updated[category] = CategoryPreference(email = false, push = push, inApp = inApp)
        _uiState.value = _uiState.value.copy(categoryPreferences = updated)
        saveUpdate(NotificationPreferencesUpdate(categoryPreferences = updated))
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun saveUpdate(update: NotificationPreferencesUpdate) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            when (val result = updatePreferencesUseCase(update)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = "Einstellungen konnten nicht gespeichert werden"
                    )
                    Log.e("NotifPrefsVM", "Save failed", result.exception)
                }
                is Result.Loading -> {}
            }
        }
    }
}
