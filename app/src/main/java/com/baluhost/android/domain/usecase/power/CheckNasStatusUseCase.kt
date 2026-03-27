package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.model.NasStatusResult
import com.baluhost.android.domain.repository.PowerRepository
import javax.inject.Inject

class CheckNasStatusUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): NasStatusResult = powerRepository.checkNasStatus()
}
