package com.baluhost.android.domain.repository

import com.baluhost.android.domain.model.sync.*
import com.baluhost.android.domain.model.sync.SyncTrigger

/**
 * Repository interface for folder synchronization operations.
 */
interface SyncRepository {
    
    /**
     * Get all sync folders for the current device.
     */
    suspend fun getSyncFolders(deviceId: String): Result<List<SyncFolderConfig>>
    
    /**
     * Create a new sync folder configuration.
     */
    suspend fun createSyncFolder(
        deviceId: String,
        localPath: String,
        remotePath: String,
        syncType: SyncType,
        autoSync: Boolean = true,
        conflictResolution: ConflictResolution = ConflictResolution.KEEP_NEWEST,
        excludePatterns: List<String> = emptyList(),
        adapterType: String = "webdav",
        adapterUsername: String? = null,
        adapterPassword: String? = null,
        saveCredentials: Boolean = false
    ): Result<SyncFolderConfig>
    
    /**
     * Update an existing sync folder.
     */
    suspend fun updateSyncFolder(
        folderId: String,
        remotePath: String? = null,
        syncType: SyncType? = null,
        autoSync: Boolean? = null,
        conflictResolution: ConflictResolution? = null,
        excludePatterns: List<String>? = null,
        status: SyncStatus? = null,
        adapterType: String? = null,
        adapterUsername: String? = null,
        adapterPassword: String? = null,
        saveCredentials: Boolean? = null
    ): Result<SyncFolderConfig>
    
    /**
     * Delete a sync folder.
     */
    suspend fun deleteSyncFolder(folderId: String): Result<Unit>
    
    /**
     * Trigger manual sync for a folder.
     */
    suspend fun triggerSync(folderId: String): Result<String>
    
    /**
     * Get sync status for a folder.
     */
    suspend fun getSyncStatus(folderId: String): Result<SyncStatus>
    
    /**
     * Get upload queue for the current device.
     */
    suspend fun getUploadQueue(deviceId: String): Result<List<UploadQueueItem>>
    
    /**
     * Cancel an upload.
     */
    suspend fun cancelUpload(uploadId: String): Result<Unit>
    
    /**
     * Retry a failed upload.
     */
    suspend fun retryUpload(uploadId: String): Result<UploadQueueItem>

    /**
     * List remote files in a sync folder.
     */
    suspend fun listRemoteFiles(folderId: String, remotePath: String): List<RemoteFileInfo>

    /**
     * Upload a file to a sync folder.
     */
    suspend fun uploadFile(folderId: String, remotePath: String, file: okhttp3.MultipartBody.Part, trigger: SyncTrigger? = null)

    /**
     * Download a file from a sync folder.
     */
    suspend fun downloadFile(folderId: String, remotePath: String, trigger: SyncTrigger? = null): okhttp3.ResponseBody

    /**
     * Get sync schedules from server.
     */
    suspend fun getSyncSchedules(): Result<List<SyncSchedule>>

    /**
     * Create a new sync schedule on the server.
     */
    suspend fun createSyncSchedule(
        deviceId: String,
        scheduleType: ScheduleType,
        timeOfDay: String?,
        dayOfWeek: Int? = null,
        dayOfMonth: Int? = null,
        syncDeletions: Boolean = true,
        resolveConflicts: String = "keep_newest",
        autoVpn: Boolean = false
    ): Result<SyncSchedule>

    /**
     * Update an existing sync schedule.
     */
    suspend fun updateSyncSchedule(
        scheduleId: Int,
        scheduleType: ScheduleType? = null,
        timeOfDay: String? = null,
        dayOfWeek: Int? = null,
        dayOfMonth: Int? = null,
        enabled: Boolean? = null,
        syncDeletions: Boolean? = null,
        resolveConflicts: String? = null,
        autoVpn: Boolean? = null
    ): Result<SyncSchedule>

    /**
     * Disable a sync schedule.
     */
    suspend fun disableSyncSchedule(scheduleId: Int): Result<Unit>

    /**
     * Enable a sync schedule.
     */
    suspend fun enableSyncSchedule(scheduleId: Int): Result<Unit>

    /**
     * Check if sync is currently allowed (sleep-aware preflight).
     */
    suspend fun getSyncPreflight(): Result<SyncPreflightResponse>
}
