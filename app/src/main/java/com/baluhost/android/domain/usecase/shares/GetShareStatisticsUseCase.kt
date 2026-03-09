package com.baluhost.android.domain.usecase.shares

import com.baluhost.android.data.remote.api.SharesApi
import com.baluhost.android.domain.model.ShareStatistics
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetShareStatisticsUseCase @Inject constructor(
    private val sharesApi: SharesApi
) {
    suspend operator fun invoke(): Result<ShareStatistics> {
        return try {
            val response = sharesApi.getShareStatistics()
            Result.Success(
                ShareStatistics(
                    totalShares = response.totalFileShares,
                    activeShares = response.activeFileShares,
                    sharedWithMe = response.filesSharedWithMe
                )
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
