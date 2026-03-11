package com.baluhost.android.presentation.ui.screens.sync

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.worker.SyncScheduleManager
import com.baluhost.android.data.worker.SyncScheduleWorkScheduler
import com.baluhost.android.domain.model.sync.ScheduleType
import com.baluhost.android.domain.model.sync.SyncSchedule
import com.baluhost.android.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncScheduleViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val preferencesManager: PreferencesManager,
    private val syncScheduleManager: SyncScheduleManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "SyncScheduleVM"
    }

    private val _schedules = MutableStateFlow<List<SyncSchedule>>(emptyList())
    val schedules: StateFlow<List<SyncSchedule>> = _schedules.asStateFlow()

    private val _autoVpnEnabled = MutableStateFlow(false)
    val autoVpnEnabled: StateFlow<Boolean> = _autoVpnEnabled.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadSchedules()
        viewModelScope.launch {
            preferencesManager.isAutoVpnForSync().collect { _autoVpnEnabled.value = it }
        }
    }

    fun loadSchedules() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val result = syncRepository.getSyncSchedules()
                result.onSuccess { schedules ->
                    _schedules.value = schedules
                    preferencesManager.saveSyncSchedules(schedules)
                }.onFailure { e ->
                    Log.e(TAG, "Failed to load schedules", e)
                    // Fallback to cache
                    _schedules.value = preferencesManager.getCachedSyncSchedules()
                    _errorMessage.value = "Zeitpläne konnten nicht geladen werden"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading schedules", e)
                _schedules.value = preferencesManager.getCachedSyncSchedules()
                _errorMessage.value = "Verbindungsfehler"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createSchedule(
        scheduleType: ScheduleType,
        timeOfDay: String,
        dayOfWeek: Int? = null,
        dayOfMonth: Int? = null,
        autoVpn: Boolean = false
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val deviceId = preferencesManager.getDeviceId().first() ?: return@launch
                val result = syncRepository.createSyncSchedule(
                    deviceId = deviceId,
                    scheduleType = scheduleType,
                    timeOfDay = timeOfDay,
                    dayOfWeek = dayOfWeek,
                    dayOfMonth = dayOfMonth,
                    autoVpn = autoVpn
                )
                result.onSuccess {
                    loadSchedules()
                    // Trigger immediate reconciliation with WorkManager
                    SyncScheduleWorkScheduler.triggerImmediateRefresh(context)
                }.onFailure { e ->
                    Log.e(TAG, "Failed to create schedule", e)
                    _errorMessage.value = "Zeitplan konnte nicht erstellt werden"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleSchedule(scheduleId: Int, enabled: Boolean) {
        viewModelScope.launch {
            try {
                val result = if (enabled) {
                    syncRepository.enableSyncSchedule(scheduleId)
                } else {
                    syncRepository.disableSyncSchedule(scheduleId)
                }
                result.onSuccess {
                    loadSchedules()
                    SyncScheduleWorkScheduler.triggerImmediateRefresh(context)
                }.onFailure { e ->
                    Log.e(TAG, "Failed to toggle schedule $scheduleId", e)
                    _errorMessage.value = "Zeitplan konnte nicht geändert werden"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling schedule", e)
            }
        }
    }

    fun deleteSchedule(scheduleId: Int) {
        viewModelScope.launch {
            try {
                syncRepository.disableSyncSchedule(scheduleId).onSuccess {
                    loadSchedules()
                    SyncScheduleWorkScheduler.triggerImmediateRefresh(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting schedule", e)
            }
        }
    }

    fun setAutoVpn(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveAutoVpnForSync(enabled)
            _autoVpnEnabled.value = enabled
        }
    }

    fun refreshFromServer() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                syncScheduleManager.refreshAndReconcile()
                loadSchedules()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
