package com.baluhost.android.presentation.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.util.BssidReader
import com.baluhost.android.util.ByteFormatter
import com.baluhost.android.util.ByteUnitMode
import com.baluhost.android.util.NetworkMonitor
import com.baluhost.android.data.local.security.AppLockManager
import com.baluhost.android.data.local.security.BiometricAuthManager
import com.baluhost.android.data.local.security.PinManager
import com.baluhost.android.data.local.security.SecurePreferencesManager
import com.baluhost.android.domain.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Settings screen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val preferencesManager: PreferencesManager,
    private val securePreferences: SecurePreferencesManager,
    private val biometricAuthManager: BiometricAuthManager,
    private val pinManager: PinManager,
    private val appLockManager: AppLockManager,
    private val getCacheStatsUseCase: com.baluhost.android.domain.usecase.cache.GetCacheStatsUseCase,
    private val clearCacheUseCase: com.baluhost.android.domain.usecase.cache.ClearCacheUseCase,
    private val bssidReader: BssidReader,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()
    
    init {
        loadUserInfo()
        loadSecuritySettings()
        loadCacheStats()
        loadByteUnitMode()
        loadNetworkSettings()
        observeWifiState()
    }
    
    private fun loadUserInfo() {
        viewModelScope.launch {
            val username = preferencesManager.getUsername().first()
            val serverUrl = preferencesManager.getServerUrl().first()
            val deviceId = preferencesManager.getDeviceId().first()
            
            _uiState.update { currentState ->
                currentState.copy(
                    username = username ?: "Unknown",
                    serverUrl = serverUrl ?: "Not connected",
                    deviceId = deviceId
                )
            }
            val role = preferencesManager.getUserRole().first() ?: "user"
            _isAdmin.value = role == "admin"
        }
    }

    private fun loadSecuritySettings() {
        viewModelScope.launch {
            val biometricStatus = biometricAuthManager.checkBiometricAvailability(allowDeviceCredential = false)
            val biometricAvailable = biometricStatus == BiometricAuthManager.BiometricStatus.AVAILABLE
            val biometricEnabled = securePreferences.isBiometricEnabled()
            val pinConfigured = pinManager.isPinConfigured()
            val appLockEnabled = securePreferences.isAppLockEnabled()
            val lockTimeout = appLockManager.getLockTimeoutMillis()
            
            _uiState.update { currentState ->
                currentState.copy(
                    biometricAvailable = biometricAvailable,
                    biometricEnabled = biometricEnabled,
                    pinConfigured = pinConfigured,
                    appLockEnabled = appLockEnabled,
                    lockTimeoutMinutes = (lockTimeout / 60_000).toInt()
                )
            }
        }
    }
    
    fun toggleBiometric(enabled: Boolean) {
        viewModelScope.launch {
            securePreferences.setBiometricEnabled(enabled)
            
            // If enabling biometric, disable PIN (only one method allowed)
            if (enabled && pinManager.isPinConfigured()) {
                pinManager.removePin()
                _uiState.update { it.copy(
                    biometricEnabled = true,
                    pinConfigured = false
                ) }
            } else {
                _uiState.update { it.copy(biometricEnabled = enabled) }
            }
            
            // Automatically enable app lock when biometric is enabled
            if (enabled) {
                securePreferences.setAppLockEnabled(true)
                _uiState.update { it.copy(appLockEnabled = true) }
            } else {
                // If disabling biometric and no PIN, disable app lock
                if (!pinManager.isPinConfigured()) {
                    securePreferences.setAppLockEnabled(false)
                    _uiState.update { it.copy(appLockEnabled = false) }
                }
            }
        }
    }
    
    fun toggleAppLock(enabled: Boolean) {
        viewModelScope.launch {
            securePreferences.setAppLockEnabled(enabled)
            _uiState.update { it.copy(appLockEnabled = enabled) }
        }
    }
    
    fun setLockTimeout(minutes: Int) {
        viewModelScope.launch {
            appLockManager.setLockTimeoutMillis(minutes * 60_000L)
            _uiState.update { it.copy(lockTimeoutMinutes = minutes) }
        }
    }
    
    fun setupPin(pin: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                pinManager.setupPin(pin)
                
                // If setting up PIN, disable biometric (only one method allowed)
                if (securePreferences.isBiometricEnabled()) {
                    securePreferences.setBiometricEnabled(false)
                    _uiState.update { it.copy(
                        pinConfigured = true,
                        biometricEnabled = false
                    ) }
                } else {
                    _uiState.update { it.copy(pinConfigured = true) }
                }
                
                // Automatically enable app lock when PIN is set up
                securePreferences.setAppLockEnabled(true)
                _uiState.update { it.copy(appLockEnabled = true) }
                
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to setup PIN")
            }
        }
    }
    
    fun removePin() {
        viewModelScope.launch {
            pinManager.removePin()
            _uiState.update { it.copy(pinConfigured = false) }
            
            // If no authentication method left, disable app lock
            if (!securePreferences.isBiometricEnabled()) {
                securePreferences.setAppLockEnabled(false)
                _uiState.update { it.copy(appLockEnabled = false) }
            }
        }
    }
    
    /**
     * Delete the current device from the server and clear local data.
     */
    fun deleteDevice() {
        viewModelScope.launch {
            val deviceId = _uiState.value.deviceId

            android.util.Log.d("SettingsViewModel", "Attempting to delete device: $deviceId")

            _uiState.update { it.copy(isDeleting = true, error = null) }

            // Best-effort: try to notify server, but always clear locally
            if (deviceId != null) {
                try {
                    deviceRepository.deleteDevice(deviceId)
                    android.util.Log.d("SettingsViewModel", "Device deleted on server")
                } catch (e: Exception) {
                    android.util.Log.w("SettingsViewModel", "Server deletion failed (proceeding with local cleanup): ${e.message}")
                }
            }

            // Always clear local data regardless of server response
            preferencesManager.clearAll()
            preferencesManager.saveOnboardingCompleted(false)
            securePreferences.clearAll()

            android.util.Log.d("SettingsViewModel", "Local data cleared, navigating to setup")

            _uiState.update { it.copy(
                isDeleting = false,
                deviceDeleted = true
            ) }
        }
    }
    
    private fun loadCacheStats() {
        viewModelScope.launch {
            try {
                val stats = getCacheStatsUseCase()
                _uiState.update { currentState ->
                    currentState.copy(
                        cacheFileCount = stats.fileCount,
                        cacheOldestAgeDays = stats.oldestFileAge?.let { (it / (1000 * 60 * 60 * 24)).toInt() },
                        cacheNewestAgeDays = stats.newestFileAge?.let { (it / (1000 * 60 * 60 * 24)).toInt() }
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Cache-Statistiken konnten nicht geladen werden") }
            }
        }
    }
    
    fun clearCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingCache = true) }
            try {
                clearCacheUseCase()
                // Reload stats after clearing
                kotlinx.coroutines.delay(500)
                loadCacheStats()
                _uiState.update { it.copy(isClearingCache = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isClearingCache = false,
                    error = "Cache konnte nicht gelöscht werden"
                ) }
            }
        }
    }
    
    private fun loadByteUnitMode() {
        viewModelScope.launch {
            val mode = preferencesManager.getByteUnitMode().first()
            val byteUnitMode = if (mode == "decimal") ByteUnitMode.DECIMAL else ByteUnitMode.BINARY
            ByteFormatter.mode = byteUnitMode
            _uiState.update { it.copy(byteUnitMode = byteUnitMode) }
        }
    }

    fun setByteUnitMode(mode: ByteUnitMode) {
        viewModelScope.launch {
            ByteFormatter.mode = mode
            val modeString = if (mode == ByteUnitMode.DECIMAL) "decimal" else "binary"
            preferencesManager.saveByteUnitMode(modeString)
            _uiState.update { it.copy(byteUnitMode = mode) }
        }
    }

    private fun loadNetworkSettings() {
        viewModelScope.launch {
            val bssid = preferencesManager.getHomeBssidOnce()
            val autoVpn = preferencesManager.isAutoVpnOnExternal().first()
            val onWifi = networkMonitor.isCurrentlyWifiConnected()
            _uiState.update { it.copy(
                homeBssidConfigured = bssid != null,
                autoVpnOnExternal = autoVpn,
                isOnWifi = onWifi
            ) }
        }
    }

    private fun observeWifiState() {
        viewModelScope.launch {
            networkMonitor.isWifiConnected.collect { onWifi ->
                _uiState.update { it.copy(isOnWifi = onWifi) }
            }
        }
    }

    fun setHomeNetwork() {
        viewModelScope.launch {
            val bssid = bssidReader.getCurrentBssid()
            if (bssid != null) {
                preferencesManager.saveHomeBssid(bssid)
                _uiState.update { it.copy(homeBssidConfigured = true, error = null) }
            } else {
                val msg = if (networkMonitor.isCurrentlyWifiConnected()) {
                    "BSSID konnte nicht gelesen werden – prüfe die WLAN-Berechtigung"
                } else {
                    "Verbinde dich zuerst mit deinem Heim-WLAN"
                }
                _uiState.update { it.copy(error = msg) }
            }
        }
    }

    fun toggleAutoVpn(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveAutoVpnOnExternal(enabled)
            _uiState.update { it.copy(autoVpnOnExternal = enabled) }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}

/**
 * UI state for Settings screen.
 */
data class SettingsUiState(
    val username: String = "",
    val serverUrl: String = "",
    val deviceId: String? = null,
    val isDeleting: Boolean = false,
    val deviceDeleted: Boolean = false,
    val error: String? = null,
    // Security settings
    val biometricAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
    val pinConfigured: Boolean = false,
    val appLockEnabled: Boolean = false,
    val lockTimeoutMinutes: Int = 5,
    // Cache management
    val cacheFileCount: Int = 0,
    val cacheOldestAgeDays: Int? = null,
    val cacheNewestAgeDays: Int? = null,
    val isClearingCache: Boolean = false,
    // Byte unit mode
    val byteUnitMode: com.baluhost.android.util.ByteUnitMode = com.baluhost.android.util.ByteUnitMode.BINARY,
    // Network settings
    val homeBssidConfigured: Boolean = false,
    val autoVpnOnExternal: Boolean = false,
    val isOnWifi: Boolean = false
)
