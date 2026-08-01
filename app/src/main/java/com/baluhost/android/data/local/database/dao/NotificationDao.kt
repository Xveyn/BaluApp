package com.baluhost.android.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.baluhost.android.data.local.database.entities.NotificationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Thin by design: every merge decision lives in NotificationMerge, which is
 * plain Kotlin and therefore unit-testable. This repo has no androidTest source
 * set, so anything expressed as a Room query is effectively untested.
 */
@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notifications: List<NotificationEntity>)

    @Update
    suspend fun update(notification: NotificationEntity)

    @Query(
        """
        SELECT * FROM notifications
        WHERE owner_user_id = :ownerUserId
          AND ((:trashed = 0 AND deleted_at IS NULL) OR (:trashed = 1 AND deleted_at IS NOT NULL))
        ORDER BY created_at DESC
        """
    )
    fun observe(ownerUserId: Int, trashed: Boolean): Flow<List<NotificationEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM notifications
        WHERE owner_user_id = :ownerUserId AND is_read = 0 AND deleted_at IS NULL
        """
    )
    fun observeUnreadCount(ownerUserId: Int): Flow<Int>

    @Query("SELECT * FROM notifications WHERE owner_user_id = :ownerUserId AND id = :id")
    suspend fun find(ownerUserId: Int, id: Int): NotificationEntity?

    @Query("SELECT * FROM notifications WHERE owner_user_id = :ownerUserId")
    suspend fun getAll(ownerUserId: Int): List<NotificationEntity>

    @Query(
        """
        SELECT * FROM notifications
        WHERE owner_user_id = :ownerUserId
          AND (local_read_at IS NOT NULL OR local_trashed_at IS NOT NULL
               OR local_restored_at IS NOT NULL OR local_snoozed_until IS NOT NULL)
        """
    )
    suspend fun getWithPendingIntent(ownerUserId: Int): List<NotificationEntity>

    @Query("DELETE FROM notifications WHERE owner_user_id = :ownerUserId AND id = :id")
    suspend fun delete(ownerUserId: Int, id: Int)

    @Query("DELETE FROM notifications WHERE owner_user_id = :ownerUserId AND id IN (:ids)")
    suspend fun deleteAllById(ownerUserId: Int, ids: List<Int>)

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()

    /**
     * Rows eligible for eviction, oldest first: never evict a row whose local
     * decision has not reached the server yet.
     */
    @Query(
        """
        SELECT * FROM notifications
        WHERE owner_user_id = :ownerUserId
          AND local_read_at IS NULL AND local_trashed_at IS NULL
          AND local_restored_at IS NULL AND local_snoozed_until IS NULL
        ORDER BY created_at ASC
        """
    )
    suspend fun getEvictable(ownerUserId: Int): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM notifications WHERE owner_user_id = :ownerUserId")
    suspend fun count(ownerUserId: Int): Int
}
