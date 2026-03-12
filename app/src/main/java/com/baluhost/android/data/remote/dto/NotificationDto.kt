package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

data class NotificationDto(
    val id: Int,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("user_id")
    val userId: Int?,
    @SerializedName("notification_type")
    val notificationType: String,
    val category: String,
    val title: String,
    val message: String,
    @SerializedName("action_url")
    val actionUrl: String?,
    @SerializedName("is_read")
    val isRead: Boolean,
    @SerializedName("is_dismissed")
    val isDismissed: Boolean,
    val priority: Int,
    val metadata: Map<String, Any>?,
    @SerializedName("time_ago")
    val timeAgo: String?,
    @SerializedName("snoozed_until")
    val snoozedUntil: String?
)

data class NotificationListResponse(
    val notifications: List<NotificationDto>,
    val total: Int,
    @SerializedName("unread_count")
    val unreadCount: Int,
    val page: Int,
    @SerializedName("page_size")
    val pageSize: Int
)

data class UnreadCountResponse(
    val count: Int,
    @SerializedName("by_category")
    val byCategory: Map<String, Int>?
)

data class MarkReadResponse(
    val success: Boolean,
    val count: Int
)

data class NotificationPreferencesDto(
    val id: Int,
    @SerializedName("user_id")
    val userId: Int,
    @SerializedName("email_enabled")
    val emailEnabled: Boolean,
    @SerializedName("push_enabled")
    val pushEnabled: Boolean,
    @SerializedName("in_app_enabled")
    val inAppEnabled: Boolean,
    @SerializedName("quiet_hours_enabled")
    val quietHoursEnabled: Boolean,
    @SerializedName("quiet_hours_start")
    val quietHoursStart: String?,
    @SerializedName("quiet_hours_end")
    val quietHoursEnd: String?,
    @SerializedName("min_priority")
    val minPriority: Int,
    @SerializedName("category_preferences")
    val categoryPreferences: Map<String, CategoryPreference>?
)

data class CategoryPreference(
    val email: Boolean,
    val push: Boolean,
    @SerializedName("in_app")
    val inApp: Boolean
)

data class NotificationPreferencesUpdate(
    @SerializedName("email_enabled")
    val emailEnabled: Boolean? = null,
    @SerializedName("push_enabled")
    val pushEnabled: Boolean? = null,
    @SerializedName("in_app_enabled")
    val inAppEnabled: Boolean? = null,
    @SerializedName("quiet_hours_enabled")
    val quietHoursEnabled: Boolean? = null,
    @SerializedName("quiet_hours_start")
    val quietHoursStart: String? = null,
    @SerializedName("quiet_hours_end")
    val quietHoursEnd: String? = null,
    @SerializedName("min_priority")
    val minPriority: Int? = null,
    @SerializedName("category_preferences")
    val categoryPreferences: Map<String, CategoryPreference>? = null
)

data class WsTokenResponse(
    val token: String
)

data class MarkReadRequest(
    val category: String? = null
)
