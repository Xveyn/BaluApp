package com.baluhost.android.domain.usecase.vpn

import android.util.Log
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.service.vpn.VpnConnectionManager
import com.baluhost.android.service.vpn.VpnNotAuthorizedException
import com.baluhost.android.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Use case for connecting to VPN.
 *
 * Uses GoBackend via VpnConnectionManager for a stable, non-reflection-based
 * WireGuard tunnel setup.
 */
class ConnectVpnUseCase @Inject constructor(
    private val vpnConnectionManager: VpnConnectionManager,
    private val preferencesManager: PreferencesManager
) {

    suspend operator fun invoke(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val vpnConfig = preferencesManager.getVpnConfig().first()
                ?: return@withContext Result.Error(Exception("Keine VPN-Konfiguration gefunden"))

            Log.d(TAG, "Connecting VPN via GoBackend")
            vpnConnectionManager.connect(vpnConfig)
            Log.d(TAG, "VPN tunnel established")

            Result.Success(true)
        } catch (e: VpnNotAuthorizedException) {
            Log.e(TAG, "VPN not authorized", e)
            Result.Error(e)
        } catch (e: Exception) {
            Log.e(TAG, "VPN connection failed", e)
            Result.Error(Exception("VPN-Verbindung fehlgeschlagen: ${e.message}", e))
        }
    }

    companion object {
        private const val TAG = "ConnectVpnUseCase"
    }
}
