package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class DismissNotificationUseCase @Inject constructor(
    private val repository: NotificationRepository
) {
    suspend operator fun invoke(id: Int): Result<AppNotification> {
        return try {
            repository.dismiss(id).fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Error(it as Exception) }
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
