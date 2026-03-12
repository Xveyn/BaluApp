package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.data.remote.dto.UnreadCountResponse
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetUnreadCountUseCase @Inject constructor(
    private val repository: NotificationRepository
) {
    suspend operator fun invoke(): Result<UnreadCountResponse> {
        return try {
            repository.getUnreadCount().fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Error(it as Exception) }
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
