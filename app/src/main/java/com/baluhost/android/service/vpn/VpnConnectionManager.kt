package com.baluhost.android.service.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.baluhost.android.presentation.MainActivity
import com.baluhost.android.util.Constants
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton manager for WireGuard VPN connections.
 *
 * Uses GoBackend which manages its own GoBackend.VpnService internally.
 */
@Singleton
class VpnConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val backend = GoBackend(context)
    private var tunnel: BaluTunnel? = null

    init {
        createNotificationChannel()
    }

    /**
     * Connect to VPN. Caller must ensure VpnService.prepare() returned null
     * (from Activity context) before calling this.
     */
    fun connect(configString: String) {
        Log.d(TAG, "Starting VPN connection")

        val config = Config.parse(configString.byteInputStream())
        Log.d(TAG, "Config parsed: iface=${config.`interface`.addresses}, peers=${config.peers.size}")

        if (tunnel == null) {
            tunnel = BaluTunnel("BaluHost")
        }

        try {
            backend.setState(tunnel!!, Tunnel.State.UP, config)
        } catch (e: BackendException) {
            Log.e(TAG, "BackendException reason: ${e.reason}", e)
            if (e.reason == BackendException.Reason.VPN_NOT_AUTHORIZED) {
                throw VpnNotAuthorizedException("VPN nicht autorisiert. Bitte erneut erlauben.", e)
            }
            throw e
        }

        Log.i(TAG, "VPN tunnel started successfully")
        showNotification(true)
    }

    fun disconnect() {
        Log.d(TAG, "Stopping VPN connection")

        tunnel?.let { t ->
            try {
                backend.setState(t, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping tunnel", e)
            }
        }
        tunnel = null

        Log.i(TAG, "VPN connection stopped")
        showNotification(false)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.VPN_NOTIFICATION_CHANNEL,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows VPN connection status"
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(isConnected: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        if (isConnected) {
            val notification = NotificationCompat.Builder(context, Constants.VPN_NOTIFICATION_CHANNEL)
                .setContentTitle("VPN Connected")
                .setContentText("Connection active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
            notificationManager.notify(Constants.VPN_NOTIFICATION_ID, notification)
        } else {
            notificationManager.cancel(Constants.VPN_NOTIFICATION_ID)
        }
    }

    companion object {
        private const val TAG = "VpnConnectionManager"
    }
}

class VpnNotAuthorizedException(message: String, cause: Throwable) : Exception(message, cause)

private class BaluTunnel(private val tunnelName: String) : Tunnel {
    override fun getName(): String = tunnelName
    override fun onStateChange(newState: Tunnel.State) {
        Log.d("BaluTunnel", "State changed to: $newState")
    }
}
