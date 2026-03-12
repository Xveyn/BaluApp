package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class MarkAllReadUseCase @Inject constructor(
    private val repository: NotificationRepository
) {
    suspend operator fun invoke(category: String? = null): Result<Int> {
        return try {
            repository.markAllAsRead(category).fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Error(it as Exception) }
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
