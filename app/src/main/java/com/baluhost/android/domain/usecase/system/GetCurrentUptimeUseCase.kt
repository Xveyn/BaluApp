package com.baluhost.android.domain.usecase.system

import com.baluhost.android.domain.model.CurrentUptime
import com.baluhost.android.domain.repository.MonitoringRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetCurrentUptimeUseCase @Inject constructor(
    private val monitoringRepository: MonitoringRepository
) {
    suspend operator fun invoke(): Result<CurrentUptime> {
        return monitoringRepository.getCurrentUptime()
    }
}
