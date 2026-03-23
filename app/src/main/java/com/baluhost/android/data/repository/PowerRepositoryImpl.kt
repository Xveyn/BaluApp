package com.baluhost.android.data.repository

import com.baluhost.android.data.remote.api.FritzBoxApi
import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import retrofit2.HttpException
import javax.inject.Inject

class PowerRepositoryImpl @Inject constructor(
    private val fritzBoxApi: FritzBoxApi,
    private val sleepApi: SleepApi
) : PowerRepository {

    override suspend fun sendWol(): Result<String> {
        return try {
            val response = fritzBoxApi.sendWol()
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            val msg = when (e.code()) {
                400 -> "Fritz!Box nicht konfiguriert"
                503 -> "Fritz!Box nicht erreichbar"
                else -> "WoL fehlgeschlagen: ${e.message()}"
            }
            Result.Error(Exception(msg, e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
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
}
