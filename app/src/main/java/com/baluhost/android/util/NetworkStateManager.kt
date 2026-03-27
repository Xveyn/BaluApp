package com.baluhost.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import com.baluhost.android.data.local.datastore.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Manages network state and detects if device is in home network.
 * 
 * Uses IP subnet comparison to determine if device is on same network as NAS.
 * This approach doesn't require location permissions (unlike SSID checking).
 */
class NetworkStateManager(
    private val context: Context,
    private val networkMonitor: NetworkMonitor,
    private val bssidReader: BssidReader,
    preferencesManager: PreferencesManager
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var cachedHomeBssid: String? = null

    init {
        scope.launch {
            preferencesManager.getHomeBssid().collect { cachedHomeBssid = it }
        }
    }

    private val _isInHomeNetwork = MutableStateFlow<Boolean?>(null)
    val isInHomeNetwork: Flow<Boolean?> = _isInHomeNetwork.asStateFlow()

    private var cachedServerUrl: String? = null
    private var lastCheckTime: Long = 0
    private val CACHE_DURATION_MS = 30_000L // 30 seconds
    
    /**
     * Check whether the device is on the home network via BSSID match or subnet comparison.
     *
     * IMPORTANT: This method does NOT check VPN or WiFi state. It is designed to be called
     * from [observeHomeNetworkStatus], which guards with VPN-active and WiFi-connected checks
     * before invoking this method. Do not call directly without those guards.
     */
    fun checkHomeNetworkStatus(serverUrl: String): Boolean? {
        cachedServerUrl = serverUrl
        val now = System.currentTimeMillis()

        // BSSID check — instant, bypasses 30s cache
        val currentBssid = bssidReader.getCurrentBssid()
        val storedBssid = cachedHomeBssid
        if (currentBssid != null && storedBssid != null) {
            val isHomeBssid = currentBssid == storedBssid
            _isInHomeNetwork.value = isHomeBssid
            lastCheckTime = now
            return isHomeBssid
        }
        // BSSID inconclusive (null current or null stored) — fall through to subnet

        // Cache check — only for subnet fallback path (avoids frequent network lookups)
        if (now - lastCheckTime < CACHE_DURATION_MS && _isInHomeNetwork.value != null) {
            return _isInHomeNetwork.value
        }

        // Subnet comparison (existing fallback logic)
        val isHome = try {
            val nasIp = extractIpFromUrl(serverUrl)
            if (nasIp == null) {
                false
            } else {
                val deviceIp = getCurrentLocalIp()
                if (deviceIp == null) {
                    null
                } else {
                    isSameSubnet(nasIp, deviceIp)
                }
            }
        } catch (e: Exception) {
            null
        }

        _isInHomeNetwork.value = isHome
        lastCheckTime = now

        return isHome
    }
    
    /**
     * Check if device is on home network using BSSID only.
     * Does NOT check VPN or subnet. Returns false if either BSSID is unavailable.
     */
    fun isOnHomeNetworkByBssid(): Boolean {
        val current = bssidReader.getCurrentBssid() ?: return false
        val stored = cachedHomeBssid ?: return false
        return current == stored
    }

    /**
     * Reactive flow that emits network status changes.
     * Combines WiFi state with home network check.
     *
     * Returns true if in home network OR connected via VPN.
     */
    fun observeHomeNetworkStatus(serverUrl: String): Flow<Boolean?> {
        return combine(
            networkMonitor.isWifiConnected,
            _isInHomeNetwork
        ) { isWifi, isHome ->
            // Check if VPN is active (use fallback-aware check)
            val isVpnActive = isVpnConnectedFallback()
            
            if (isVpnActive) {
                // VPN connected → treat as "in home network"
                true
            } else if (!isWifi) {
                // Not on WiFi and no VPN → Definitely not home network
                false
            } else {
                // On WiFi → Check if it's home network
                checkHomeNetworkStatus(serverUrl)
            }
        }
    }
    
    /**
     * Check if VPN is currently active.
     */
    private fun isVpnConnected(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fallback-aware VPN detection.
     * First tries ConnectivityManager transport check, then falls back to
     * checking for typical VPN network interfaces (tun/tap/ppp).
     */
    private fun isVpnConnectedFallback(): Boolean {
        // Primary check
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            if (networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true
            }
        } catch (_: Exception) {
            // ignore and continue to fallback
        }

        // Fallback: look for VPN-like network interface names
        return try {
            NetworkInterface.getNetworkInterfaces().toList().any { ni ->
                val name = ni.name.lowercase()
                name.contains("tun") || name.contains("tap") || name.contains("ppp")
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Public helper to check VPN state synchronously.
     * Other components can use this to decide UI behavior.
     */
    fun isVpnActive(): Boolean = isVpnConnectedFallback()
    
    /**
     * Extract IP address from server URL.
     * 
     * Examples:
     * - "http://192.168.178.21:8000" → "192.168.178.21"
     * - "https://nas.local" → null (domain name, not IP)
     */
    private fun extractIpFromUrl(url: String): String? {
        return try {
            val cleanUrl = url
                .replace("http://", "")
                .replace("https://", "")
                .split(":")[0] // Remove port
            
            // Validate if it's an IP address (not domain)
            InetAddress.getByName(cleanUrl)
            if (cleanUrl.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))) {
                cleanUrl
            } else {
                null // It's a domain name, not IP
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get device's current local IPv4 address.
     * Excludes loopback and VPN addresses.
     */
    private fun getCurrentLocalIp(): String? {
        return try {
            // Method 1: Try via ConnectivityManager (more reliable)
            val network = connectivityManager.activeNetwork
            val linkProperties = connectivityManager.getLinkProperties(network)
            
            linkProperties?.linkAddresses
                ?.firstOrNull { linkAddress ->
                    val address = linkAddress.address
                    address is Inet4Address && 
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress
                }?.address?.hostAddress
                ?: getLocalIpFromInterfaces()
        } catch (e: Exception) {
            getLocalIpFromInterfaces()
        }
    }
    
    /**
     * Fallback method to get local IP via NetworkInterface.
     */
    private fun getLocalIpFromInterfaces(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { address ->
                    address is Inet4Address &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress
                }?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if two IP addresses are in same subnet.
     * 
     * @param ip1 First IP address (e.g., NAS IP)
     * @param ip2 Second IP address (e.g., device IP)
     * @param prefixLength Subnet mask bits (default: 24 for /24 = 255.255.255.0)
     * @return true if in same subnet
     */
    private fun isSameSubnet(
        ip1: String,
        ip2: String,
        prefixLength: Int = 24
    ): Boolean {
        return try {
            val addr1 = InetAddress.getByName(ip1).address
            val addr2 = InetAddress.getByName(ip2).address
            
            if (addr1.size != addr2.size) return false
            
            // Calculate subnet mask
            val mask = -1 shl (32 - prefixLength)
            
            // Convert byte arrays to integers
            val subnet1 = byteArrayToInt(addr1) and mask
            val subnet2 = byteArrayToInt(addr2) and mask
            
            subnet1 == subnet2
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Convert IPv4 byte array to integer.
     */
    private fun byteArrayToInt(bytes: ByteArray): Int {
        return ((bytes[0].toInt() and 0xFF) shl 24) or
               ((bytes[1].toInt() and 0xFF) shl 16) or
               ((bytes[2].toInt() and 0xFF) shl 8) or
               (bytes[3].toInt() and 0xFF)
    }
    
    /**
     * Force refresh network status (ignore cache).
     */
    fun refreshNetworkStatus(serverUrl: String) {
        lastCheckTime = 0
        checkHomeNetworkStatus(serverUrl)
    }
}
