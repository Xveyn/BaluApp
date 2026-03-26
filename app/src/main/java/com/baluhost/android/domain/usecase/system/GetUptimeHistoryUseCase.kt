package com.baluhost.android.domain.usecase.system

import com.baluhost.android.domain.model.UptimeHistory
import com.baluhost.android.domain.repository.MonitoringRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetUptimeHistoryUseCase @Inject constructor(
    private val monitoringRepository: MonitoringRepository
) {
    suspend operator fun invoke(timeRange: String = "1h"): Result<UptimeHistory> {
        return monitoringRepository.getUptimeHistory(timeRange)
    }
}
