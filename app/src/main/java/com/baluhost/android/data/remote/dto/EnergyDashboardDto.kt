package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

data class EnergyPeriodStatsDto(
    @SerializedName("device_id")
    val deviceId: Int,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("samples_count")
    val samplesCount: Int,
    @SerializedName("avg_watts")
    val avgWatts: Double,
    @SerializedName("min_watts")
    val minWatts: Double,
    @SerializedName("max_watts")
    val maxWatts: Double,
    @SerializedName("total_energy_kwh")
    val totalEnergyKwh: Double,
    @SerializedName("uptime_percentage")
    val uptimePercentage: Double,
    @SerializedName("downtime_minutes")
    val downtimeMinutes: Int
)
