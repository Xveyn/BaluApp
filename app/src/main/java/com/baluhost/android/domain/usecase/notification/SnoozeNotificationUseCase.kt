package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class SnoozeNotificationUseCase @Inject constructor(
    private val repository: NotificationRepository
) {
    suspend operator fun invoke(id: Int, hours: Int): Result<AppNotification> {
        return try {
            repository.snooze(id, hours).fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Error(it as Exception) }
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
