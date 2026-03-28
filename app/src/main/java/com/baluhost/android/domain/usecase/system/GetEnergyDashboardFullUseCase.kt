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
    suspend operator fun invoke(deviceId: Int? = null): Result<EnergyDashboardFull> {
        return try {
            val resolvedId = deviceId ?: run {
                val response = energyApi.getSmartDevices()
                response.devices.firstOrNull {
                    it.isActive && "power_monitor" in it.capabilities
                }?.id ?: return Result.Error(Exception("No active power monitoring device found"))
            }

            monitoringRepository.getEnergyDashboardFull(resolvedId)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
