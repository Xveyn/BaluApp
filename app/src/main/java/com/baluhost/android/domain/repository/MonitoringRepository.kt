package com.baluhost.android.domain.repository

import com.baluhost.android.domain.model.CpuHistory
import com.baluhost.android.domain.model.CurrentUptime
import com.baluhost.android.domain.model.EnergyDashboardFull
import com.baluhost.android.domain.model.MemoryHistory
import com.baluhost.android.domain.model.UptimeHistory
import com.baluhost.android.util.Result

interface MonitoringRepository {
    suspend fun getCpuHistory(timeRange: String = "1h"): Result<CpuHistory>
    suspend fun getMemoryHistory(timeRange: String = "1h"): Result<MemoryHistory>
    suspend fun getEnergyDashboardFull(deviceId: Int): Result<EnergyDashboardFull>
    suspend fun getCurrentUptime(): Result<CurrentUptime>
    suspend fun getUptimeHistory(timeRange: String = "1h"): Result<UptimeHistory>
}
