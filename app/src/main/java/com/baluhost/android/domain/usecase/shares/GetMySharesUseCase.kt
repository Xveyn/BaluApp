package com.baluhost.android.domain.usecase.shares

import com.baluhost.android.data.remote.api.SharesApi
import com.baluhost.android.domain.model.FileShareInfo
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetMySharesUseCase @Inject constructor(
    private val sharesApi: SharesApi
) {
    suspend operator fun invoke(): Result<List<FileShareInfo>> {
        return try {
            val response = sharesApi.getMyShares()
            Result.Success(
                response.map { dto ->
                    FileShareInfo(
                        id = dto.id,
                        fileName = dto.fileName,
                        filePath = dto.filePath,
                        fileSize = dto.fileSize,
                        isDirectory = dto.isDirectory,
                        sharedWithUsername = dto.sharedWithUsername,
                        canRead = dto.canRead,
                        canWrite = dto.canWrite,
                        canDelete = dto.canDelete,
                        canShare = dto.canShare,
                        createdAt = dto.createdAt,
                        isExpired = dto.isExpired
                    )
                }
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
