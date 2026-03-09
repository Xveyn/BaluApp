package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

data class EnergyDashboardResponseDto(
    @SerializedName("device_id")
    val deviceId: Int,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("today")
    val today: EnergyPeriodStatsDto?,
    @SerializedName("week")
    val week: EnergyPeriodStatsDto?,
    @SerializedName("month")
    val month: EnergyPeriodStatsDto?,
    @SerializedName("hourly_samples")
    val hourlySamples: List<HourlySampleDto>,
    @SerializedName("current_watts")
    val currentWatts: Double,
    @SerializedName("is_online")
    val isOnline: Boolean
)

data class HourlySampleDto(
    @SerializedName("timestamp")
    val timestamp: String,
    @SerializedName("avg_watts")
    val avgWatts: Double,
    @SerializedName("sample_count")
    val sampleCount: Int
)
