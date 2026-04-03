package com.baluhost.android.data.repository

import android.net.Uri
import android.util.Log
import com.baluhost.android.data.remote.api.FilesApi
import com.baluhost.android.data.remote.api.SyncApi
import com.baluhost.android.data.remote.dto.sync.*
import com.baluhost.android.domain.model.sync.*
import com.baluhost.android.domain.repository.SyncRepository
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Implementation of SyncRepository.
 * Handles API calls and DTO to domain model mapping for folder synchronization.
 */
class SyncRepositoryImpl @Inject constructor(
    private val syncApi: SyncApi,
    private val filesApi: FilesApi
) : SyncRepository {
    
    override suspend fun getSyncFolders(deviceId: String): Result<List<SyncFolderConfig>> {
        return try {
            val response = syncApi.getSyncFolders(deviceId)
            Result.success(response.map { it.toDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun createSyncFolder(
        deviceId: String,
        localPath: String,
        remotePath: String,
        syncType: SyncType,
        autoSync: Boolean,
        conflictResolution: ConflictResolution,
        excludePatterns: List<String>,
        adapterType: String,
        adapterUsername: String?,
        adapterPassword: String?,
        saveCredentials: Boolean
    ): Result<SyncFolderConfig> {
        return try {
            val dto = SyncFolderCreateDto(
                localPath = localPath,
                remotePath = remotePath,
                syncType = syncType.toApiString(),
                autoSync = autoSync,
                conflictResolution = conflictResolution.toApiString(),
                adapterType = adapterType,
                adapterUsername = adapterUsername,
                adapterPassword = adapterPassword,
                saveCredentials = saveCredentials,
                excludePatterns = excludePatterns
            )
            val response = syncApi.createSyncFolder(deviceId, dto)
            Result.success(response.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun updateSyncFolder(
        folderId: String,
        remotePath: String?,
        syncType: SyncType?,
        autoSync: Boolean?,
        conflictResolution: ConflictResolution?,
        excludePatterns: List<String>?,
        status: SyncStatus?,
        adapterType: String?,
        adapterUsername: String?,
        adapterPassword: String?,
        saveCredentials: Boolean?
    ): Result<SyncFolderConfig> {
        return try {
            val dto = SyncFolderUpdateDto(
                remotePath = remotePath,
                syncType = syncType?.toApiString(),
                autoSync = autoSync,
                conflictResolution = conflictResolution?.toApiString(),
                adapterType = adapterType,
                adapterUsername = adapterUsername,
                adapterPassword = adapterPassword,
                saveCredentials = saveCredentials,
                excludePatterns = excludePatterns,
                status = status?.toApiString()
            )
            val response = syncApi.updateSyncFolder(folderId, dto)
            Result.success(response.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun deleteSyncFolder(folderId: String): Result<Unit> {
        return try {
            syncApi.deleteSyncFolder(folderId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun triggerSync(folderId: String): Result<String> {
        return try {
            val response = syncApi.triggerSync(folderId)
            Result.success(response.message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getSyncStatus(folderId: String): Result<SyncStatus> {
        return try {
            val response = syncApi.getSyncStatus(folderId)
            Result.success(SyncStatus.fromString(response.status))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getUploadQueue(deviceId: String): Result<List<UploadQueueItem>> {
        return try {
            val response = syncApi.getUploadQueue(deviceId)
            Result.success(response.items.map { it.toDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun cancelUpload(uploadId: String): Result<Unit> {
        return try {
            syncApi.cancelUpload(uploadId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun retryUpload(uploadId: String): Result<UploadQueueItem> {
        return try {
            val response = syncApi.retryUpload(uploadId)
            Result.success(response.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun listRemoteFiles(folderId: String, remotePath: String): List<RemoteFileInfo> {
        val allFiles = mutableListOf<RemoteFileInfo>()
        val basePath = remotePath.trimEnd('/')
        listFilesRecursive(basePath, basePath, allFiles)
        Log.d("SyncRepositoryImpl", "listRemoteFiles: found ${allFiles.size} files in $remotePath")
        return allFiles
    }

    /**
     * Recursively list all files under a path using the files/list endpoint.
     * Converts absolute paths to relative paths for conflict detection.
     */
    private suspend fun listFilesRecursive(
        currentPath: String,
        basePath: String,
        result: MutableList<RemoteFileInfo>
    ) {
        val response = filesApi.listFiles(currentPath)
        for (item in response.files) {
            if (item.isDirectory) {
                listFilesRecursive(item.path, basePath, result)
            } else {
                // Convert absolute path to relative path from basePath
                val relativePath = item.path
                    .removePrefix(basePath)
                    .removePrefix("/")
                result.add(
                    RemoteFileInfo(
                        relativePath = relativePath,
                        name = item.name,
                        size = item.size,
                        hash = item.checksum ?: "",
                        modifiedAt = parseIsoTimestamp(item.modifiedAt)
                    )
                )
            }
        }
    }

    override suspend fun uploadFile(
        folderId: String,
        remotePath: String,
        file: okhttp3.MultipartBody.Part
    ) {
        val pathBody = remotePath.toRequestBody("text/plain".toMediaTypeOrNull())
        syncApi.uploadFile(file, pathBody)
    }

    /**
     * Initiate chunked upload.
     */
    suspend fun initiateChunkedUpload(
        request: InitiateUploadDto
    ): InitiateUploadResponseDto {
        return syncApi.initiateChunkedUpload(request)
    }

    /**
     * Upload a chunk.
     */
    suspend fun uploadChunk(
        uploadId: String,
        chunkIndex: Int,
        chunk: okhttp3.RequestBody
    ): ChunkUploadResponseDto {
        return syncApi.uploadChunk(uploadId, chunkIndex, chunk)
    }

    /**
     * Finalize chunked upload.
     */
    suspend fun finalizeChunkedUpload(uploadId: String) {
        syncApi.finalizeChunkedUpload(uploadId)
    }

    /**
     * Cancel chunked upload.
     */
    suspend fun cancelChunkedUpload(uploadId: String) {
        syncApi.cancelChunkedUpload(uploadId)
    }
    
    override suspend fun downloadFile(
        folderId: String,
        remotePath: String
    ): okhttp3.ResponseBody {
        return syncApi.downloadFile(remotePath)
    }

    override suspend fun getSyncSchedules(): Result<List<SyncSchedule>> {
        return try {
            val response = syncApi.getSyncSchedules()
            Result.success(response.schedules.map { it.toDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createSyncSchedule(
        deviceId: String,
        scheduleType: ScheduleType,
        timeOfDay: String?,
        dayOfWeek: Int?,
        dayOfMonth: Int?,
        syncDeletions: Boolean,
        resolveConflicts: String,
        autoVpn: Boolean
    ): Result<SyncSchedule> {
        return try {
            val dto = SyncScheduleCreateDto(
                deviceId = deviceId,
                scheduleType = scheduleType.toApiString(),
                timeOfDay = timeOfDay,
                dayOfWeek = dayOfWeek,
                dayOfMonth = dayOfMonth,
                syncDeletions = syncDeletions,
                resolveConflicts = resolveConflicts,
                autoVpn = autoVpn
            )
            val response = syncApi.createSyncSchedule(dto)
            Result.success(response.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateSyncSchedule(
        scheduleId: Int,
        scheduleType: ScheduleType?,
        timeOfDay: String?,
        dayOfWeek: Int?,
        dayOfMonth: Int?,
        enabled: Boolean?,
        syncDeletions: Boolean?,
        resolveConflicts: String?,
        autoVpn: Boolean?
    ): Result<SyncSchedule> {
        return try {
            val dto = SyncScheduleUpdateDto(
                scheduleType = scheduleType?.toApiString(),
                timeOfDay = timeOfDay,
                dayOfWeek = dayOfWeek,
                dayOfMonth = dayOfMonth,
                isActive = enabled,
                syncDeletions = syncDeletions,
                resolveConflicts = resolveConflicts,
                autoVpn = autoVpn
            )
            val response = syncApi.updateSyncSchedule(scheduleId, dto)
            Result.success(response.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disableSyncSchedule(scheduleId: Int): Result<Unit> {
        return try {
            syncApi.disableSyncSchedule(scheduleId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun enableSyncSchedule(scheduleId: Int): Result<Unit> {
        return try {
            syncApi.enableSyncSchedule(scheduleId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSyncPreflight(): Result<SyncPreflightResponse> {
        return try {
            val dto = syncApi.getSyncPreflight()
            Result.success(
                SyncPreflightResponse(
                    syncAllowed = dto.syncAllowed,
                    currentSleepState = dto.currentSleepState,
                    sleepSchedule = dto.sleepSchedule?.let {
                        SleepScheduleInfo(
                            enabled = it.enabled,
                            sleepTime = it.sleepTime,
                            wakeTime = it.wakeTime,
                            mode = it.mode
                        )
                    },
                    nextWakeAt = dto.nextWakeAt,
                    blockReason = dto.blockReason
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Extension functions to map DTOs to domain models.
 */

private fun SyncFolderDto.toDomain() = SyncFolderConfig(
    id = id,
    deviceId = deviceId,
    localUri = Uri.parse(localPath),
    remotePath = remotePath,
    syncType = SyncType.fromString(syncType),
    autoSync = autoSync,
    conflictResolution = ConflictResolution.fromString(conflictResolution ?: "keep_newest"),
    syncStatus = SyncStatus.fromString(status),
    lastSync = lastSync?.let { parseIsoTimestamp(it) },
    excludePatterns = excludePatterns ?: emptyList()
)

private fun RemoteFileDto.toDomain() = RemoteFileInfo(
    relativePath = relativePath,
    name = name,
    size = size,
    hash = hash,
    modifiedAt = parseIsoTimestamp(modifiedAt)
)

private fun UploadQueueDto.toDomain() = UploadQueueItem(
    id = id,
    folderId = folderId ?: "",
    fileName = filename,
    filePath = "", // Local path not returned by API
    remotePath = remotePath,
    fileSize = fileSize,
    uploadedBytes = uploadedBytes,
    status = UploadStatus.fromString(status),
    retryCount = retryCount,
    createdAt = parseIsoTimestamp(createdAt),
    errorMessage = errorMessage
)

private fun SyncScheduleDto.toDomain() = SyncSchedule(
    scheduleId = scheduleId,
    deviceId = deviceId,
    scheduleType = ScheduleType.fromString(scheduleType),
    timeOfDay = timeOfDay,
    dayOfWeek = dayOfWeek,
    dayOfMonth = dayOfMonth,
    nextRunAt = nextRunAt?.let { parseIsoTimestamp(it) },
    lastRunAt = lastRunAt?.let { parseIsoTimestamp(it) },
    enabled = enabled,
    syncDeletions = syncDeletions,
    resolveConflicts = resolveConflicts,
    autoVpn = autoVpn
)

/**
 * Parse ISO 8601 timestamp to milliseconds since epoch.
 */
private fun parseIsoTimestamp(timestamp: String): Long {
    return try {
        val zonedDateTime = ZonedDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME)
        zonedDateTime.toInstant().toEpochMilli()
    } catch (e: Exception) {
        try {
            Instant.parse(timestamp).toEpochMilli()
        } catch (e2: Exception) {
            // Timestamps without timezone info (e.g. "2026-03-29T12:30:00") — assume UTC
            try {
                LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (e3: Exception) {
                0L
            }
        }
    }
}
