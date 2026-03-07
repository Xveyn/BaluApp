package com.baluhost.android.data.repository

import com.baluhost.android.domain.model.FileItem
import com.baluhost.android.domain.repository.FilesRepository
import com.baluhost.android.util.Result
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class FilesRepositoryImpl @Inject constructor(
    private val fileRepository: FileRepository
) : FilesRepository {

    override fun getFiles(path: String, forceRefresh: Boolean): Flow<List<FileItem>> {
        return fileRepository.getFiles(path, forceRefresh)
    }

    override suspend fun refreshFiles(path: String): Result<List<FileItem>> {
        return fileRepository.refreshFiles(path)
    }

    override suspend fun deleteFile(filePath: String): Result<Boolean> {
        return fileRepository.deleteFile(filePath)
    }

    override suspend fun clearCache() {
        fileRepository.clearCache()
    }
}
