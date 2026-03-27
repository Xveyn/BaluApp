package com.baluhost.android.data.repository

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.network.FritzBoxTR064Client
import com.baluhost.android.data.network.WolResult
import com.baluhost.android.domain.model.NasStatus
import com.baluhost.android.domain.model.NasStatusResult
import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject

class PowerRepositoryImpl @Inject constructor(
    private val sleepApi: SleepApi,
    private val fritzBoxClient: FritzBoxTR064Client,
    private val preferencesManager: PreferencesManager
) : PowerRepository {

    override suspend fun sendWol(): Result<String> {
        return try {
            val host = preferencesManager.getFritzBoxHost().first()
            val port = preferencesManager.getFritzBoxPort().first()
            val username = preferencesManager.getFritzBoxUsername().first()
            val password = preferencesManager.getFritzBoxPassword() ?: ""
            val mac = preferencesManager.getFritzBoxMacAddress().first()

            if (mac.isEmpty()) {
                return Result.Error(Exception("Fritz!Box nicht konfiguriert — MAC-Adresse fehlt"))
            }

            when (val result = fritzBoxClient.sendWol(host, port, username, password, mac)) {
                is WolResult.Success -> Result.Success("WoL-Signal gesendet")
                is WolResult.AuthError -> Result.Error(Exception("Fritz!Box Zugangsdaten ungültig"))
                is WolResult.Unreachable -> Result.Error(Exception("Fritz!Box nicht erreichbar. VPN aktiv?"))
                is WolResult.Error -> Result.Error(Exception(result.message))
            }
        } catch (e: Exception) {
            Result.Error(Exception("WoL fehlgeschlagen: ${e.message}", e))
        }
    }

    override suspend fun sendSoftSleep(): Result<String> {
        return try {
            val response = sleepApi.sendSoftSleep()
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.Error(Exception("Sleep fehlgeschlagen: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun sendSuspend(): Result<String> {
        return try {
            val response = sleepApi.sendSuspend()
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.Error(Exception("Suspend fehlgeschlagen: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun checkNasStatus(): NasStatusResult {
        return try {
            val mac = preferencesManager.getFritzBoxMacAddress().first()
            if (mac.isEmpty()) return NasStatusResult.FritzBoxNotConfigured

            val host = preferencesManager.getFritzBoxHost().first()
            val port = preferencesManager.getFritzBoxPort().first()
            val username = preferencesManager.getFritzBoxUsername().first()
            val password = preferencesManager.getFritzBoxPassword() ?: ""

            when (val result = fritzBoxClient.checkHostActive(host, port, username, password, mac)) {
                is WolResult.Success -> NasStatusResult.Resolved(NasStatus.ONLINE)
                is WolResult.Error -> {
                    if (result.message == "inactive") {
                        NasStatusResult.Resolved(NasStatus.SLEEPING)
                    } else {
                        NasStatusResult.Resolved(NasStatus.OFFLINE)
                    }
                }
                is WolResult.AuthError -> NasStatusResult.FritzBoxAuthError
                is WolResult.Unreachable -> NasStatusResult.FritzBoxUnreachable
            }
        } catch (e: Exception) {
            NasStatusResult.FritzBoxUnreachable
        }
    }
}
