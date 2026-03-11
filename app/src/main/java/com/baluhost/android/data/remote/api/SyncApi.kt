package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.sync.*
import retrofit2.http.*

/**
 * API interface for folder synchronization endpoints.
 * Connects to existing backend sync endpoints from mobile_routes.py
 */
interface SyncApi {
    
    /**
     * Get all sync folders for a device.
     */
    @GET("mobile/sync/folders/{device_id}")
    suspend fun getSyncFolders(
        @Path("device_id") deviceId: String
    ): List<SyncFolderDto>
    
    /**
     * Create a new sync folder configuration.
     */
    @POST("mobile/sync/folders/{device_id}")
    suspend fun createSyncFolder(
        @Path("device_id") deviceId: String,
        @Body folder: SyncFolderCreateDto
    ): SyncFolderDto
    
    /**
     * Update an existing sync folder.
     */
    @PUT("mobile/sync/folders/{folder_id}")
    suspend fun updateSyncFolder(
        @Path("folder_id") folderId: String,
        @Body updates: SyncFolderUpdateDto
    ): SyncFolderDto
    
    /**
     * Delete a sync folder.
     */
    @DELETE("mobile/sync/folders/{folder_id}")
    suspend fun deleteSyncFolder(
        @Path("folder_id") folderId: String
    )
    
    /**
     * Trigger manual sync for a folder.
     */
    @POST("mobile/sync/folders/{folder_id}/trigger")
    suspend fun triggerSync(
        @Path("folder_id") folderId: String
    ): SyncTriggerResponseDto
    
    /**
     * Get sync status for a folder.
     */
    @GET("mobile/sync/folders/{folder_id}/status")
    suspend fun getSyncStatus(
        @Path("folder_id") folderId: String
    ): SyncStatusResponseDto
    
    /**
     * Get upload queue for a device.
     */
    @GET("mobile/upload/queue/{device_id}")
    suspend fun getUploadQueue(
        @Path("device_id") deviceId: String
    ): UploadQueueListResponseDto
    
    /**
     * Cancel an upload.
     */
    @DELETE("mobile/upload/queue/{upload_id}")
    suspend fun cancelUpload(
        @Path("upload_id") uploadId: String
    )
    
    /**
     * Retry a failed upload.
     */
    @POST("mobile/upload/queue/{upload_id}/retry")
    suspend fun retryUpload(
        @Path("upload_id") uploadId: String
    ): UploadQueueDto
    
    /**
     * List files in a remote folder.
     */
    @GET("mobile/sync/folders/{folder_id}/files")
    suspend fun listRemoteFiles(
        @Path("folder_id") folderId: String,
        @Query("path") remotePath: String
    ): RemoteFileListResponseDto
    
    /**
     * Upload a file via the files/upload endpoint.
     * Backend accepts 'file' (single) or 'files' (list) + 'path' form field.
     */
    @Multipart
    @POST("files/upload")
    suspend fun uploadFile(
        @Part file: okhttp3.MultipartBody.Part,
        @Part("path") remotePath: okhttp3.RequestBody
    )

    /**
     * Initiate a chunked upload session.
     * Backend: POST /files/upload/chunked/init
     */
    @POST("files/upload/chunked/init")
    suspend fun initiateChunkedUpload(
        @Body request: InitiateUploadDto
    ): InitiateUploadResponseDto

    /**
     * Upload a single chunk as raw bytes.
     * Backend: POST /files/upload/chunked/{upload_id}/chunk?chunk_index=N
     */
    @POST("files/upload/chunked/{upload_id}/chunk")
    suspend fun uploadChunk(
        @Path("upload_id") uploadId: String,
        @Query("chunk_index") chunkIndex: Int,
        @Body chunk: okhttp3.RequestBody
    ): ChunkUploadResponseDto

    /**
     * Finalize a chunked upload.
     * Backend: POST /files/upload/chunked/{upload_id}/complete
     */
    @POST("files/upload/chunked/{upload_id}/complete")
    suspend fun finalizeChunkedUpload(
        @Path("upload_id") uploadId: String
    )

    /**
     * Cancel a chunked upload.
     * Backend: DELETE /files/upload/chunked/{upload_id}
     */
    @DELETE("files/upload/chunked/{upload_id}")
    suspend fun cancelChunkedUpload(
        @Path("upload_id") uploadId: String
    )
    
    /**
     * Download a file from server.
     * Backend: GET /files/download/{resource_path}
     * resource_path is the full path from storage root (e.g. "sven/Documents/file.txt")
     */
    @Streaming
    @GET("files/download/{resource_path}")
    suspend fun downloadFile(
        @Path("resource_path", encoded = true) resourcePath: String
    ): okhttp3.ResponseBody

    /**
     * Get sync schedules configured on the server.
     */
    @GET("sync/schedule/list")
    suspend fun getSyncSchedules(): SyncScheduleListResponseDto

    /**
     * Create a new sync schedule.
     */
    @POST("sync/schedule/create")
    suspend fun createSyncSchedule(@Body schedule: SyncScheduleCreateDto): SyncScheduleDto

    /**
     * Update an existing sync schedule.
     */
    @PUT("sync/schedule/{schedule_id}")
    suspend fun updateSyncSchedule(
        @Path("schedule_id") scheduleId: Int,
        @Body updates: SyncScheduleUpdateDto
    ): SyncScheduleDto

    /**
     * Disable a sync schedule.
     */
    @POST("sync/schedule/{schedule_id}/disable")
    suspend fun disableSyncSchedule(@Path("schedule_id") scheduleId: Int)

    /**
     * Enable a sync schedule.
     */
    @POST("sync/schedule/{schedule_id}/enable")
    suspend fun enableSyncSchedule(@Path("schedule_id") scheduleId: Int)
}
