package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.CpuHistoryResponseDto
import com.baluhost.android.data.remote.dto.MemoryHistoryResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

interface MonitoringApi {

    @GET("monitoring/cpu/history")
    suspend fun getCpuHistory(
        @Query("time_range") timeRange: String = "1h"
    ): CpuHistoryResponseDto

    @GET("monitoring/memory/history")
    suspend fun getMemoryHistory(
        @Query("time_range") timeRange: String = "1h"
    ): MemoryHistoryResponseDto
}
