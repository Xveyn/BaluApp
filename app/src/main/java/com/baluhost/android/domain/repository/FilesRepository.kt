package com.baluhost.android.domain.repository

import com.baluhost.android.domain.model.FileItem
import com.baluhost.android.util.Result
import kotlinx.coroutines.flow.Flow

interface FilesRepository {
    fun getFiles(path: String, forceRefresh: Boolean = false): Flow<List<FileItem>>
    suspend fun refreshFiles(path: String): Result<List<FileItem>>
    suspend fun deleteFile(filePath: String): Result<Boolean>
    suspend fun clearCache()
}
