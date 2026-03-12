package com.baluhost.android.domain.repository

import com.baluhost.android.data.remote.dto.MarkReadRequest
import com.baluhost.android.data.remote.dto.NotificationListResponse
import com.baluhost.android.data.remote.dto.NotificationPreferencesDto
import com.baluhost.android.data.remote.dto.NotificationPreferencesUpdate
import com.baluhost.android.data.remote.dto.UnreadCountResponse
import com.baluhost.android.domain.model.AppNotification

interface NotificationRepository {

    suspend fun getNotifications(
        unreadOnly: Boolean = false,
        category: String? = null,
        page: Int = 1,
        pageSize: Int = 50
    ): Result<NotificationListResponse>

    suspend fun getUnreadCount(): Result<UnreadCountResponse>

    suspend fun markAsRead(id: Int): Result<AppNotification>

    suspend fun markAllAsRead(category: String? = null): Result<Int>

    suspend fun dismiss(id: Int): Result<AppNotification>

    suspend fun snooze(id: Int, hours: Int): Result<AppNotification>

    suspend fun getPreferences(): Result<NotificationPreferencesDto>

    suspend fun updatePreferences(update: NotificationPreferencesUpdate): Result<NotificationPreferencesDto>
}
