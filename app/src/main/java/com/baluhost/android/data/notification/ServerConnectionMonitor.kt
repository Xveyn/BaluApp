package com.baluhost.android.data.notification

import android.util.Log
import com.baluhost.android.data.remote.api.SystemApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed class ConnectionState {
    data class Connected(val uptimeText: String) : ConnectionState()
    data object Disconnected : ConnectionState()
    data object Idle : ConnectionState()
}

@Singleton
class ServerConnectionMonitor @Inject constructor(
    private val systemApi: SystemApi
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTags = mutableSetOf<String>()
    private var pollingJob: kotlinx.coroutines.Job? = null

    fun acquire(tag: String) {
        synchronized(activeTags) {
            activeTags.add(tag)
            if (activeTags.size == 1) {
                startPolling()
            }
        }
    }

    fun release(tag: String) {
        synchronized(activeTags) {
            activeTags.remove(tag)
            if (activeTags.isEmpty()) {
                stopPolling()
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (true) {
                try {
                    val info = systemApi.getSystemInfo()
                    @Suppress("UNNECESSARY_SAFE_CALL")
                    val uptimeSeconds = info.uptime?.toLong() ?: 0L
                    val days = uptimeSeconds / 86400
                    val hours = (uptimeSeconds % 86400) / 3600
                    val minutes = (uptimeSeconds % 3600) / 60
                    _connectionState.value = ConnectionState.Connected("${days}d ${hours}h ${minutes}m")
                } catch (e: Exception) {
                    Log.d(TAG, "Server unreachable: ${e.message}")
                    _connectionState.value = ConnectionState.Disconnected
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _connectionState.value = ConnectionState.Idle
    }

    companion object {
        private const val TAG = "ServerConnectionMonitor"
        private const val POLL_INTERVAL_MS = 30_000L
    }
}
