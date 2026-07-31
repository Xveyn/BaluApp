package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.model.AlwaysAwake
import com.baluhost.android.domain.repository.SleepConfigRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetAlwaysAwakeUseCase @Inject constructor(
    private val sleepConfigRepository: SleepConfigRepository
) {
    suspend operator fun invoke(): Result<AlwaysAwake> {
        return sleepConfigRepository.getAlwaysAwake()
    }
}
