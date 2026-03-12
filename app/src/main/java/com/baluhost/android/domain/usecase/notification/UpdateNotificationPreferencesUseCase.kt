package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.data.remote.dto.NotificationPreferencesDto
import com.baluhost.android.data.remote.dto.NotificationPreferencesUpdate
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class UpdateNotificationPreferencesUseCase @Inject constructor(
    private val repository: NotificationRepository
) {
    suspend operator fun invoke(update: NotificationPreferencesUpdate): Result<NotificationPreferencesDto> {
        return try {
            repository.updatePreferences(update).fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Error(it as Exception) }
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
