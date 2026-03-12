package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.data.remote.dto.NotificationListResponse
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetNotificationsUseCase @Inject constructor(
    private val repository: NotificationRepository
) {
    suspend operator fun invoke(
        unreadOnly: Boolean = false,
        category: String? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): Result<NotificationListResponse> {
        return try {
            val response = repository.getNotifications(unreadOnly, category, page, pageSize)
            response.fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Error(it as Exception) }
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
