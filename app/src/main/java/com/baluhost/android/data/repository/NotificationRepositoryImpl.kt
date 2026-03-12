package com.baluhost.android.data.repository

import com.baluhost.android.data.remote.api.NotificationsApi
import com.baluhost.android.data.remote.dto.MarkReadRequest
import com.baluhost.android.data.remote.dto.NotificationListResponse
import com.baluhost.android.data.remote.dto.NotificationPreferencesDto
import com.baluhost.android.data.remote.dto.NotificationPreferencesUpdate
import com.baluhost.android.data.remote.dto.UnreadCountResponse
import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.model.toDomain
import com.baluhost.android.domain.repository.NotificationRepository
import javax.inject.Inject

class NotificationRepositoryImpl @Inject constructor(
    private val notificationsApi: NotificationsApi
) : NotificationRepository {

    override suspend fun getNotifications(
        unreadOnly: Boolean,
        category: String?,
        page: Int,
        pageSize: Int
    ): Result<NotificationListResponse> {
        return try {
            val response = notificationsApi.getNotifications(
                unreadOnly = unreadOnly,
                category = category,
                page = page,
                pageSize = pageSize
            )
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUnreadCount(): Result<UnreadCountResponse> {
        return try {
            Result.success(notificationsApi.getUnreadCount())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markAsRead(id: Int): Result<AppNotification> {
        return try {
            val dto = notificationsApi.markAsRead(id)
            Result.success(dto.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markAllAsRead(category: String?): Result<Int> {
        return try {
            val response = notificationsApi.markAllAsRead(MarkReadRequest(category))
            Result.success(response.count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun dismiss(id: Int): Result<AppNotification> {
        return try {
            val dto = notificationsApi.dismiss(id)
            Result.success(dto.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun snooze(id: Int, hours: Int): Result<AppNotification> {
        return try {
            val dto = notificationsApi.snooze(id, hours)
            Result.success(dto.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getPreferences(): Result<NotificationPreferencesDto> {
        return try {
            Result.success(notificationsApi.getPreferences())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updatePreferences(
        update: NotificationPreferencesUpdate
    ): Result<NotificationPreferencesDto> {
        return try {
            Result.success(notificationsApi.updatePreferences(update))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
