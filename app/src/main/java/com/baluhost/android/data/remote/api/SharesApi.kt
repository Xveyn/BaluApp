package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.FileShareCreateDto
import com.baluhost.android.data.remote.dto.FileShareResponseDto
import com.baluhost.android.data.remote.dto.ShareStatisticsDto
import com.baluhost.android.data.remote.dto.ShareableUserDto
import com.baluhost.android.data.remote.dto.SharedWithMeResponseDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface SharesApi {
    @GET("shares/statistics")
    suspend fun getShareStatistics(): ShareStatisticsDto

    @GET("shares/user-shares")
    suspend fun getMyShares(): List<FileShareResponseDto>

    @GET("shares/shared-with-me")
    suspend fun getSharedWithMe(): List<SharedWithMeResponseDto>

    @GET("shares/users")
    suspend fun getShareableUsers(): List<ShareableUserDto>

    @POST("shares/user-shares")
    suspend fun createShare(@Body request: FileShareCreateDto): FileShareResponseDto

    @DELETE("shares/user-shares/{shareId}")
    suspend fun deleteShare(@Path("shareId") shareId: Int)
}
