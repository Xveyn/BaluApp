package com.baluhost.android.data.repository

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.network.FritzBoxTR064Client
import com.baluhost.android.data.network.WolResult
import com.baluhost.android.domain.model.DesktopActionResult
import com.baluhost.android.domain.model.DesktopState
import com.baluhost.android.domain.model.NasStatus
import com.baluhost.android.domain.model.NasStatusResult
import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.domain.model.PowerPermissions
import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.io.IOException
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
            // The server carries out the suspend and is gone before it can reply,
            // so this call would otherwise sit on OkHttp's 120s read timeout —
            // long enough that everything the caller does afterwards happens two
            // minutes too late to be useful. The server acts within a few hundred
            // milliseconds, so silence past this window already means it did.
            val response = withTimeoutOrNull(SUSPEND_ACK_TIMEOUT_MS) { sleepApi.sendSuspend() }
                ?: return Result.Success("System wird suspendiert")
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            // The server answered, and said no — e.g. 403 without the permission.
            Result.Error(Exception("Suspend fehlgeschlagen: ${e.message()}", e))
        } catch (e: IOException) {
            // No HTTP answer at all. For a suspend that is the *expected* outcome:
            // the server carries out the request and stops answering before it can
            // reply, so the POST dies on the wire. Reporting this as a failure made
            // the caller skip its whole post-suspend handling, which is why the NAS
            // kept showing as awake. The dialog only offers Suspend while the server
            // reads as online, so "was never reachable" is already largely excluded.
            Result.Success("System wird suspendiert")
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun getMyPermissions(): Result<PowerPermissions> {
        return try {
            val dto = sleepApi.getMyPermissions()
            Result.Success(PowerPermissions(
                canSoftSleep = dto.canSoftSleep,
                canWake = dto.canWake,
                canSuspend = dto.canSuspend,
                canWol = dto.canWol,
                canToggleDesktop = dto.canToggleDesktop,
                canUnlockSession = dto.canUnlockSession
            ))
        } catch (e: HttpException) {
            Result.Error(Exception("Berechtigungen konnten nicht geladen werden: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun sendWake(): Result<String> {
        return try {
            val response = sleepApi.sendWake()
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.Error(Exception("Wake fehlgeschlagen: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun getDesktopStatus(): Result<DesktopState> {
        return try {
            Result.Success(DesktopState.fromApi(sleepApi.getDesktopStatus().state))
        } catch (e: HttpException) {
            Result.Error(Exception("Desktop-Status nicht abrufbar: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun enableDesktop(): Result<DesktopActionResult> {
        return try {
            val response = sleepApi.enableDesktop()
            if (response.success) {
                Result.Success(DesktopActionResult(response.message, response.sessionUnlocked))
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.Error(Exception("Displays einschalten fehlgeschlagen: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun disableDesktop(): Result<String> {
        return try {
            val response = sleepApi.disableDesktop()
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.Error(Exception("Displays ausschalten fehlgeschlagen: ${e.message()}", e))
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
                // Deliberately NOT ONLINE. This runs only after a telemetry call has
                // already failed, so the server is demonstrably unreachable — and the
                // Fritz!Box's NewActive flag hangs on ARP and lease timeouts, not on
                // whether the machine is awake. Measured on a real suspend, it went on
                // reporting the host as active for more than two minutes. Trusting it
                // here made the app contradict the evidence it had just collected.
                is WolResult.Success -> NasStatusResult.Resolved(NasStatus.SLEEPING)
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

    private companion object {
        /**
         * How long to wait for the server to acknowledge a suspend before taking
         * its silence as confirmation. Deliberately far below the client's 120s
         * read timeout — a suspending server never answers, and the caller needs
         * to know now, not in two minutes.
         */
        const val SUSPEND_ACK_TIMEOUT_MS = 5_000L
    }
}
