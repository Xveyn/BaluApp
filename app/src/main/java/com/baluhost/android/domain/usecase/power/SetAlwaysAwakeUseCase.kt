package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.model.AlwaysAwake
import com.baluhost.android.domain.repository.SleepConfigRepository
import com.baluhost.android.util.Result
import java.time.Instant
import javax.inject.Inject

class SetAlwaysAwakeUseCase @Inject constructor(
    private val sleepConfigRepository: SleepConfigRepository
) {
    /** @param until null means permanent; ignored when [enabled] is false. */
    suspend operator fun invoke(enabled: Boolean, until: Instant?): Result<AlwaysAwake> {
        return sleepConfigRepository.setAlwaysAwake(enabled, until)
    }
}
