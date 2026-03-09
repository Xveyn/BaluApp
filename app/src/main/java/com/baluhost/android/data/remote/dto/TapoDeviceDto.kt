package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TapoDeviceDto(
    @SerializedName("id")
    val id: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("device_type")
    val deviceType: String,
    @SerializedName("is_active")
    val isActive: Boolean,
    @SerializedName("is_monitoring")
    val isMonitoring: Boolean
)
