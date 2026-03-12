package com.baluhost.android.domain.usecase.shares

import com.baluhost.android.data.remote.api.SharesApi
import com.baluhost.android.data.remote.dto.ShareableUserDto
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetShareableUsersUseCase @Inject constructor(
    private val sharesApi: SharesApi
) {
    suspend operator fun invoke(): Result<List<ShareableUserDto>> {
        return try {
            val users = sharesApi.getShareableUsers()
            Result.Success(users)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
