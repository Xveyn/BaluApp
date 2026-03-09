package com.baluhost.android.data.repository

import com.baluhost.android.data.remote.api.EnergyApi
import com.baluhost.android.data.remote.api.MonitoringApi
import com.baluhost.android.data.remote.dto.CpuSampleDto
import com.baluhost.android.data.remote.dto.HourlySampleDto
import com.baluhost.android.data.remote.dto.MemorySampleDto
import com.baluhost.android.domain.model.CpuHistory
import com.baluhost.android.domain.model.CpuSample
import com.baluhost.android.domain.model.EnergyDashboardFull
import com.baluhost.android.domain.model.MemoryHistory
import com.baluhost.android.domain.model.MemorySample
import com.baluhost.android.domain.model.PowerHourlySample
import com.baluhost.android.domain.repository.MonitoringRepository
import com.baluhost.android.util.Result
import java.time.Instant
import javax.inject.Inject

class MonitoringRepositoryImpl @Inject constructor(
    private val monitoringApi: MonitoringApi,
    private val energyApi: EnergyApi
) : MonitoringRepository {

    override suspend fun getCpuHistory(timeRange: String): Result<CpuHistory> {
        return try {
            val dto = monitoringApi.getCpuHistory(timeRange)
            Result.Success(
                CpuHistory(
                    samples = dto.samples.map { it.toDomain() },
                    sampleCount = dto.sampleCount
                )
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun getMemoryHistory(timeRange: String): Result<MemoryHistory> {
        return try {
            val dto = monitoringApi.getMemoryHistory(timeRange)
            Result.Success(
                MemoryHistory(
                    samples = dto.samples.map { it.toDomain() },
                    sampleCount = dto.sampleCount
                )
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun getEnergyDashboardFull(deviceId: Int): Result<EnergyDashboardFull> {
        return try {
            val dto = energyApi.getEnergyDashboard(deviceId)
            Result.Success(
                EnergyDashboardFull(
                    deviceName = dto.deviceName,
                    currentWatts = dto.currentWatts,
                    isOnline = dto.isOnline,
                    todayKwh = dto.today?.totalEnergyKwh ?: 0.0,
                    todayAvgWatts = dto.today?.avgWatts ?: 0.0,
                    todayMinWatts = dto.today?.minWatts ?: 0.0,
                    todayMaxWatts = dto.today?.maxWatts ?: 0.0,
                    monthKwh = dto.month?.totalEnergyKwh ?: 0.0,
                    hourlySamples = dto.hourlySamples.map { it.toDomain() }
                )
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}

private fun CpuSampleDto.toDomain() = CpuSample(
    timestamp = parseIsoTimestamp(timestamp),
    usagePercent = usagePercent,
    frequencyMhz = frequencyMhz,
    temperatureCelsius = temperatureCelsius
)

private fun MemorySampleDto.toDomain() = MemorySample(
    timestamp = parseIsoTimestamp(timestamp),
    usedBytes = usedBytes,
    totalBytes = totalBytes,
    percent = percent
)

private fun HourlySampleDto.toDomain() = PowerHourlySample(
    timestamp = parseIsoTimestamp(timestamp),
    avgWatts = avgWatts
)

private fun parseIsoTimestamp(iso: String): Long {
    return try {
        Instant.parse(iso).epochSecond
    } catch (_: Exception) {
        try {
            // Handle timestamps without timezone (append Z)
            Instant.parse("${iso}Z").epochSecond
        } catch (_: Exception) {
            0L
        }
    }
}
