package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.CpuHistoryResponseDto
import com.baluhost.android.data.remote.dto.CurrentUptimeResponseDto
import com.baluhost.android.data.remote.dto.MemoryHistoryResponseDto
import com.baluhost.android.data.remote.dto.UptimeHistoryResponseDto
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

    @GET("monitoring/uptime/current")
    suspend fun getUptimeCurrent(): CurrentUptimeResponseDto

    @GET("monitoring/uptime/history")
    suspend fun getUptimeHistory(
        @Query("time_range") timeRange: String = "1h",
        @Query("source") source: String = "auto",
        @Query("limit") limit: Int = 1000
    ): UptimeHistoryResponseDto
}
