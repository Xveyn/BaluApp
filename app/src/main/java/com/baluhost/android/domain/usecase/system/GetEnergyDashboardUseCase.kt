package com.baluhost.android.domain.usecase.system

import com.baluhost.android.data.remote.api.EnergyApi
import com.baluhost.android.domain.model.EnergyDashboard
import com.baluhost.android.util.Result
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

/**
 * UseCase to get energy dashboard data from the Tapo power monitor.
 * Discovers the first active monitoring device and fetches current power + stats.
 */
class GetEnergyDashboardUseCase @Inject constructor(
    private val energyApi: EnergyApi
) {
    suspend operator fun invoke(): Result<EnergyDashboard> {
        return try {
            // Find the first active monitoring Tapo device
            val devices = energyApi.getTapoDevices()
            val device = devices.firstOrNull { it.isActive && it.isMonitoring }
                ?: return Result.Error(Exception("No active power monitoring device found"))

            // Fetch current power and stats in parallel
            val dashboard = coroutineScope {
                val currentPowerDeferred = async { energyApi.getCurrentPower(device.id) }
                val todayStatsDeferred = async {
                    try { energyApi.getTodayStats(device.id) } catch (_: Exception) { null }
                }
                val monthStatsDeferred = async {
                    try { energyApi.getMonthStats(device.id) } catch (_: Exception) { null }
                }

                val currentPower = currentPowerDeferred.await()
                val todayStats = todayStatsDeferred.await()
                val monthStats = monthStatsDeferred.await()

                EnergyDashboard(
                    deviceName = currentPower.deviceName,
                    currentWatts = currentPower.currentWatts,
                    isOnline = currentPower.isOnline,
                    todayKwh = todayStats?.totalEnergyKwh ?: 0.0,
                    todayAvgWatts = todayStats?.avgWatts ?: 0.0,
                    todayMinWatts = todayStats?.minWatts ?: 0.0,
                    todayMaxWatts = todayStats?.maxWatts ?: 0.0,
                    monthKwh = monthStats?.totalEnergyKwh ?: 0.0
                )
            }

            Result.Success(dashboard)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
