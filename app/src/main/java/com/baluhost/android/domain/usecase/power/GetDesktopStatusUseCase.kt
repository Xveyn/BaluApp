package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.model.DesktopState
import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetDesktopStatusUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): Result<DesktopState> {
        return powerRepository.getDesktopStatus()
    }
}
