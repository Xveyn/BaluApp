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
    val devices: List<DevicePowerDto> = emptyList(),
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
    @SerializedName("has_smart_devices")
    val hasSmartDevices: Boolean = false,
    @SerializedName("timestamp")
    val timestamp: String = ""
)

data class DevicePowerDto(
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

data class PowerActionResponse(
    val success: Boolean,
    val message: String
)

data class MyPowerPermissionsDto(
    @SerializedName("can_soft_sleep")
    val canSoftSleep: Boolean = false,
    @SerializedName("can_wake")
    val canWake: Boolean = false,
    @SerializedName("can_suspend")
    val canSuspend: Boolean = false,
    @SerializedName("can_wol")
    val canWol: Boolean = false,
    @SerializedName("can_toggle_desktop")
    val canToggleDesktop: Boolean = false,
    @SerializedName("can_unlock_session")
    val canUnlockSession: Boolean = false
)

data class DesktopStatusDto(
    @SerializedName("state")
    val state: String = "unknown",
    @SerializedName("display_manager")
    val displayManager: String = "",
    @SerializedName("detail")
    val detail: String? = null
)

/**
 * Covers both desktop routes. The disable route answers with success/message
 * only, so the two unlock fields stay null there.
 */
data class DesktopActionResponseDto(
    @SerializedName("success")
    val success: Boolean = false,
    @SerializedName("message")
    val message: String = "",
    @SerializedName("session_unlocked")
    val sessionUnlocked: Boolean? = null,
    @SerializedName("unlock_message")
    val unlockMessage: String? = null
)
