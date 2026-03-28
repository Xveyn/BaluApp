package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.EnergyDashboardResponseDto
import com.baluhost.android.data.remote.dto.EnergyPeriodStatsDto
import com.baluhost.android.data.remote.dto.SmartDeviceListResponseDto
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * API interface for energy monitoring and smart device endpoints.
 */
interface EnergyApi {

    @GET("smart-devices/")
    suspend fun getSmartDevices(): SmartDeviceListResponseDto

    @GET("energy/stats/{device_id}/today")
    suspend fun getTodayStats(@Path("device_id") deviceId: Int): EnergyPeriodStatsDto?

    @GET("energy/stats/{device_id}/month")
    suspend fun getMonthStats(@Path("device_id") deviceId: Int): EnergyPeriodStatsDto?

    @GET("energy/dashboard/{device_id}")
    suspend fun getEnergyDashboard(@Path("device_id") deviceId: Int): EnergyDashboardResponseDto
}
