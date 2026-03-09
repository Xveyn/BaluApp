package com.baluhost.android.domain.usecase.system

import com.baluhost.android.domain.model.MemoryHistory
import com.baluhost.android.domain.repository.MonitoringRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetMemoryHistoryUseCase @Inject constructor(
    private val monitoringRepository: MonitoringRepository
) {
    suspend operator fun invoke(timeRange: String = "1h"): Result<MemoryHistory> {
        return monitoringRepository.getMemoryHistory(timeRange)
    }
}
