package com.baluhost.android.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.baluhost.android.data.local.database.entities.FileActivityEntity

/**
 * DAO for local file activity buffer.
 */
@Dao
interface FileActivityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(activity: FileActivityEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(activities: List<FileActivityEntity>)

    @Query("SELECT * FROM file_activities WHERE synced = 0 ORDER BY occurred_at ASC")
    suspend fun getUnsyncedActivities(): List<FileActivityEntity>

    @Query("UPDATE file_activities SET synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("DELETE FROM file_activities WHERE synced = 1 AND occurred_at < :before")
    suspend fun deleteSyncedOlderThan(before: Long)

    @Query("DELETE FROM file_activities")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM file_activities WHERE synced = 0")
    suspend fun getUnsyncedCount(): Int
}
