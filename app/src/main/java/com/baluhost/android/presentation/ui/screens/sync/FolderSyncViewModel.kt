package com.baluhost.android.presentation.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.remote.api.FilesApi
import com.baluhost.android.data.worker.FolderSyncWorker
import com.baluhost.android.domain.model.sync.*
import com.baluhost.android.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for FolderSyncScreen.
 * Manages sync folders, upload queue, and sync operations.
 */
@HiltViewModel
class FolderSyncViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val preferencesManager: PreferencesManager,
    private val workManager: WorkManager,
    private val filesApi: FilesApi
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<FolderSyncState>(FolderSyncState.Loading)
    val uiState: StateFlow<FolderSyncState> = _uiState.asStateFlow()
    
    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()
    
    // Pending conflicts that need manual resolution
    private val _pendingConflicts = MutableStateFlow<List<FileConflict>>(emptyList())
    val pendingConflicts: StateFlow<List<FileConflict>> = _pendingConflicts.asStateFlow()

    // NAS folder browsing state
    private val _nasState = MutableStateFlow(NasBrowseState())
    val nasState: StateFlow<NasBrowseState> = _nasState.asStateFlow()

    // User info for path defaults
    private val _userInfo = MutableStateFlow(UserInfo())
    val userInfo: StateFlow<UserInfo> = _userInfo.asStateFlow()

    // Real-time sync progress from WorkManager, keyed by folderId
    private val _syncProgress = MutableStateFlow<Map<String, SyncProgressInfo>>(emptyMap())
    val syncProgress: StateFlow<Map<String, SyncProgressInfo>> = _syncProgress.asStateFlow()

    init {
        loadUserInfo()
        // Cancel any stale/stuck sync workers, then prune completed entries
        workManager.cancelAllWorkByTag(FolderSyncWorker.TAG_SYNC)
        workManager.pruneWork()
        loadSyncFolders()
        observeAllSyncWorkers()
    }
    
    fun loadSyncFolders() {
        viewModelScope.launch {
            _uiState.value = FolderSyncState.Loading
            
            try {
                com.baluhost.android.util.Logger.i("FolderSyncViewModel", "loadSyncFolders: start")
                val deviceId = preferencesManager.getDeviceId().first() 
                    ?: throw Exception("Device ID not found")
                
                // Load sync folders
                val foldersResult = syncRepository.getSyncFolders(deviceId)
                if (foldersResult.isFailure) {
                    _uiState.value = FolderSyncState.Error(
                        foldersResult.exceptionOrNull()?.message ?: "Failed to load sync folders"
                    )
                    return@launch
                }
                
                val folders = foldersResult.getOrNull() ?: emptyList()
                
                // Load upload queue
                val queueResult = syncRepository.getUploadQueue(deviceId)
                val uploadQueue = queueResult.getOrNull() ?: emptyList()
                
                _uiState.value = FolderSyncState.Success(
                    folders = folders,
                    uploadQueue = uploadQueue
                )
                com.baluhost.android.util.Logger.i("FolderSyncViewModel", "loadSyncFolders: loaded ${folders.size} folders, uploadQueue=${uploadQueue.size}")
                
                // Check for pending conflicts
                folders.forEach { folder ->
                    val conflicts = preferencesManager.getPendingConflicts(folder.id).first()
                    if (conflicts.isNotEmpty()) {
                        _pendingConflicts.value = conflicts
                    }
                }
                
            } catch (e: Exception) {
                com.baluhost.android.util.Logger.e("FolderSyncViewModel", "loadSyncFolders failed", e)
                _uiState.value = FolderSyncState.Error(
                    e.message ?: "Unknown error occurred"
                )
            }
        }
    }
    
    fun refreshSyncFolders() {
        viewModelScope.launch {
            try {
                val deviceId = preferencesManager.getDeviceId().first()
                    ?: throw Exception("Device ID not found")
                val foldersResult = syncRepository.getSyncFolders(deviceId)
                if (foldersResult.isFailure) return@launch
                val folders = foldersResult.getOrNull() ?: emptyList()
                val queueResult = syncRepository.getUploadQueue(deviceId)
                val uploadQueue = queueResult.getOrNull() ?: emptyList()
                _uiState.value = FolderSyncState.Success(
                    folders = folders,
                    uploadQueue = uploadQueue
                )
            } catch (_: Exception) {
                // Silently fail on refresh - keep existing data visible
            }
        }
    }

    fun browseNasFolder(path: String = "") {
        viewModelScope.launch {
            _nasState.value = _nasState.value.copy(isLoading = true, error = null)
            try {
                val response = filesApi.listFiles(path)
                val folders = response.files
                    .filter { it.isDirectory }
                    .map { NasFolder(it.name, it.path) }
                _nasState.value = NasBrowseState(
                    currentPath = path,
                    folders = folders,
                    isLoading = false
                )
            } catch (e: Exception) {
                _nasState.value = _nasState.value.copy(
                    isLoading = false,
                    error = "NAS nicht erreichbar: ${e.message}"
                )
            }
        }
    }

    fun resetNasBrowseState() {
        _nasState.value = NasBrowseState()
    }

    /**
     * Observe all sync workers tagged with TAG_SYNC and map their progress
     * back to folder cards in real-time.
     */
    private fun observeAllSyncWorkers() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(FolderSyncWorker.TAG_SYNC)
                .collect { workInfos ->
                    val progressMap = mutableMapOf<String, SyncProgressInfo>()
                    var anyFinished = false

                    for (info in workInfos) {
                        val folderId = when (info.state) {
                            WorkInfo.State.RUNNING -> info.progress.getString(FolderSyncWorker.INPUT_FOLDER_ID)
                                ?: info.tags.firstOrNull { it.startsWith("folder:") }?.removePrefix("folder:")
                            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> {
                                anyFinished = true
                                null
                            }
                            else -> null
                        } ?: continue

                        val progress = info.progress
                        val status = progress.getString(FolderSyncWorker.PROGRESS_STATUS) ?: "running"
                        val fileName = progress.getString(FolderSyncWorker.PROGRESS_FILE)
                        val current = progress.getInt(FolderSyncWorker.PROGRESS_CURRENT, 0)
                        val total = progress.getInt(FolderSyncWorker.PROGRESS_TOTAL, 0)

                        progressMap[folderId] = SyncProgressInfo(
                            status = status,
                            currentFile = fileName,
                            current = current,
                            total = total
                        )
                    }

                    _syncProgress.value = progressMap

                    // Refresh folder list when a worker finishes
                    if (anyFinished) {
                        refreshSyncFolders()
                    }
                }
        }
    }

    private fun loadUserInfo() {
        viewModelScope.launch {
            val username = preferencesManager.getUsername().first() ?: ""
            val role = preferencesManager.getUserRole().first() ?: "user"
            _userInfo.value = UserInfo(username = username, isAdmin = role == "admin")
        }
    }

    fun createFolder(config: SyncFolderCreateConfig) {
        viewModelScope.launch {
            try {
                val deviceId = preferencesManager.getDeviceId().first() 
                    ?: throw Exception("Device ID not found")
                
                val result = syncRepository.createSyncFolder(
                    deviceId = deviceId,
                    localPath = config.localUri.toString(),
                    remotePath = config.remotePath,
                    syncType = config.syncType,
                    autoSync = config.autoSync,
                    conflictResolution = config.conflictResolution,
                    excludePatterns = config.excludePatterns,
                    adapterType = config.adapterType,
                    adapterUsername = config.credentials?.username,
                    adapterPassword = config.credentials?.password,
                    saveCredentials = config.saveCredentials
                )
                
                if (result.isSuccess) {
                    val folder = result.getOrNull()!!
                    // Save URI mapping in preferences
                    preferencesManager.saveSyncFolderUri(folder.id, config.localUri.toString())

                    // Persist credentials securely if requested
                    if (config.saveCredentials && config.credentials != null) {
                        preferencesManager.saveAdapterCredentials(folder.id, config.credentials.username, config.credentials.password)
                    }

                    _snackbarMessage.emit("Sync folder created successfully")
                    loadSyncFolders()
                } else {
                    _snackbarMessage.emit("Failed to create sync folder: ${result.exceptionOrNull()?.message}")
                }
                
            } catch (e: Exception) {
                _snackbarMessage.emit("Error: ${e.message}")
            }
        }
    }
    
    fun updateFolder(config: SyncFolderUpdateConfig) {
        viewModelScope.launch {
            try {
                val result = syncRepository.updateSyncFolder(
                    folderId = config.folderId,
                    remotePath = config.remotePath,
                    syncType = config.syncType,
                    autoSync = config.autoSync,
                    conflictResolution = config.conflictResolution,
                    excludePatterns = config.excludePatterns,
                    status = null,
                    adapterType = config.adapterType,
                    adapterUsername = config.credentials?.username,
                    adapterPassword = config.credentials?.password,
                    saveCredentials = config.saveCredentials
                )
                
                if (result.isSuccess) {
                    // Persist credentials if requested
                    if (config.saveCredentials == true && config.credentials != null) {
                        preferencesManager.saveAdapterCredentials(config.folderId, config.credentials.username, config.credentials.password)
                    }

                    _snackbarMessage.emit("Sync folder updated")
                    loadSyncFolders()
                } else {
                    _snackbarMessage.emit("Failed to update: ${result.exceptionOrNull()?.message}")
                }
                
            } catch (e: Exception) {
                _snackbarMessage.emit("Error: ${e.message}")
            }
        }
    }
    
    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            try {
                val result = syncRepository.deleteSyncFolder(folderId)
                
                if (result.isSuccess) {
                    // Remove URI mapping
                    preferencesManager.removeSyncFolderUri(folderId)
                    
                    _snackbarMessage.emit("Sync folder removed")
                    loadSyncFolders()
                } else {
                    _snackbarMessage.emit("Failed to delete: ${result.exceptionOrNull()?.message}")
                }
                
            } catch (e: Exception) {
                _snackbarMessage.emit("Error: ${e.message}")
            }
        }
    }
    
    fun triggerSync(folderId: String) {
        viewModelScope.launch {
            try {
                // Enqueue unique WorkManager job - APPEND_OR_REPLACE avoids both
                // duplicate workers and stale entries blocking new work
                val workRequest = FolderSyncWorker.createOneTimeRequest(
                    folderId = folderId,
                    isManual = true
                )

                workManager.enqueueUniqueWork(
                    "sync_manual_$folderId",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    workRequest
                )

                android.util.Log.d("FolderSyncViewModel", "triggerSync enqueued for $folderId")
                _snackbarMessage.emit("Sync wird im Hintergrund ausgeführt")

                // Observe work status
                observeWorkProgress(workRequest.id)

            } catch (e: Exception) {
                _snackbarMessage.emit("Fehler beim Starten der Synchronisation: ${e.message}")
            }
        }
    }
    
    /**
     * Schedule periodic background sync for a folder.
     */
    private fun schedulePeriodicSync(folderId: String) {
        val periodicWork = FolderSyncWorker.createPeriodicRequest(folderId)
        workManager.enqueueUniquePeriodicWork(
            "${FolderSyncWorker.WORK_NAME}_$folderId",
            androidx.work.ExistingPeriodicWorkPolicy.REPLACE,
            periodicWork
        )
    }
    
    /**
     * Cancel periodic sync for a folder.
     */
    fun cancelPeriodicSync(folderId: String) {
        workManager.cancelUniqueWork("${FolderSyncWorker.WORK_NAME}_$folderId")
    }
    
    /**
     * Observe Worker progress and update UI.
     */
    private fun observeWorkProgress(workId: java.util.UUID) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                when (workInfo?.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress
                        val status = progress.getString(FolderSyncWorker.PROGRESS_STATUS)
                        val file = progress.getString(FolderSyncWorker.PROGRESS_FILE)
                        
                        if (status != null) {
                            _snackbarMessage.emit(status + if (file != null) ": $file" else "")
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _snackbarMessage.emit("Synchronisation abgeschlossen")
                        loadSyncFolders()
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString("error")
                        _snackbarMessage.emit("Synchronisation fehlgeschlagen: $error")
                        loadSyncFolders()
                    }
                    WorkInfo.State.CANCELLED -> {
                        _snackbarMessage.emit("Synchronisation abgebrochen")
                    }
                    else -> {
                        // ENQUEUED, BLOCKED - no action needed
                    }
                }
            }
        }
    }
    
    /**
     * Resolve conflicts with specified resolutions.
     * Triggers a new sync with the resolved actions.
     */
    fun resolveConflicts(folderId: String, resolutions: Map<String, ConflictResolution>) {
        viewModelScope.launch {
            try {
                // Apply resolutions by triggering targeted uploads/downloads
                // This would ideally be handled by a dedicated API endpoint
                // For now, we'll clear the conflicts and re-trigger sync
                
                preferencesManager.clearPendingConflicts(folderId)
                _pendingConflicts.value = emptyList()
                
                // Re-trigger sync which will now succeed without conflicts
                triggerSync(folderId)
                
                _snackbarMessage.emit("Konflikte aufgelöst, Synchronisation läuft")
                
            } catch (e: Exception) {
                _snackbarMessage.emit("Fehler beim Auflösen der Konflikte: ${e.message}")
            }
        }
    }
    
    /**
     * Dismiss conflicts without resolving (skip for now).
     */
    fun dismissConflicts(folderId: String) {
        viewModelScope.launch {
            preferencesManager.clearPendingConflicts(folderId)
            _pendingConflicts.value = emptyList()
            _snackbarMessage.emit("Konflikte übersprungen")
        }
    }
    
    fun cancelUpload(uploadId: String) {
        viewModelScope.launch {
            try {
                val result = syncRepository.cancelUpload(uploadId)
                
                if (result.isSuccess) {
                    _snackbarMessage.emit("Upload cancelled")
                    loadSyncFolders()
                } else {
                    _snackbarMessage.emit("Failed to cancel: ${result.exceptionOrNull()?.message}")
                }
                
            } catch (e: Exception) {
                _snackbarMessage.emit("Error: ${e.message}")
            }
        }
    }
    
    fun retryUpload(uploadId: String) {
        viewModelScope.launch {
            try {
                val result = syncRepository.retryUpload(uploadId)
                
                if (result.isSuccess) {
                    _snackbarMessage.emit("Upload retrying")
                    loadSyncFolders()
                } else {
                    _snackbarMessage.emit("Failed to retry: ${result.exceptionOrNull()?.message}")
                }
                
            } catch (e: Exception) {
                _snackbarMessage.emit("Error: ${e.message}")
            }
        }
    }
    
    private fun startStatusPolling(folderId: String) {
        viewModelScope.launch {
            // Poll every 2 seconds for 30 seconds max
            repeat(15) {
                kotlinx.coroutines.delay(2000)
                loadSyncFolders()
                
                // Check if sync is still running
                val state = _uiState.value
                if (state is FolderSyncState.Success) {
                    val folder = state.folders.find { it.id == folderId }
                    if (folder?.syncStatus != SyncStatus.SYNCING) {
                        return@launch // Stop polling
                    }
                }
            }
        }
    }
}

/**
 * UI state for folder sync screen.
 */
sealed class FolderSyncState {
    object Loading : FolderSyncState()
    
    data class Success(
        val folders: List<SyncFolderConfig>,
        val uploadQueue: List<UploadQueueItem>
    ) : FolderSyncState()
    
    data class Error(val message: String) : FolderSyncState()
}

/**
 * Configuration for creating a new sync folder.
 */
data class SyncFolderCreateConfig(
    val localUri: android.net.Uri,
    val remotePath: String,
    val syncType: SyncType,
    val autoSync: Boolean,
    val conflictResolution: ConflictResolution,
    val excludePatterns: List<String>,
    val adapterType: String = "webdav",
    val credentials: Credentials? = null,
    val saveCredentials: Boolean = false
)

/**
 * Configuration for updating an existing sync folder.
 */
data class SyncFolderUpdateConfig(
    val folderId: String,
    val remotePath: String?,
    val syncType: SyncType?,
    val autoSync: Boolean?,
    val conflictResolution: ConflictResolution?,
    val excludePatterns: List<String>?,
    val adapterType: String? = null,
    val credentials: Credentials? = null,
    val saveCredentials: Boolean? = null
)

data class Credentials(val username: String?, val password: String?)

data class NasBrowseState(
    val currentPath: String = "",
    val folders: List<NasFolder> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

data class NasFolder(
    val name: String,
    val path: String
)

data class UserInfo(
    val username: String = "",
    val isAdmin: Boolean = false
)

data class SyncProgressInfo(
    val status: String,
    val currentFile: String?,
    val current: Int,
    val total: Int
) {
    val progress: Float
        get() = if (total > 0) current.toFloat() / total else 0f

    val statusText: String
        get() = when (status) {
            "scanning_local" -> "Dateien scannen..."
            "listing_remote" -> "Remote-Dateien abrufen..."
            "detecting_conflicts" -> "Konflikte prüfen..."
            "loading_config" -> "Konfiguration laden..."
            "uploading" -> if (total > 0) "Hochladen ($current/$total)" else "Hochladen..."
            "downloading" -> if (total > 0) "Herunterladen ($current/$total)" else "Herunterladen..."
            else -> "Sync läuft..."
        }
}

// Helper to load stored adapter credentials for a folder
suspend fun PreferencesManager.loadStoredCredentialsFor(folderId: String): Credentials? {
    val (u, p) = getAdapterCredentials(folderId)
    return if (u != null || p != null) Credentials(u, p) else null
}
