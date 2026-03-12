package com.baluhost.android.domain.model

import com.baluhost.android.data.remote.dto.NotificationDto

enum class NotificationType { INFO, WARNING, CRITICAL }

enum class NotificationCategory { RAID, SMART, BACKUP, SCHEDULER, SYSTEM, SECURITY, SYNC, VPN }

data class AppNotification(
    val id: Int,
    val createdAt: String,
    val userId: Int?,
    val type: NotificationType,
    val category: NotificationCategory,
    val title: String,
    val message: String,
    val actionUrl: String?,
    val isRead: Boolean,
    val isDismissed: Boolean,
    val priority: Int,
    val metadata: Map<String, Any>?,
    val timeAgo: String?,
    val snoozedUntil: String?
)

fun NotificationDto.toDomain() = AppNotification(
    id = id,
    createdAt = createdAt,
    userId = userId,
    type = NotificationType.entries.find {
        it.name.equals(notificationType, ignoreCase = true)
    } ?: NotificationType.INFO,
    category = NotificationCategory.entries.find {
        it.name.equals(category, ignoreCase = true)
    } ?: NotificationCategory.SYSTEM,
    title = title,
    message = message,
    actionUrl = actionUrl,
    isRead = isRead,
    isDismissed = isDismissed,
    priority = priority,
    metadata = metadata,
    timeAgo = timeAgo,
    snoozedUntil = snoozedUntil
)
