package com.baluhost.android.data.notification

import android.util.Log
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.remote.api.NotificationsApi
import com.baluhost.android.data.remote.dto.NotificationDto
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class NotificationWebSocketManager @Inject constructor(
    @Named("websocket") private val okHttpClient: OkHttpClient,
    private val notificationsApi: NotificationsApi,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val TAG = "NotificationWS"
        private const val PING_INTERVAL_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 3_000L
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isConnecting = AtomicBoolean(false)
    private var reconnectAttempts = 0

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _latestNotification = MutableSharedFlow<NotificationDto>(extraBufferCapacity = 10)
    val latestNotification: SharedFlow<NotificationDto> = _latestNotification.asSharedFlow()

    /** Increment unread count from external sources (e.g. FCM push). */
    fun incrementUnreadCount() {
        _unreadCount.value += 1
    }

    suspend fun connect() {
        if (webSocket != null || isConnecting.get()) {
            Log.d(TAG, "Already connected or connecting, skipping")
            return
        }
        isConnecting.set(true)
        try {
            val serverUrl = preferencesManager.getServerUrl().first() ?: run {
                Log.w(TAG, "No server URL configured")
                isConnecting.set(false)
                return
            }

            // Get WS token
            val wsToken = try {
                notificationsApi.getWsToken().token
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get WS token", e)
                isConnecting.set(false)
                return
            }

            val wsUrl = buildWsUrl(serverUrl, wsToken)
            Log.d(TAG, "Connecting to WebSocket: $wsUrl")

            val request = Request.Builder().url(wsUrl).build()
            webSocket = okHttpClient.newWebSocket(request, createListener())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            isConnecting.set(false)
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        pingJob?.cancel()
        reconnectJob?.cancel()
        reconnectAttempts = 0
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connected.value = false
        isConnecting.set(false)
    }

    fun sendMarkRead(id: Int) {
        val msg = JsonObject().apply {
            addProperty("type", "mark_read")
            add("payload", JsonObject().apply {
                addProperty("notification_id", id)
            })
        }
        webSocket?.send(msg.toString())
    }

    private fun createListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            isConnecting.set(false)
            _connected.value = true
            reconnectAttempts = 0
            startPing()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JsonParser.parseString(text).asJsonObject
                val type = json.get("type")?.asString ?: return
                val payload = json.getAsJsonObject("payload")

                when (type) {
                    "unread_count" -> {
                        val count = payload?.get("count")?.asInt ?: 0
                        _unreadCount.value = count
                    }
                    "notification" -> {
                        payload?.let {
                            val notification = gson.fromJson(it, NotificationDto::class.java)
                            scope.launch { _latestNotification.emit(notification) }
                        }
                    }
                    "pong" -> {
                        // Keep-alive acknowledged
                    }
                    else -> Log.d(TAG, "Unknown message type: $type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message: $text", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            cleanup()
            if (code != 1000) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            cleanup()
            scheduleReconnect()
        }
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                val msg = JsonObject().apply { addProperty("type", "ping") }
                webSocket?.send(msg.toString())
            }
        }
    }

    private fun cleanup() {
        pingJob?.cancel()
        webSocket = null
        _connected.value = false
        isConnecting.set(false)
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempts++
            val delay = RECONNECT_BASE_DELAY_MS * reconnectAttempts
            Log.d(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
            delay(delay)
            connect()
        }
    }

    private fun buildWsUrl(serverUrl: String, token: String): String {
        val baseUrl = serverUrl
            .trimEnd('/')
            .removeSuffix("/api")
        val wsBase = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        return "$wsBase/api/notifications/ws?token=$token"
    }
}
