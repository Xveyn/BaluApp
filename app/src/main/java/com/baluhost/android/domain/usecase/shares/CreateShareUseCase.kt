package com.baluhost.android.domain.usecase.shares

import com.baluhost.android.data.remote.api.SharesApi
import com.baluhost.android.data.remote.dto.FileShareCreateDto
import com.baluhost.android.domain.model.FileShareInfo
import com.baluhost.android.util.Result
import javax.inject.Inject

class CreateShareUseCase @Inject constructor(
    private val sharesApi: SharesApi
) {
    suspend operator fun invoke(
        fileId: Int,
        sharedWithUserId: Int,
        canRead: Boolean = true,
        canWrite: Boolean = false,
        canDelete: Boolean = false,
        canShare: Boolean = false,
        expiresAt: String? = null
    ): Result<FileShareInfo> {
        return try {
            val dto = FileShareCreateDto(
                fileId = fileId,
                sharedWithUserId = sharedWithUserId,
                canRead = canRead,
                canWrite = canWrite,
                canDelete = canDelete,
                canShare = canShare,
                expiresAt = expiresAt
            )
            val response = sharesApi.createShare(dto)
            Result.Success(
                FileShareInfo(
                    id = response.id,
                    fileName = response.fileName,
                    filePath = response.filePath,
                    fileSize = response.fileSize,
                    isDirectory = response.isDirectory,
                    sharedWithUsername = response.sharedWithUsername,
                    canRead = response.canRead,
                    canWrite = response.canWrite,
                    canDelete = response.canDelete,
                    canShare = response.canShare,
                    createdAt = response.createdAt,
                    isExpired = response.isExpired
                )
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
