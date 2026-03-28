package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.PluginConfigResponseDto
import com.baluhost.android.data.remote.dto.PluginConfigUpdateRequestDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

interface PluginApi {

    @GET("plugins/tapo_smart_plug/config")
    suspend fun getTapoPluginConfig(): PluginConfigResponseDto

    @PUT("plugins/tapo_smart_plug/config")
    suspend fun updateTapoPluginConfig(
        @Body request: PluginConfigUpdateRequestDto
    ): PluginConfigResponseDto
}
