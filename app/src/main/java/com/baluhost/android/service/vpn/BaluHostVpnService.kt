package com.baluhost.android.service.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.baluhost.android.R
import com.baluhost.android.presentation.MainActivity
import com.baluhost.android.util.Constants
import com.wireguard.android.backend.GoBackend
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.Method

/**
 * VPN Service using WireGuard Go library directly via VpnService.Builder.
 *
 * Registers this VpnService with GoBackend's internal static reference so
 * the native Go code can call protect() on sockets BEFORE sending packets.
 * This prevents routing loops where handshake packets get sent through the
 * VPN tunnel instead of the real network.
 */
class BaluHostVpnService : VpnService() {

    private var tunnelHandle = -1
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Coroutine exception in VPN service", throwable)
            stopSelf()
        }
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(Constants.VPN_NOTIFICATION_ID, createNotification(false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val configString = intent.getStringExtra(EXTRA_CONFIG)
                if (configString != null) {
                    serviceScope.launch {
                        try {
                            // Stop any existing tunnel before starting a new one
                            stopVpn()
                            startVpn(configString)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start VPN", e)
                            updateNotification(false)
                            stopSelf()
                        }
                    }
                } else {
                    Log.e(TAG, "No VPN config provided")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    stopVpn()
                }
                stopSelf()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Register this VpnService with GoBackend's static vpnService field.
     *
     * The WireGuard Go native code calls back to GoBackend to protect UDP
     * sockets via VpnService.protect(fd). Without this, sockets created
     * during wgTurnOn are NOT protected and the initial handshake packets
     * get routed back through the VPN tunnel (routing loop).
     */
    private fun registerWithGoBackend() {
        try {
            val vpnServiceField = GoBackend::class.java.getDeclaredField("vpnService")
            vpnServiceField.isAccessible = true

            // Create a fresh GhettoCompletableFuture and complete it with our service
            val currentFuture = vpnServiceField.get(null)
            val futureClass = currentFuture.javaClass

            val constructor = futureClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val newFuture = constructor.newInstance()

            val completeMethod = futureClass.getDeclaredMethod("complete", Any::class.java)
            completeMethod.isAccessible = true
            completeMethod.invoke(newFuture, this)

            vpnServiceField.set(null, newFuture)
            Log.d(TAG, "Registered with GoBackend for automatic socket protection")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register with GoBackend — sockets will be protected manually after tunnel start", e)
        }
    }

    /**
     * Unregister from GoBackend so it doesn't hold a reference to a destroyed service.
     */
    private fun unregisterFromGoBackend() {
        try {
            val vpnServiceField = GoBackend::class.java.getDeclaredField("vpnService")
            vpnServiceField.isAccessible = true
            val currentFuture = vpnServiceField.get(null)
            val futureClass = currentFuture.javaClass
            val constructor = futureClass.getDeclaredConstructor()
            constructor.isAccessible = true
            vpnServiceField.set(null, constructor.newInstance())
            Log.d(TAG, "Unregistered from GoBackend")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister from GoBackend", e)
        }
    }

    private fun startVpn(configString: String) {
        Log.d(TAG, "Starting VPN connection")

        val config = Config.parse(configString.byteInputStream())
        Log.d(TAG, "Config parsed: ${config.peers.size} peer(s), " +
                "addresses=${config.`interface`.addresses}, " +
                "dns=${config.`interface`.dnsServers}")

        // Build VPN interface
        val builder = Builder()
        builder.setSession("BaluHost")

        for (addr in config.`interface`.addresses) {
            Log.d(TAG, "Adding address: ${addr.address}/${addr.mask}")
            builder.addAddress(addr.address, addr.mask)
        }

        for (dns in config.`interface`.dnsServers) {
            Log.d(TAG, "Adding DNS: ${dns.hostAddress}")
            builder.addDnsServer(dns.hostAddress!!)
        }

        for (peer in config.peers) {
            Log.d(TAG, "Peer endpoint: ${peer.endpoint.orElse(null)}")
            for (allowedIp in peer.allowedIps) {
                Log.d(TAG, "Adding route: ${allowedIp.address}/${allowedIp.mask}")
                builder.addRoute(allowedIp.address, allowedIp.mask)
            }
        }

        builder.setMtu(config.`interface`.mtu.orElse(1280))
        builder.setBlocking(true)

        // Exclude own app from VPN to avoid routing loops
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not exclude own app from VPN", e)
        }

        Log.d(TAG, "Calling establish()...")
        val tun = builder.establish()
        if (tun == null) {
            val prepareIntent = VpnService.prepare(this)
            Log.e(TAG, "establish() returned null. prepare() result: $prepareIntent")
            if (prepareIntent != null) {
                Log.d(TAG, "Launching VPN consent dialog from service")
                prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(prepareIntent)
            }
            throw IllegalStateException("Failed to establish VPN interface — permission denied?")
        }

        Log.d(TAG, "VPN interface established, fd=${tun.fd}")

        // Tell Android to use the current default network (WiFi/cellular) as
        // the underlying transport for this VPN. Without this, protected sockets
        // have no route to the endpoint — even if protect() returns true.
        setUnderlyingNetworks(null)
        Log.d(TAG, "Set underlying networks to system default")

        // Register with GoBackend BEFORE wgTurnOn so native code can
        // call protect() on sockets as they are created during startup.
        // This prevents the handshake routing loop.
        registerWithGoBackend()

        // Get userspace config string for WireGuard Go
        val wgConfig = config.toWgUserspaceString()

        // Call WireGuard Go native wgTurnOn via reflection
        val handle = wgTurnOn.invoke(null, "BaluHost", tun.detachFd(), wgConfig) as Int

        if (handle < 0) {
            throw IllegalStateException("wgTurnOn failed with code: $handle")
        }

        tunnelHandle = handle
        Log.d(TAG, "WireGuard tunnel started, handle=$handle")

        // Also protect sockets explicitly as a fallback
        protectSockets(handle)

        Log.i(TAG, "VPN tunnel started successfully")
        updateNotification(true)
    }

    /**
     * Explicitly protect WireGuard UDP sockets as a fallback.
     * The primary protection happens via GoBackend registration above.
     */
    private fun protectSockets(handle: Int) {
        try {
            val socketV4 = wgGetSocketV4.invoke(null, handle) as Int
            if (socketV4 >= 0) {
                val ok = protect(socketV4)
                Log.d(TAG, "Protected IPv4 socket fd=$socketV4: $ok")
            }
            val socketV6 = wgGetSocketV6.invoke(null, handle) as Int
            if (socketV6 >= 0) {
                val ok = protect(socketV6)
                Log.d(TAG, "Protected IPv6 socket fd=$socketV6: $ok")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to protect WireGuard sockets manually", e)
        }
    }

    private fun stopVpn() {
        try {
            if (tunnelHandle >= 0) {
                wgTurnOff.invoke(null, tunnelHandle)
                Log.d(TAG, "WireGuard tunnel stopped, handle=$tunnelHandle")
                tunnelHandle = -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WireGuard tunnel", e)
        }

        unregisterFromGoBackend()
        Log.i(TAG, "VPN connection stopped")
        updateNotification(false)
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

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(isConnected: Boolean): android.app.Notification {
        val title = if (isConnected) "VPN Connected" else "VPN Disconnected"
        val text = if (isConnected) "Connection active" else "Tap to connect"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, BaluHostVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }

        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.VPN_NOTIFICATION_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .apply {
                if (isConnected) {
                    addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Disconnect",
                        disconnectPendingIntent
                    )
                }
            }
            .build()
    }

    private fun updateNotification(isConnected: Boolean) {
        val notification = createNotification(isConnected)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(Constants.VPN_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        serviceScope.launch { stopVpn() }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BaluHostVpnService"

        const val ACTION_CONNECT = "com.baluhost.android.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.baluhost.android.vpn.DISCONNECT"
        const val EXTRA_CONFIG = "config"

        /** Emits the VPN consent Intent when establish() fails due to missing permission. */
        private val _needsPermission = MutableStateFlow<Intent?>(null)
        val needsPermission: StateFlow<Intent?> = _needsPermission.asStateFlow()

        fun clearPermissionRequest() {
            _needsPermission.value = null
        }

        // Load WireGuard Go native library
        init {
            System.loadLibrary("wg-go")
        }

        // WireGuard Go native methods accessed via reflection on GoBackend
        private val wgTurnOn: Method by lazy {
            GoBackend::class.java.getDeclaredMethod(
                "wgTurnOn",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            ).apply { isAccessible = true }
        }

        private val wgTurnOff: Method by lazy {
            GoBackend::class.java.getDeclaredMethod(
                "wgTurnOff",
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }

        private val wgGetSocketV4: Method by lazy {
            GoBackend::class.java.getDeclaredMethod(
                "wgGetSocketV4",
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }

        private val wgGetSocketV6: Method by lazy {
            GoBackend::class.java.getDeclaredMethod(
                "wgGetSocketV6",
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }
    }
}
