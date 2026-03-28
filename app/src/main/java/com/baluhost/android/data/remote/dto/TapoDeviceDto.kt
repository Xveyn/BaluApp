package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

data class IoTDeviceDto(
    @SerializedName("id")
    val id: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("plugin_name")
    val pluginName: String,
    @SerializedName("device_type_id")
    val deviceTypeId: String,
    @SerializedName("capabilities")
    val capabilities: List<String>,
    @SerializedName("is_active")
    val isActive: Boolean,
    @SerializedName("is_online")
    val isOnline: Boolean
)

data class SmartDeviceListResponseDto(
    @SerializedName("devices")
    val devices: List<IoTDeviceDto>,
    @SerializedName("total")
    val total: Int
)
