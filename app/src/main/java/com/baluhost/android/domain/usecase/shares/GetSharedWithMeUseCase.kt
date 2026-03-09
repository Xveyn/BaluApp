package com.baluhost.android.domain.usecase.shares

import com.baluhost.android.data.remote.api.SharesApi
import com.baluhost.android.domain.model.SharedWithMeInfo
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetSharedWithMeUseCase @Inject constructor(
    private val sharesApi: SharesApi
) {
    suspend operator fun invoke(): Result<List<SharedWithMeInfo>> {
        return try {
            val response = sharesApi.getSharedWithMe()
            Result.Success(
                response.map { dto ->
                    SharedWithMeInfo(
                        shareId = dto.shareId,
                        fileName = dto.fileName,
                        filePath = dto.filePath,
                        fileSize = dto.fileSize,
                        isDirectory = dto.isDirectory,
                        ownerUsername = dto.ownerUsername,
                        canRead = dto.canRead,
                        canWrite = dto.canWrite,
                        canDelete = dto.canDelete,
                        canShare = dto.canShare,
                        sharedAt = dto.sharedAt,
                        isExpired = dto.isExpired
                    )
                }
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
