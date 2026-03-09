package com.baluhost.android.domain.usecase.vpn

import android.util.Base64
import android.util.Log
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.model.VpnConfig
import com.baluhost.android.util.Result
import com.baluhost.android.util.WireGuardConfigParser
import javax.inject.Inject

/**
 * Use case for importing VPN configuration from QR code.
 * 
 * Parses Base64-encoded WireGuard config, saves it to preferences,
 * and prepares for Android VPN Service registration.
 */
class ImportVpnConfigUseCase @Inject constructor(
    private val preferencesManager: PreferencesManager
) {
    
    suspend operator fun invoke(configBase64: String): Result<VpnConfig> {
        return try {
            val configString = String(
                Base64.decode(configBase64, Base64.DEFAULT),
                Charsets.UTF_8
            )

            Log.d(TAG, "Importing VPN config (${configString.length} bytes)")

            // Save config to preferences
            preferencesManager.saveVpnConfig(configString)

            // Parse config to extract key information
            val config = parseWireGuardConfig(configString)

            // Save parsed metadata so VpnViewModel/VpnRepositoryImpl can use them
            preferencesManager.saveVpnAssignedIp(config.assignedIp)
            if (config.serverPublicKey.isNotEmpty()) {
                preferencesManager.saveVpnPublicKey(config.serverPublicKey)
            }

            Log.d(TAG, "VPN config parsed: IP=${config.assignedIp}, Endpoint=${config.serverEndpoint}")

            Result.Success(config)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import VPN config", e)
            Result.Error(Exception("Failed to import VPN config: ${e.message}", e))
        }
    }
    
    private fun parseWireGuardConfig(configString: String): VpnConfig {
        val parsed = WireGuardConfigParser.parse(configString)
        return VpnConfig(
            clientId = 0,
            deviceName = "",
            publicKey = "",
            assignedIp = parsed.assignedIp,
            configString = configString,
            serverPublicKey = parsed.serverPublicKey,
            serverEndpoint = parsed.serverEndpoint,
            serverPort = parsed.serverPort
        )
    }
    
    companion object {
        private const val TAG = "ImportVpnConfigUseCase"
    }
}
