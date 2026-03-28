package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TapoPluginConfigDto(
    @SerializedName("panel_devices")
    val panelDevices: List<Int>
)

data class PluginConfigResponseDto(
    @SerializedName("name")
    val name: String,
    @SerializedName("config")
    val config: TapoPluginConfigDto
)

data class PluginConfigUpdateRequestDto(
    @SerializedName("config")
    val config: TapoPluginConfigDto
)
