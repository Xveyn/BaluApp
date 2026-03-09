package com.baluhost.android.domain.usecase.shares

import com.baluhost.android.data.remote.api.SharesApi
import com.baluhost.android.util.Result
import javax.inject.Inject

class DeleteShareUseCase @Inject constructor(
    private val sharesApi: SharesApi
) {
    suspend operator fun invoke(shareId: Int): Result<Unit> {
        return try {
            sharesApi.deleteShare(shareId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
