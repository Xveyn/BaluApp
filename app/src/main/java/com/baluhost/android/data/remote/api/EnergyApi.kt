package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.CurrentPowerDto
import com.baluhost.android.data.remote.dto.EnergyDashboardResponseDto
import com.baluhost.android.data.remote.dto.EnergyPeriodStatsDto
import com.baluhost.android.data.remote.dto.TapoDeviceDto
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * API interface for energy monitoring endpoints.
 */
interface EnergyApi {

    @GET("tapo/devices")
    suspend fun getTapoDevices(): List<TapoDeviceDto>

    @GET("tapo/power/current/{device_id}")
    suspend fun getCurrentPower(@Path("device_id") deviceId: Int): CurrentPowerDto

    @GET("energy/stats/{device_id}/today")
    suspend fun getTodayStats(@Path("device_id") deviceId: Int): EnergyPeriodStatsDto?

    @GET("energy/stats/{device_id}/month")
    suspend fun getMonthStats(@Path("device_id") deviceId: Int): EnergyPeriodStatsDto?

    @GET("energy/dashboard/{device_id}")
    suspend fun getEnergyDashboard(@Path("device_id") deviceId: Int): EnergyDashboardResponseDto
}
