package com.baluhost.android.data.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.notification.ServerConnectionMonitor
import com.baluhost.android.data.notification.SyncNotificationManager
import com.baluhost.android.domain.model.sync.*
import com.baluhost.android.domain.repository.SyncRepository
import com.baluhost.android.domain.service.ConflictDetectionService
import com.baluhost.android.util.LocalFolderScanner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@HiltWorker
class FolderSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val preferencesManager: PreferencesManager,
    private val conflictDetectionService: ConflictDetectionService,
    private val syncNotificationManager: SyncNotificationManager,
    private val serverConnectionMonitor: ServerConnectionMonitor
) : CoroutineWorker(appContext, workerParams) {

    private val localFolderScanner = LocalFolderScanner(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val folderId = inputData.getString(INPUT_FOLDER_ID)
            ?: return@withContext Result.failure(
                workDataOf("error" to "No folder ID provided")
            )
        val isManual = inputData.getBoolean(INPUT_IS_MANUAL, false)
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "Starting sync for folder: $folderId (manual=$isManual)")
        serverConnectionMonitor.acquire("sync")

        try {
            // 1. Load folder config
            setProgress(workDataOf(PROGRESS_STATUS to "loading_config"))
            val deviceId = preferencesManager.getDeviceId().first() ?: ""
            val foldersResult = syncRepository.getSyncFolders(deviceId)
            val folders = foldersResult.getOrNull()
                ?: return@withContext Result.failure(
                    workDataOf("error" to "Failed to load sync folders: ${foldersResult.exceptionOrNull()?.message}")
                )

            val folderConfig = folders.find { it.id == folderId }
                ?: return@withContext Result.failure(
                    workDataOf("error" to "Folder config not found for ID: $folderId")
                )

            val folderName = localFolderScanner.getFolderDisplayName(folderConfig.localUri) ?: folderId

            // 2. Check folder accessibility
            if (!localFolderScanner.isFolderAccessible(folderConfig.localUri)) {
                syncNotificationManager.showSyncErrorNotification(
                    folderId, folderName,
                    "Ordner nicht mehr zugreifbar. Bitte Berechtigung erneuern."
                )
                return@withContext Result.failure(
                    workDataOf("error" to "Folder not accessible: ${folderConfig.localUri}")
                )
            }

            // 3. Scan local files
            setProgress(workDataOf(PROGRESS_STATUS to "scanning_local"))

            val scanResult = localFolderScanner.scanFolder(
                folderUri = folderConfig.localUri,
                recursive = true,
                excludePatterns = folderConfig.excludePatterns
            )

            if (scanResult.errors.isNotEmpty()) {
                Log.w(TAG, "Scan had errors: ${scanResult.errors}")
            }

            // Calculate hashes for local files
            val localFilesWithHash = scanResult.fileList.map { fileInfo ->
                val hash = localFolderScanner.calculateFileHash(fileInfo.uri) ?: ""
                ConflictDetectionService.LocalFileInfo(
                    relativePath = fileInfo.relativePath,
                    hash = hash,
                    size = fileInfo.size,
                    modifiedAt = fileInfo.lastModified
                )
            }

            // 4. List remote files
            setProgress(workDataOf(PROGRESS_STATUS to "listing_remote"))
            val remoteFiles = syncRepository.listRemoteFiles(
                folderId.toLongOrNull() ?: 0L,
                folderConfig.remotePath
            )

            // 5. Detect conflicts
            setProgress(workDataOf(PROGRESS_STATUS to "detecting_conflicts"))
            val analysisResult = conflictDetectionService.analyzeConflicts(
                localFiles = localFilesWithHash,
                remoteFiles = remoteFiles,
                lastSyncTime = folderConfig.lastSync
            )

            Log.d(TAG, "Conflict analysis: uploads=${analysisResult.summary.uploadsNeeded}, " +
                    "downloads=${analysisResult.summary.downloadsNeeded}, " +
                    "conflicts=${analysisResult.summary.conflictsFound}")

            // 6. Handle conflicts based on resolution strategy
            val pendingConflicts = mutableListOf<FileConflict>()
            val resolvedUploads = mutableListOf<ConflictDetectionService.ConflictCheckResult>()
            val resolvedDownloads = mutableListOf<ConflictDetectionService.ConflictCheckResult>()

            for (conflict in analysisResult.conflicts) {
                val resolution = folderConfig.conflictResolution
                if (resolution == ConflictResolution.ASK_USER) {
                    pendingConflicts.add(
                        FileConflict(
                            id = UUID.randomUUID().toString(),
                            relativePath = conflict.relativePath,
                            fileName = conflict.relativePath.substringAfterLast("/"),
                            localSize = conflict.localInfo?.size ?: 0,
                            remoteSize = conflict.remoteInfo?.size ?: 0,
                            localModifiedAt = conflict.localInfo?.modifiedAt ?: 0,
                            remoteModifiedAt = conflict.remoteInfo?.modifiedAt ?: 0,
                            detectedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    val action = conflictDetectionService.resolveConflict(
                        FileConflict(
                            id = UUID.randomUUID().toString(),
                            relativePath = conflict.relativePath,
                            fileName = conflict.relativePath.substringAfterLast("/"),
                            localSize = conflict.localInfo?.size ?: 0,
                            remoteSize = conflict.remoteInfo?.size ?: 0,
                            localModifiedAt = conflict.localInfo?.modifiedAt ?: 0,
                            remoteModifiedAt = conflict.remoteInfo?.modifiedAt ?: 0,
                            detectedAt = System.currentTimeMillis()
                        ),
                        resolution
                    )
                    when (action) {
                        ConflictDetectionService.SyncAction.UPLOAD -> resolvedUploads.add(conflict)
                        ConflictDetectionService.SyncAction.DOWNLOAD -> resolvedDownloads.add(conflict)
                        else -> { /* NO_ACTION */ }
                    }
                }
            }

            // Save pending conflicts for user resolution
            if (pendingConflicts.isNotEmpty()) {
                preferencesManager.savePendingConflicts(folderId, pendingConflicts)
                syncNotificationManager.showConflictsNotification(
                    folderId, folderName, pendingConflicts.size
                )
            }

            // Combine actions
            val allUploads = analysisResult.toUpload + resolvedUploads
            val allDownloads = analysisResult.toDownload + resolvedDownloads
            val totalActions = allUploads.size + allDownloads.size

            // 7. Execute uploads
            var filesUploaded = 0
            var filesDownloaded = 0
            var bytesTransferred = 0L
            val errors = mutableListOf<String>()

            if (folderConfig.syncType != SyncType.DOWNLOAD_ONLY) {
                for ((index, upload) in allUploads.withIndex()) {
                    if (isStopped) {
                        Log.d(TAG, "Worker stopped, aborting sync")
                        break
                    }

                    setProgress(workDataOf(
                        PROGRESS_STATUS to "uploading",
                        PROGRESS_FILE to upload.relativePath,
                        PROGRESS_CURRENT to (index + 1),
                        PROGRESS_TOTAL to totalActions
                    ))

                    try {
                        val localFileInfo = scanResult.fileList.find { it.relativePath == upload.relativePath }
                        if (localFileInfo != null) {
                            // Copy URI content to temp file for upload
                            val tempFile = File.createTempFile("sync_upload_", null, applicationContext.cacheDir)
                            try {
                                val inputStream = applicationContext.contentResolver.openInputStream(localFileInfo.uri)
                                if (inputStream == null) {
                                    errors.add("Upload failed: ${upload.relativePath} - cannot read file")
                                    continue
                                }
                                inputStream.use { input ->
                                    tempFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                if (tempFile.length() == 0L && localFileInfo.size > 0) {
                                    errors.add("Upload failed: ${upload.relativePath} - temp file empty after copy")
                                    continue
                                }

                                val requestBody = tempFile.asRequestBody(
                                    (localFileInfo.mimeType ?: "application/octet-stream").toMediaTypeOrNull()
                                )
                                val part = MultipartBody.Part.createFormData(
                                    "file", localFileInfo.name, requestBody
                                )
                                // Combine sync folder remote path with file's relative path
                                // Backend expects full path from storage root, e.g. "sven/Documents/subfolder/file.txt"
                                val fullRemotePath = buildString {
                                    append(folderConfig.remotePath.trimEnd('/'))
                                    if (upload.relativePath.isNotEmpty()) {
                                        append('/')
                                        append(upload.relativePath.trimStart('/'))
                                    }
                                }
                                syncRepository.uploadFile(
                                    folderId.toLongOrNull() ?: 0L,
                                    fullRemotePath,
                                    part
                                )
                                filesUploaded++
                                bytesTransferred += localFileInfo.size
                            } finally {
                                tempFile.delete()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Upload failed for ${upload.relativePath}", e)
                        errors.add("Upload failed: ${upload.relativePath} - ${e.message}")
                    }
                }
            }

            // 8. Execute downloads
            if (folderConfig.syncType != SyncType.UPLOAD_ONLY) {
                for ((index, download) in allDownloads.withIndex()) {
                    if (isStopped) {
                        Log.d(TAG, "Worker stopped, aborting sync")
                        break
                    }

                    val overallIndex = allUploads.size + index + 1
                    setProgress(workDataOf(
                        PROGRESS_STATUS to "downloading",
                        PROGRESS_FILE to download.relativePath,
                        PROGRESS_CURRENT to overallIndex,
                        PROGRESS_TOTAL to totalActions
                    ))

                    try {
                        // Combine sync folder remote path with file's relative path
                        val fullDownloadPath = buildString {
                            append(folderConfig.remotePath.trimEnd('/'))
                            if (download.relativePath.isNotEmpty()) {
                                append('/')
                                append(download.relativePath.trimStart('/'))
                            }
                        }
                        val responseBody = syncRepository.downloadFile(
                            folderId.toLongOrNull() ?: 0L,
                            fullDownloadPath
                        )

                        // Write downloaded file to local folder via SAF
                        val segments = download.relativePath.split("/")
                        val fileName = segments.last()

                        // Find or create parent directory in SAF tree
                        var parentDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                            applicationContext, folderConfig.localUri
                        )
                        for (segment in segments.dropLast(1)) {
                            parentDoc = parentDoc?.findFile(segment)
                                ?: parentDoc?.createDirectory(segment)
                        }

                        val existingFile = parentDoc?.findFile(fileName)
                        val targetFile = existingFile
                            ?: parentDoc?.createFile(
                                download.remoteInfo?.let { "application/octet-stream" } ?: "application/octet-stream",
                                fileName
                            )

                        if (targetFile != null) {
                            applicationContext.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                                responseBody.byteStream().use { input ->
                                    bytesTransferred += input.copyTo(output)
                                }
                            }
                            filesDownloaded++
                        } else {
                            errors.add("Download failed: Could not create file ${download.relativePath}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Download failed for ${download.relativePath}", e)
                        errors.add("Download failed: ${download.relativePath} - ${e.message}")
                    }
                }
            }

            // 9. Save sync history
            val duration = System.currentTimeMillis() - startTime
            val historyStatus = when {
                errors.isNotEmpty() && (filesUploaded > 0 || filesDownloaded > 0) -> SyncHistoryStatus.PARTIAL_SUCCESS
                errors.isNotEmpty() -> SyncHistoryStatus.FAILED
                isStopped -> SyncHistoryStatus.CANCELLED
                else -> SyncHistoryStatus.SUCCESS
            }

            val syncHistory = SyncHistory(
                id = UUID.randomUUID().toString(),
                folderId = folderId.toLongOrNull() ?: 0L,
                folderName = folderName,
                timestamp = System.currentTimeMillis(),
                status = historyStatus,
                filesUploaded = filesUploaded,
                filesDownloaded = filesDownloaded,
                filesDeleted = 0,
                conflictsDetected = analysisResult.summary.conflictsFound,
                conflictsResolved = analysisResult.summary.conflictsFound - pendingConflicts.size,
                bytesTransferred = bytesTransferred,
                durationMs = duration,
                errorMessage = errors.firstOrNull()
            )
            preferencesManager.saveSyncHistory(syncHistory)

            // 10. Show result notification
            if (errors.isEmpty()) {
                syncNotificationManager.showSyncCompleteNotification(
                    folderId, folderName, filesUploaded, filesDownloaded, duration
                )
            } else {
                syncNotificationManager.showSyncErrorNotification(
                    folderId, folderName,
                    "Sync mit Fehlern: ${errors.size} Fehler. ${errors.first()}"
                )
            }

            Log.d(TAG, "Sync completed: uploaded=$filesUploaded, downloaded=$filesDownloaded, " +
                    "conflicts=${pendingConflicts.size}, errors=${errors.size}, duration=${duration}ms")

            if (errors.isNotEmpty() && filesUploaded == 0 && filesDownloaded == 0) {
                Result.failure(workDataOf("error" to errors.first()))
            } else {
                Result.success(workDataOf(
                    "files_uploaded" to filesUploaded,
                    "files_downloaded" to filesDownloaded,
                    "conflicts" to pendingConflicts.size,
                    "errors" to errors.size,
                    "duration_ms" to duration
                ))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed unexpectedly", e)
            val duration = System.currentTimeMillis() - startTime

            syncNotificationManager.showSyncErrorNotification(
                folderId,
                folderId,
                "Synchronisation fehlgeschlagen: ${e.message}"
            )

            // Save failure to history
            try {
                preferencesManager.saveSyncHistory(
                    SyncHistory(
                        id = UUID.randomUUID().toString(),
                        folderId = folderId.toLongOrNull() ?: 0L,
                        folderName = folderId,
                        timestamp = System.currentTimeMillis(),
                        status = SyncHistoryStatus.FAILED,
                        filesUploaded = 0,
                        filesDownloaded = 0,
                        filesDeleted = 0,
                        conflictsDetected = 0,
                        conflictsResolved = 0,
                        bytesTransferred = 0,
                        durationMs = duration,
                        errorMessage = e.message
                    )
                )
            } catch (historyError: Exception) {
                Log.e(TAG, "Failed to save sync history", historyError)
            }

            Result.retry()
        } finally {
            serverConnectionMonitor.release("sync")
        }
    }

    companion object {
        private const val TAG = "FolderSyncWorker"
        const val WORK_NAME = "folder_sync_work"
        const val INPUT_FOLDER_ID = "folder_id"
        const val INPUT_IS_MANUAL = "is_manual"

        const val TAG_SYNC = "folder_sync"

        const val PROGRESS_STATUS = "status"
        const val PROGRESS_FILE = "file"
        const val PROGRESS_CURRENT = "current"
        const val PROGRESS_TOTAL = "total"

        fun createOneTimeRequest(folderId: String, isManual: Boolean = false): OneTimeWorkRequest {
            val inputData = workDataOf(
                INPUT_FOLDER_ID to folderId,
                INPUT_IS_MANUAL to isManual
            )

            return OneTimeWorkRequestBuilder<FolderSyncWorker>()
                .setInputData(inputData)
                .addTag(TAG_SYNC)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        }

        fun createPeriodicRequest(folderId: String, intervalMinutes: Long = 360L): PeriodicWorkRequest {
            val inputData = workDataOf(INPUT_FOLDER_ID to folderId)
            val safeInterval = maxOf(intervalMinutes, 15L) // WorkManager minimum is 15 minutes

            return PeriodicWorkRequestBuilder<FolderSyncWorker>(
                safeInterval, TimeUnit.MINUTES
            )
                .setInputData(inputData)
                .addTag(TAG_SYNC)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
        }
    }
}
