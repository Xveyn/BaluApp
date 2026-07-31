package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.PluginConfigResponseDto
import com.baluhost.android.data.remote.dto.PluginConfigUpdateRequestDto
import com.baluhost.android.data.remote.dto.PluginMenuActionResultDto
import com.baluhost.android.data.remote.dto.PluginUiManifestDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface PluginApi {

    @GET("plugins/tapo_smart_plug/config")
    suspend fun getTapoPluginConfig(): PluginConfigResponseDto

    @PUT("plugins/tapo_smart_plug/config")
    suspend fun updateTapoPluginConfig(
        @Body request: PluginConfigUpdateRequestDto
    ): PluginConfigResponseDto

    /** Lists the enabled plugins with the UI they contribute. */
    @GET("plugins/ui/manifest")
    suspend fun getUiManifest(): PluginUiManifestDto

    /** Runs a plugin-contributed menu action. Admin only, server-enforced. */
    @POST("plugins/{name}/menu-actions/{actionId}")
    suspend fun runMenuAction(
        @Path("name") name: String,
        @Path("actionId") actionId: String
    ): PluginMenuActionResultDto
}
