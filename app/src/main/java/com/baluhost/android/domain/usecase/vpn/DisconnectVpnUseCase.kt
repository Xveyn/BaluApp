package com.baluhost.android.domain.usecase.vpn

import android.util.Log
import com.baluhost.android.service.vpn.VpnConnectionManager
import com.baluhost.android.util.Result
import javax.inject.Inject

/**
 * Use case for disconnecting from VPN.
 */
class DisconnectVpnUseCase @Inject constructor(
    private val vpnConnectionManager: VpnConnectionManager
) {

    operator fun invoke(): Result<Boolean> {
        return try {
            Log.d(TAG, "Disconnecting VPN")
            vpnConnectionManager.disconnect()
            Log.d(TAG, "VPN disconnected")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "VPN disconnect failed", e)
            Result.Error(Exception("VPN trennen fehlgeschlagen: ${e.message}", e))
        }
    }

    companion object {
        private const val TAG = "DisconnectVpnUseCase"
    }
}
