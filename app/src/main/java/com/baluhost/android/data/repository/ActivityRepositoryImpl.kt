package com.baluhost.android.data.repository

import android.util.Log
import com.baluhost.android.data.local.database.dao.FileActivityDao
import com.baluhost.android.data.local.database.entities.FileActivityEntity
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.remote.api.ActivityApi
import com.baluhost.android.data.remote.dto.ReportActivitiesRequest
import com.baluhost.android.data.remote.dto.ReportActivityDto
import com.baluhost.android.domain.model.FileAction
import com.baluhost.android.domain.model.RecentFile
import com.baluhost.android.domain.repository.ActivityRepository
import com.baluhost.android.util.Result
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of ActivityRepository.
 * Fetches recent files from server and buffers client activities for offline sync.
 */
@Singleton
class ActivityRepositoryImpl @Inject constructor(
    private val activityApi: ActivityApi,
    private val fileActivityDao: FileActivityDao,
    private val preferencesManager: PreferencesManager
) : ActivityRepository {

    companion object {
        private const val TAG = "ActivityRepository"
        private const val SYNC_BATCH_SIZE = 50
    }

    override suspend fun getRecentFiles(limit: Int): Result<List<RecentFile>> {
        return try {
            // Sync pending activities first so the server has the latest data
            syncPendingActivities()

            val response = activityApi.getRecentFiles(limit = limit)
            val recentFiles = response.files.map { dto ->
                RecentFile(
                    filePath = dto.filePath,
                    fileName = dto.fileName,
                    isDirectory = dto.isDirectory,
                    fileSize = dto.fileSize,
                    mimeType = dto.mimeType,
                    lastAction = FileAction.fromApi(dto.lastAction),
                    lastActionAt = Instant.parse(dto.lastActionAt),
                    actionCount = dto.actionCount
                )
            }
            Log.d(TAG, "Loaded ${recentFiles.size} recent files from server")
            Result.Success(recentFiles)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recent files", e)
            Result.Error(Exception("Failed to load recent files: ${e.message}", e))
        }
    }

    override suspend fun trackActivity(
        actionType: String,
        filePath: String,
        fileName: String,
        isDirectory: Boolean,
        fileSize: Long?,
        mimeType: String?
    ) {
        try {
            val deviceId = preferencesManager.getDeviceId().first() ?: return
            val entity = FileActivityEntity(
                actionType = actionType,
                filePath = filePath,
                fileName = fileName,
                isDirectory = isDirectory,
                fileSize = fileSize,
                mimeType = mimeType,
                deviceId = deviceId,
                occurredAt = Instant.now(),
                synced = false
            )
            fileActivityDao.insert(entity)
            Log.d(TAG, "Tracked activity: $actionType for $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to track activity", e)
        }
    }

    override suspend fun syncPendingActivities(): Result<Int> {
        return try {
            val unsyncedActivities = fileActivityDao.getUnsyncedActivities()
            if (unsyncedActivities.isEmpty()) {
                return Result.Success(0)
            }

            var totalSynced = 0
            unsyncedActivities.chunked(SYNC_BATCH_SIZE).forEach { batch ->
                val request = ReportActivitiesRequest(
                    activities = batch.map { entity ->
                        ReportActivityDto(
                            actionType = entity.actionType,
                            filePath = entity.filePath,
                            fileName = entity.fileName,
                            isDirectory = entity.isDirectory,
                            fileSize = entity.fileSize,
                            mimeType = entity.mimeType,
                            deviceId = entity.deviceId,
                            occurredAt = DateTimeFormatter.ISO_INSTANT
                                .format(entity.occurredAt.atZone(ZoneOffset.UTC))
                        )
                    }
                )

                val response = activityApi.reportActivities(request)
                val syncedIds = batch.map { it.id }
                fileActivityDao.markAsSynced(syncedIds)
                totalSynced += response.accepted + response.deduplicated
                Log.d(TAG, "Synced batch: ${response.accepted} accepted, ${response.deduplicated} deduplicated")
            }

            // Cleanup old synced entries (older than 7 days)
            val cleanupThreshold = Instant.now().minusSeconds(7 * 24 * 60 * 60).toEpochMilli()
            fileActivityDao.deleteSyncedOlderThan(cleanupThreshold)

            Log.d(TAG, "Synced $totalSynced activities total")
            Result.Success(totalSynced)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync activities", e)
            Result.Error(Exception("Failed to sync activities: ${e.message}", e))
        }
    }
}
