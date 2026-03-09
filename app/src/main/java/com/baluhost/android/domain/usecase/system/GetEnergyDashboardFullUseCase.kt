package com.baluhost.android.domain.usecase.system

import com.baluhost.android.data.remote.api.EnergyApi
import com.baluhost.android.domain.model.EnergyDashboardFull
import com.baluhost.android.domain.repository.MonitoringRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetEnergyDashboardFullUseCase @Inject constructor(
    private val energyApi: EnergyApi,
    private val monitoringRepository: MonitoringRepository
) {
    suspend operator fun invoke(): Result<EnergyDashboardFull> {
        return try {
            val devices = energyApi.getTapoDevices()
            val device = devices.firstOrNull { it.isActive && it.isMonitoring }
                ?: return Result.Error(Exception("No active power monitoring device found"))

            monitoringRepository.getEnergyDashboardFull(device.id)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
