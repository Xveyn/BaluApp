package com.baluhost.android.domain.repository

import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.remote.dto.MarkReadRequest
import com.baluhost.android.data.remote.dto.NotificationListResponse
import com.baluhost.android.data.remote.dto.NotificationPreferencesDto
import com.baluhost.android.data.remote.dto.NotificationPreferencesUpdate
import com.baluhost.android.data.remote.dto.UnreadCountResponse
import com.baluhost.android.domain.model.AppNotification
import kotlinx.coroutines.flow.Flow

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

    // --- Cache-first API (local Room cache, reconciled with the server by sync()) ---

    /** Cache reads, scoped to the signed-in account. Active notifications when [trashed] is false. */
    fun observeNotifications(ownerUserId: Int, trashed: Boolean): Flow<List<AppNotification>>

    fun observeUnreadCount(ownerUserId: Int): Flow<Int>

    /**
     * Two-way reconciliation with the server: fetches the current active and
     * trashed lists, merges them with the local cache via [com.baluhost.android.domain.service.NotificationMerge],
     * pushes any pending local intent, and writes the result back to the cache.
     * On a failing server call, the cache is left untouched and the failure is
     * returned as-is.
     */
    suspend fun sync(ownerUserId: Int): Result<Unit>

    /** Records the read intent locally; does not call the server. */
    suspend fun markReadLocally(ownerUserId: Int, id: Int)

    /** Records the trash intent locally; does not call the server. */
    suspend fun dismissLocally(ownerUserId: Int, id: Int)

    /** Records the restore-from-trash intent locally; does not call the server. */
    suspend fun restoreLocally(ownerUserId: Int, id: Int)

    /** Records the snooze intent locally; does not call the server. */
    suspend fun snoozeLocally(ownerUserId: Int, id: Int, hours: Int)

    /** Deletes on the server first, then locally. A failed server call leaves the row untouched. */
    suspend fun deletePermanently(ownerUserId: Int, id: Int): Result<Unit>

    /** Empties the trash on the server first, then locally. A failed server call leaves the cache untouched. */
    suspend fun emptyTrash(ownerUserId: Int): Result<Unit>

    /** Writes a row pushed in by FCM/WebSocket directly into the cache. */
    suspend fun upsertFromPush(entity: NotificationEntity)

    /** Wipes the entire local notification cache, for all accounts. */
    suspend fun clearAll()
}
