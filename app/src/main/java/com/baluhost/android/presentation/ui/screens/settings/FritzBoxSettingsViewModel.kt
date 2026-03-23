package com.baluhost.android.presentation.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.network.FritzBoxTR064Client
import com.baluhost.android.data.network.WolResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FritzBoxSettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val tr064Client: FritzBoxTR064Client
) : ViewModel() {

    private val _uiState = MutableStateFlow(FritzBoxSettingsUiState())
    val uiState: StateFlow<FritzBoxSettingsUiState> = _uiState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    init {
        loadConfig()
    }

    private fun loadConfig() {
        viewModelScope.launch {
            val host = preferencesManager.getFritzBoxHost().first()
            val port = preferencesManager.getFritzBoxPort().first()
            val username = preferencesManager.getFritzBoxUsername().first()
            val mac = preferencesManager.getFritzBoxMacAddress().first()
            val hasPassword = preferencesManager.getFritzBoxPassword() != null

            _uiState.value = FritzBoxSettingsUiState(
                host = host,
                port = port.toString(),
                username = username,
                macAddress = mac,
                hasPassword = hasPassword
            )
        }
    }

    fun updateHost(host: String) {
        _uiState.value = _uiState.value.copy(host = host)
    }

    fun updatePort(port: String) {
        _uiState.value = _uiState.value.copy(port = port)
    }

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(username = username)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun updateMacAddress(mac: String) {
        _uiState.value = _uiState.value.copy(macAddress = mac.uppercase())
    }

    fun save() {
        val state = _uiState.value
        val port = state.port.toIntOrNull()

        if (port == null || port < 1 || port > 65535) {
            viewModelScope.launch { _snackbarEvent.emit("Ungültiger Port (1-65535)") }
            return
        }

        val macRegex = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        if (state.macAddress.isNotEmpty() && !macRegex.matches(state.macAddress)) {
            viewModelScope.launch { _snackbarEvent.emit("Ungültiges MAC-Format (XX:XX:XX:XX:XX:XX)") }
            return
        }

        viewModelScope.launch {
            preferencesManager.saveFritzBoxHost(state.host)
            preferencesManager.saveFritzBoxPort(port)
            preferencesManager.saveFritzBoxUsername(state.username)
            preferencesManager.saveFritzBoxMacAddress(state.macAddress)
            if (state.password.isNotEmpty()) {
                preferencesManager.saveFritzBoxPassword(state.password)
            }
            _uiState.value = _uiState.value.copy(hasPassword = state.password.isNotEmpty() || state.hasPassword)
            _snackbarEvent.emit("Einstellungen gespeichert")
        }
    }

    fun testConnection() {
        val state = _uiState.value
        val port = state.port.toIntOrNull() ?: 49000
        val password = if (state.password.isNotEmpty()) state.password
            else preferencesManager.getFritzBoxPassword() ?: ""

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true)
            val result = tr064Client.testConnection(state.host, port, state.username, password)
            _uiState.value = _uiState.value.copy(isTesting = false)

            when (result) {
                is WolResult.Success -> _snackbarEvent.emit("Verbindung erfolgreich!")
                is WolResult.AuthError -> _snackbarEvent.emit("Zugangsdaten ungültig")
                is WolResult.Unreachable -> _snackbarEvent.emit("Fritz!Box nicht erreichbar. VPN aktiv?")
                is WolResult.Error -> _snackbarEvent.emit("Fehler: ${result.message}")
            }
        }
    }
}

data class FritzBoxSettingsUiState(
    val host: String = "192.168.178.1",
    val port: String = "49000",
    val username: String = "",
    val password: String = "",
    val macAddress: String = "",
    val hasPassword: Boolean = false,
    val isTesting: Boolean = false
)
