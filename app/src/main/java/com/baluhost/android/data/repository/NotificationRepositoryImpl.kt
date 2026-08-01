package com.baluhost.android.data.repository

import com.baluhost.android.data.local.database.dao.NotificationDao
import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.remote.api.NotificationsApi
import com.baluhost.android.data.remote.dto.MarkReadRequest
import com.baluhost.android.data.remote.dto.NotificationListResponse
import com.baluhost.android.data.remote.dto.NotificationPreferencesDto
import com.baluhost.android.data.remote.dto.NotificationPreferencesUpdate
import com.baluhost.android.data.remote.dto.UnreadCountResponse
import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.model.toDomain
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.domain.service.NotificationMerge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

class NotificationRepositoryImpl @Inject constructor(
    private val notificationsApi: NotificationsApi,
    private val notificationDao: NotificationDao
) : NotificationRepository {

    companion object {
        /** Per-account cap on cached rows; see NotificationDao.getEvictable for the exemption rule. */
        private const val CACHE_LIMIT = 500
    }

    private val emptyLocalState = NotificationMerge.LocalState(
        isRead = false,
        deletedAt = null,
        snoozedUntil = null,
        localReadAt = null,
        localTrashedAt = null,
        localRestoredAt = null,
        localSnoozedUntil = null
    )

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

    // --- Cache-first API ---

    override fun observeNotifications(ownerUserId: Int, trashed: Boolean): Flow<List<AppNotification>> =
        notificationDao.observe(ownerUserId, trashed).map { rows -> rows.map(NotificationEntity::toDomain) }

    override fun observeUnreadCount(ownerUserId: Int): Flow<Int> =
        notificationDao.observeUnreadCount(ownerUserId)

    override suspend fun sync(ownerUserId: Int): Result<Unit> {
        val activeResponse: NotificationListResponse
        val trashResponse: NotificationListResponse
        try {
            activeResponse = notificationsApi.getNotifications(page = 1, pageSize = 50)
            trashResponse = notificationsApi.getTrash(page = 1, pageSize = 50)
        } catch (e: Exception) {
            // Half-applying a sync is worse than not syncing: bail out before the cache is touched.
            return Result.failure(e)
        }

        val serverDtos = activeResponse.notifications + trashResponse.notifications
        val localRowsById = notificationDao.getAll(ownerUserId).associateBy { it.id }
        val now = Instant.now()

        val mergedRows = serverDtos.map { dto ->
            val serverEntity = dto.toEntity(ownerUserId, source = "REST")
            val localState = localRowsById[dto.id]?.toLocalState() ?: emptyLocalState
            val serverState = NotificationMerge.ServerState(
                isRead = serverEntity.isRead,
                deletedAt = serverEntity.deletedAt,
                snoozedUntil = serverEntity.snoozedUntil
            )
            val merged = NotificationMerge.merge(localState, serverState, now)

            // Push every pending local decision. A push failing here does not fail the
            // sync as a whole: the merged state below still carries the intent, so an
            // unconfirmed push is simply retried on the next sync.
            for (push in merged.pushes) {
                runCatching {
                    when (push) {
                        is NotificationMerge.Push.MarkRead -> notificationsApi.markAsRead(dto.id)
                        is NotificationMerge.Push.Dismiss -> notificationsApi.dismiss(dto.id)
                        is NotificationMerge.Push.Restore -> notificationsApi.restore(dto.id)
                        is NotificationMerge.Push.Snooze -> notificationsApi.snooze(dto.id, push.hours)
                    }
                }
            }

            serverEntity.applyMerged(merged.state)
        }

        // A local row with no server counterpart and no pending intent means the server
        // deleted it for good; a full reconcile is the only way this device can notice.
        val serverIds = serverDtos.mapTo(mutableSetOf()) { it.id }
        val toDelete = localRowsById.values
            .filter { it.id !in serverIds && !it.hasPendingIntent }
            .map { it.id }
        if (toDelete.isNotEmpty()) {
            notificationDao.deleteAllById(ownerUserId, toDelete)
        }

        notificationDao.upsertAll(mergedRows)
        evictOverflow(ownerUserId)

        return Result.success(Unit)
    }

    /** Enforces the per-account cache cap, evicting the oldest exempt-free rows first. */
    suspend fun evictOverflow(ownerUserId: Int) {
        val overflow = notificationDao.count(ownerUserId) - CACHE_LIMIT
        if (overflow <= 0) return

        val evictable = notificationDao.getEvictable(ownerUserId)
        val toEvict = evictable.take(overflow).map { it.id }
        if (toEvict.isNotEmpty()) {
            notificationDao.deleteAllById(ownerUserId, toEvict)
        }
    }

    override suspend fun markReadLocally(ownerUserId: Int, id: Int) {
        val row = notificationDao.find(ownerUserId, id) ?: return
        val now = Instant.now()
        notificationDao.update(row.copy(isRead = true, localReadAt = now))
    }

    override suspend fun dismissLocally(ownerUserId: Int, id: Int) {
        val row = notificationDao.find(ownerUserId, id) ?: return
        val now = Instant.now()
        notificationDao.update(
            row.copy(deletedAt = now, localTrashedAt = now, localRestoredAt = null)
        )
    }

    override suspend fun restoreLocally(ownerUserId: Int, id: Int) {
        val row = notificationDao.find(ownerUserId, id) ?: return
        val now = Instant.now()
        notificationDao.update(
            row.copy(deletedAt = null, localRestoredAt = now, localTrashedAt = null)
        )
    }

    override suspend fun snoozeLocally(ownerUserId: Int, id: Int, hours: Int) {
        val row = notificationDao.find(ownerUserId, id) ?: return
        val until = Instant.now().plus(Duration.ofHours(hours.toLong()))
        notificationDao.update(row.copy(snoozedUntil = until, localSnoozedUntil = until))
    }

    override suspend fun deletePermanently(ownerUserId: Int, id: Int): Result<Unit> {
        return try {
            notificationsApi.deletePermanently(id)
            notificationDao.delete(ownerUserId, id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun emptyTrash(ownerUserId: Int): Result<Unit> {
        return try {
            notificationsApi.emptyTrash()
            val trashedIds = notificationDao.getAll(ownerUserId)
                .filter { it.deletedAt != null }
                .map { it.id }
            if (trashedIds.isNotEmpty()) {
                notificationDao.deleteAllById(ownerUserId, trashedIds)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun upsertFromPush(entity: NotificationEntity) {
        notificationDao.upsertAll(listOf(entity))
    }

    override suspend fun clearAll() {
        notificationDao.deleteAll()
    }
}
