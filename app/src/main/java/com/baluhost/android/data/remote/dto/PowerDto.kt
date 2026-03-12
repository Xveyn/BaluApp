package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTOs for mobile power summary endpoint.
 * Matches backend MobilePowerSummary schema.
 */

data class PowerSummaryDto(
    @SerializedName("total_current_watts")
    val totalCurrentWatts: Double = 0.0,
    @SerializedName("devices_online")
    val devicesOnline: Int = 0,
    @SerializedName("devices_total")
    val devicesTotal: Int = 0,
    @SerializedName("devices")
    val devices: List<TapoDevicePowerDto> = emptyList(),
    @SerializedName("today_energy_kwh")
    val todayEnergyKwh: Double? = null,
    @SerializedName("today_avg_watts")
    val todayAvgWatts: Double? = null,
    @SerializedName("today_max_watts")
    val todayMaxWatts: Double? = null,
    @SerializedName("estimated_cost_today")
    val estimatedCostToday: Double? = null,
    @SerializedName("cost_per_kwh")
    val costPerKwh: Double? = null,
    @SerializedName("currency")
    val currency: String? = null,
    @SerializedName("power_profile")
    val powerProfile: String? = null,
    @SerializedName("power_profile_frequency_mhz")
    val powerProfileFrequencyMhz: Int? = null,
    @SerializedName("auto_scaling_enabled")
    val autoScalingEnabled: Boolean = false,
    @SerializedName("active_demands_count")
    val activeDemandsCount: Int = 0,
    @SerializedName("has_tapo_devices")
    val hasTapoDevices: Boolean = false,
    @SerializedName("timestamp")
    val timestamp: String = ""
)

data class TapoDevicePowerDto(
    @SerializedName("device_id")
    val deviceId: Int,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("current_watts")
    val currentWatts: Double = 0.0,
    @SerializedName("is_online")
    val isOnline: Boolean = false,
    @SerializedName("energy_today_kwh")
    val energyTodayKwh: Double? = null
)
