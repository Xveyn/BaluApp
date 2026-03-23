package com.baluhost.android.presentation.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.notification.NotificationWebSocketManager
import com.baluhost.android.domain.model.EnergyDashboard
import com.baluhost.android.domain.model.FileItem
import com.baluhost.android.domain.model.OperationStatus
import com.baluhost.android.domain.model.RaidArray
import com.baluhost.android.domain.model.RecentFile
import com.baluhost.android.domain.model.ShareStatistics
import com.baluhost.android.domain.model.SmartDeviceInfo
import com.baluhost.android.domain.model.SystemInfo
import com.baluhost.android.domain.model.sync.SyncStatus
import com.baluhost.android.domain.repository.OfflineQueueRepository
import com.baluhost.android.domain.repository.SyncRepository
import com.baluhost.android.domain.usecase.activity.GetRecentFilesUseCase
import com.baluhost.android.domain.usecase.cache.GetCacheStatsUseCase
import com.baluhost.android.domain.usecase.files.GetFilesUseCase
import com.baluhost.android.domain.usecase.system.GetEnergyDashboardUseCase
import com.baluhost.android.domain.usecase.system.GetRaidStatusUseCase
import com.baluhost.android.domain.usecase.shares.GetShareStatisticsUseCase
import com.baluhost.android.domain.usecase.system.GetSmartStatusUseCase
import com.baluhost.android.domain.usecase.system.GetSystemTelemetryUseCase
import com.baluhost.android.domain.usecase.power.SendWolUseCase
import com.baluhost.android.domain.usecase.power.SendSoftSleepUseCase
import com.baluhost.android.domain.usecase.power.SendSuspendUseCase
import com.baluhost.android.util.NetworkStateManager
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Dashboard screen.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getFilesUseCase: GetFilesUseCase,
    private val getRecentFilesUseCase: GetRecentFilesUseCase,
    private val getCacheStatsUseCase: GetCacheStatsUseCase,
    private val getSystemTelemetryUseCase: GetSystemTelemetryUseCase,
    private val getEnergyDashboardUseCase: GetEnergyDashboardUseCase,
    private val getRaidStatusUseCase: GetRaidStatusUseCase,
    private val getSmartStatusUseCase: GetSmartStatusUseCase,
    private val getShareStatisticsUseCase: GetShareStatisticsUseCase,
    private val preferencesManager: PreferencesManager,
    private val networkStateManager: NetworkStateManager,
    private val offlineQueueRepository: OfflineQueueRepository,
    private val syncRepository: SyncRepository,
    private val notificationWebSocketManager: NotificationWebSocketManager,
    private val sendWolUseCase: SendWolUseCase,
    private val sendSoftSleepUseCase: SendSoftSleepUseCase,
    private val sendSuspendUseCase: SendSuspendUseCase
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    val unreadNotificationCount: StateFlow<Int> = notificationWebSocketManager.unreadCount
    
    // VPN-related state flows
    private val _isInHomeNetwork = MutableStateFlow<Boolean?>(null)
    val isInHomeNetwork: StateFlow<Boolean?> = _isInHomeNetwork.asStateFlow()
    
    private val _hasVpnConfig = MutableStateFlow(false)
    val hasVpnConfig: StateFlow<Boolean> = _hasVpnConfig.asStateFlow()
    
    private val _vpnBannerDismissed = MutableStateFlow(false)
    val vpnBannerDismissed: StateFlow<Boolean> = _vpnBannerDismissed.asStateFlow()
    
    private val _isVpnActive = MutableStateFlow(false)
    val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _isFritzBoxConfigured = MutableStateFlow(false)
    val isFritzBoxConfigured: StateFlow<Boolean> = _isFritzBoxConfigured.asStateFlow()

    private val _powerActionInProgress = MutableStateFlow(false)
    val powerActionInProgress: StateFlow<Boolean> = _powerActionInProgress.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private var pollingJob: kotlinx.coroutines.Job? = null
    
    init {
        loadDashboardData()
        startPolling()
        observeHomeNetworkState()
        checkVpnConfig()
        loadUserRole()
        observePendingOperations()
    }
    
    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
    
    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000) // 30 seconds
                loadTelemetryData()
            }
        }
    }
    
    private fun loadDashboardData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                // Load username
                val username = preferencesManager.getUsername().first() ?: "User"
                
                // Load system telemetry
                val telemetryResult = getSystemTelemetryUseCase()
                val telemetry = when (telemetryResult) {
                    is Result.Success -> {
                        val d = telemetryResult.data
                        Log.d("DashboardViewModel", "Telemetry loaded: CPU=${d.cpu.usagePercent}%, Memory=${d.memory.usagePercent}%, Disk=${d.disk.usagePercent}%")
                        Log.d("DashboardViewModel", "Hardware: cpuModel=${d.cpu.model}, cpuFreq=${d.cpu.frequencyMhz}, cpuTemp=${d.cpu.temperatureCelsius}, ramType=${d.memory.type}, ramSpeed=${d.memory.speedMts}")
                        d
                    }
                    is Result.Error -> {
                        Log.e("DashboardViewModel", "Failed to load telemetry", telemetryResult.exception)
                        null
                    }
                    else -> null
                }
                
                // Load RAID status
                val raidResult = getRaidStatusUseCase()
                val raidArrays = when (raidResult) {
                    is Result.Success -> {
                        Log.d("DashboardViewModel", "RAID arrays loaded: ${raidResult.data.size} arrays")
                        raidResult.data
                    }
                    is Result.Error -> {
                        Log.e("DashboardViewModel", "Failed to load RAID status", raidResult.exception)
                        emptyList()
                    }
                    else -> emptyList()
                }
                
                // Load SMART status
                val smartResult = getSmartStatusUseCase()
                val smartDevices = when (smartResult) {
                    is Result.Success -> {
                        Log.d("DashboardViewModel", "SMART devices loaded: ${smartResult.data.devices.size} devices")
                        smartResult.data.devices
                    }
                    is Result.Error -> {
                        Log.e("DashboardViewModel", "Failed to load SMART status", smartResult.exception)
                        emptyList()
                    }
                    else -> emptyList()
                }

                // Load energy dashboard
                val energyResult = getEnergyDashboardUseCase()
                val energy = when (energyResult) {
                    is Result.Success -> {
                        Log.d("DashboardViewModel", "Energy loaded: ${energyResult.data.currentWatts}W")
                        energyResult.data
                    }
                    is Result.Error -> {
                        Log.e("DashboardViewModel", "Failed to load energy", energyResult.exception)
                        null
                    }
                    else -> null
                }

                // Load share statistics
                val shareStatsResult = getShareStatisticsUseCase()
                val shareStats = when (shareStatsResult) {
                    is Result.Success -> shareStatsResult.data
                    is Result.Error -> {
                        Log.e("DashboardViewModel", "Failed to load share stats", shareStatsResult.exception)
                        null
                    }
                    else -> null
                }

                // Load recent files from activity tracking (with fallback to root listing)
                val recentFilesResult = getRecentFilesUseCase(limit = 5)
                val recentFiles = when (recentFilesResult) {
                    is Result.Success -> recentFilesResult.data
                    is Result.Error -> {
                        Log.w("DashboardViewModel", "Activity API unavailable, falling back to root listing")
                        emptyList()
                    }
                    else -> emptyList()
                }
                val fallbackFiles = if (recentFiles.isEmpty()) {
                    val filesResult = getFilesUseCase("/", forceRefresh = false)
                    when (filesResult) {
                        is Result.Success -> filesResult.data.take(5)
                        else -> emptyList()
                    }
                } else {
                    emptyList()
                }

                // Load cache stats
                val cacheStats = getCacheStatsUseCase()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    username = username,
                    telemetry = telemetry,
                    energy = energy,
                    raidArrays = raidArrays,
                    smartDevices = smartDevices,
                    shareStats = shareStats,
                    recentFiles = recentFiles,
                    fallbackFiles = fallbackFiles,
                    cacheFileCount = cacheStats.fileCount,
                    error = null
                )

                loadSyncFolders()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Daten konnten nicht geladen werden: ${e.message}"
                )
            }
        }
    }
    
    private fun loadTelemetryData() {
        viewModelScope.launch {
            try {
                // Only reload telemetry and RAID status, not files
                val telemetryResult = getSystemTelemetryUseCase()
                val telemetry = when (telemetryResult) {
                    is Result.Success -> telemetryResult.data
                    is Result.Error -> null
                    else -> null
                }
                
                val raidResult = getRaidStatusUseCase()
                val raidArrays = when (raidResult) {
                    is Result.Success -> raidResult.data
                    is Result.Error -> emptyList()
                    else -> emptyList()
                }

                val smartResult = getSmartStatusUseCase()
                val smartDevices = when (smartResult) {
                    is Result.Success -> smartResult.data.devices
                    is Result.Error -> _uiState.value.smartDevices
                    else -> _uiState.value.smartDevices
                }

                val energyResult = getEnergyDashboardUseCase()
                val energy = when (energyResult) {
                    is Result.Success -> energyResult.data
                    is Result.Error -> null
                    else -> null
                }

                val shareStatsResult = getShareStatisticsUseCase()
                val shareStats = when (shareStatsResult) {
                    is Result.Success -> shareStatsResult.data
                    is Result.Error -> _uiState.value.shareStats
                    else -> _uiState.value.shareStats
                }

                _uiState.value = _uiState.value.copy(
                    telemetry = telemetry,
                    energy = energy,
                    raidArrays = raidArrays,
                    smartDevices = smartDevices,
                    shareStats = shareStats
                )
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Failed to poll telemetry", e)
            }
        }
    }
    
    fun refresh() {
        loadDashboardData()
    }
    
    /**
     * Observe home network status for VPN hint banner.
     */
    private fun observeHomeNetworkState() {
        viewModelScope.launch {
            preferencesManager.getServerUrl().collectLatest { serverUrl ->
                if (serverUrl != null) {
                    networkStateManager.observeHomeNetworkStatus(serverUrl)
                        .collect { isHome -> 
                            _isInHomeNetwork.value = isHome
                                _isVpnActive.value = networkStateManager.isVpnActive()
                        }
                }
            }
        }
    }
    
    private fun observePendingOperations() {
        viewModelScope.launch {
            offlineQueueRepository.getPendingOperations().collectLatest { operations ->
                val pendingCount = operations.count {
                    it.status == OperationStatus.PENDING || it.status == OperationStatus.RETRYING
                }
                val failedCount = operations.count { it.status == OperationStatus.FAILED }
                _uiState.value = _uiState.value.copy(
                    pendingSyncCount = pendingCount,
                    failedSyncCount = failedCount
                )
            }
        }
    }

    private fun loadSyncFolders() {
        viewModelScope.launch {
            try {
                val deviceId = preferencesManager.getDeviceId().first() ?: return@launch
                val result = syncRepository.getSyncFolders(deviceId)
                val folders = result.getOrNull() ?: emptyList()
                _uiState.value = _uiState.value.copy(
                    activeSyncFolders = folders.count { it.syncStatus == SyncStatus.SYNCING },
                    syncFolderCount = folders.size
                )
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Failed to load sync folders", e)
            }
        }
    }

    private fun loadUserRole() {
        viewModelScope.launch {
            val role = preferencesManager.getUserRole().first() ?: "user"
            _isAdmin.value = role == "admin"
        }
        viewModelScope.launch {
            preferencesManager.isFritzBoxConfigured().collectLatest { configured ->
                _isFritzBoxConfigured.value = configured
            }
        }
    }

    fun sendWol() {
        viewModelScope.launch {
            _powerActionInProgress.value = true
            when (val result = sendWolUseCase()) {
                is Result.Success -> _snackbarEvent.emit("WoL-Signal gesendet")
                is Result.Error -> _snackbarEvent.emit(result.exception.message ?: "WoL fehlgeschlagen")
                else -> {}
            }
            _powerActionInProgress.value = false
        }
    }

    fun sendSoftSleep() {
        viewModelScope.launch {
            _powerActionInProgress.value = true
            when (val result = sendSoftSleepUseCase()) {
                is Result.Success -> _snackbarEvent.emit("Sleep-Modus aktiviert")
                is Result.Error -> _snackbarEvent.emit(result.exception.message ?: "Sleep fehlgeschlagen")
                else -> {}
            }
            _powerActionInProgress.value = false
        }
    }

    fun sendSuspend() {
        viewModelScope.launch {
            _powerActionInProgress.value = true
            when (val result = sendSuspendUseCase()) {
                is Result.Success -> _snackbarEvent.emit("System wird suspendiert")
                is Result.Error -> _snackbarEvent.emit(result.exception.message ?: "Suspend fehlgeschlagen")
                else -> {}
            }
            _powerActionInProgress.value = false
        }
    }

    /**
     * Check if VPN config is available in preferences.
     */
    private fun checkVpnConfig() {
        viewModelScope.launch {
            preferencesManager.getVpnConfig().collect { config ->
                _hasVpnConfig.value = !config.isNullOrEmpty()
            }
        }
    }
    
    /**
     * Dismiss VPN hint banner.
     */
    fun dismissVpnBanner() {
        _vpnBannerDismissed.value = true
    }
    
    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

data class DashboardUiState(
    val isLoading: Boolean = false,
    val username: String = "",
    val telemetry: SystemInfo? = null,
    val energy: EnergyDashboard? = null,
    val raidArrays: List<RaidArray> = emptyList(),
    val smartDevices: List<SmartDeviceInfo> = emptyList(),
    val shareStats: ShareStatistics? = null,
    val recentFiles: List<RecentFile> = emptyList(),
    val fallbackFiles: List<FileItem> = emptyList(),
    val cacheFileCount: Int = 0,
    val error: String? = null,
    val pendingSyncCount: Int = 0,
    val failedSyncCount: Int = 0,
    val activeSyncFolders: Int = 0,
    val syncFolderCount: Int = 0
)
