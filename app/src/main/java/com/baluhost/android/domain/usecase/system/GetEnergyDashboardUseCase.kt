package com.baluhost.android.domain.usecase.system

import com.baluhost.android.data.remote.api.EnergyApi
import com.baluhost.android.data.remote.api.PluginApi
import com.baluhost.android.domain.model.EnergyDashboard
import com.baluhost.android.util.Result
import javax.inject.Inject

/**
 * UseCase to get energy dashboard data for the Dashboard panel.
 * Reads panel_devices from server plugin config. Aggregates if multiple devices selected.
 */
class GetEnergyDashboardUseCase @Inject constructor(
    private val energyApi: EnergyApi,
    private val pluginApi: PluginApi
) {
    suspend operator fun invoke(): Result<EnergyDashboard> {
        return try {
            // Load panel_devices from server config
            val panelDeviceIds = try {
                pluginApi.getTapoPluginConfig().config.panelDevices
            } catch (_: Exception) {
                emptyList()
            }

            // Load device list for name lookup and fallback
            val allDevices = energyApi.getSmartDevices().devices

            // Determine which device IDs to use
            val deviceIds = if (panelDeviceIds.isNotEmpty()) {
                // Filter to only active devices that still exist
                val activeIds = allDevices.filter { it.isActive }.map { it.id }.toSet()
                panelDeviceIds.filter { it in activeIds }
            } else {
                // Fallback: first active power_monitor device
                val device = allDevices.firstOrNull {
                    it.isActive && "power_monitor" in it.capabilities
                }
                if (device != null) listOf(device.id) else emptyList()
            }

            if (deviceIds.isEmpty()) {
                return Result.Error(Exception("No active power monitoring device found"))
            }

            // Load dashboards for all selected devices
            val dashboards = deviceIds.mapNotNull { id ->
                try {
                    energyApi.getEnergyDashboard(id)
                } catch (_: Exception) {
                    null
                }
            }

            if (dashboards.isEmpty()) {
                return Result.Error(Exception("Failed to load energy data"))
            }

            // Single device - return directly
            if (dashboards.size == 1) {
                val d = dashboards.first()
                return Result.Success(
                    EnergyDashboard(
                        deviceName = d.deviceName,
                        currentWatts = d.currentWatts,
                        isOnline = d.isOnline,
                        todayKwh = d.today?.totalEnergyKwh ?: 0.0,
                        todayAvgWatts = d.today?.avgWatts ?: 0.0,
                        todayMinWatts = d.today?.minWatts ?: 0.0,
                        todayMaxWatts = d.today?.maxWatts ?: 0.0,
                        monthKwh = d.month?.totalEnergyKwh ?: 0.0,
                        deviceCount = 1
                    )
                )
            }

            // Multiple devices - aggregate
            Result.Success(
                EnergyDashboard(
                    deviceName = "${dashboards.size} Geräte",
                    currentWatts = dashboards.sumOf { it.currentWatts },
                    isOnline = dashboards.any { it.isOnline },
                    todayKwh = dashboards.sumOf { it.today?.totalEnergyKwh ?: 0.0 },
                    todayAvgWatts = dashboards.sumOf { it.today?.avgWatts ?: 0.0 },
                    todayMinWatts = dashboards.mapNotNull { it.today?.minWatts }.minOrNull() ?: 0.0,
                    todayMaxWatts = dashboards.mapNotNull { it.today?.maxWatts }.maxOrNull() ?: 0.0,
                    monthKwh = dashboards.sumOf { it.month?.totalEnergyKwh ?: 0.0 },
                    deviceCount = dashboards.size
                )
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
