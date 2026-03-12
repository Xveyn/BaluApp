package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.*
import retrofit2.http.*

interface NotificationsApi {

    @GET("notifications")
    suspend fun getNotifications(
        @Query("unread_only") unreadOnly: Boolean = false,
        @Query("include_dismissed") includeDismissed: Boolean = false,
        @Query("category") category: String? = null,
        @Query("notification_type") notificationType: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50
    ): NotificationListResponse

    @GET("notifications/unread-count")
    suspend fun getUnreadCount(): UnreadCountResponse

    @POST("notifications/{id}/read")
    suspend fun markAsRead(@Path("id") id: Int): NotificationDto

    @POST("notifications/read-all")
    suspend fun markAllAsRead(@Body request: MarkReadRequest = MarkReadRequest()): MarkReadResponse

    @POST("notifications/{id}/dismiss")
    suspend fun dismiss(@Path("id") id: Int): NotificationDto

    @POST("notifications/{id}/snooze")
    suspend fun snooze(
        @Path("id") id: Int,
        @Query("duration_hours") durationHours: Int
    ): NotificationDto

    @GET("notifications/preferences")
    suspend fun getPreferences(): NotificationPreferencesDto

    @PUT("notifications/preferences")
    suspend fun updatePreferences(@Body update: NotificationPreferencesUpdate): NotificationPreferencesDto

    @POST("notifications/ws-token")
    suspend fun getWsToken(): WsTokenResponse
}
