package com.baluhost.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the current WiFi BSSID.
 * Uses ConnectivityManager on API 31+ with WifiManager fallback,
 * since some devices (e.g. Android 16) return placeholder BSSID
 * via ConnectivityManager even with ACCESS_FINE_LOCATION granted.
 */
@Singleton
class BssidReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PLACEHOLDER_BSSID = "02:00:00:00:00:00"

        /**
         * Normalize BSSID to uppercase.
         * Returns null for placeholders, null, or blank values.
         */
        fun normalizeBssid(bssid: String?): String? {
            if (bssid.isNullOrBlank() || bssid == PLACEHOLDER_BSSID) return null
            return bssid.uppercase()
        }
    }

    /**
     * Read the current WiFi BSSID.
     * Returns uppercase colon-separated MAC, or null if unavailable.
     */
    fun getCurrentBssid(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            normalizeBssid(readBssidModern()) ?: normalizeBssid(readBssidLegacy())
        } else {
            normalizeBssid(readBssidLegacy())
        }
    }

    private fun readBssidModern(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val wifiInfo = caps.transportInfo as? WifiInfo ?: return null
        return wifiInfo.bssid
    }

    @Suppress("DEPRECATION")
    private fun readBssidLegacy(): String? {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.connectionInfo.bssid
    }
}
