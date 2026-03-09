package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

data class SmartStatusResponseDto(
    @SerializedName("checked_at") val checkedAt: String,
    val devices: List<SmartDeviceDto>
)

data class SmartDeviceDto(
    val name: String,
    val model: String?,
    val serial: String?,
    val temperature: Int?,
    val status: String,
    @SerializedName("capacity_bytes") val capacityBytes: Long?,
    @SerializedName("used_bytes") val usedBytes: Long?,
    @SerializedName("used_percent") val usedPercent: Double?,
    @SerializedName("mount_point") val mountPoint: String?,
    @SerializedName("raid_member_of") val raidMemberOf: String?,
    @SerializedName("last_self_test") val lastSelfTest: SmartSelfTestDto?,
    val attributes: List<SmartAttributeDto>?
)

data class SmartSelfTestDto(
    @SerializedName("test_type") val testType: String?,
    val status: String?,
    val passed: Boolean?,
    @SerializedName("power_on_hours") val powerOnHours: Int?
)

data class SmartAttributeDto(
    val id: Int,
    val name: String,
    val value: Int,
    val worst: Int,
    val threshold: Int,
    val raw: String?,
    val status: String?
)
