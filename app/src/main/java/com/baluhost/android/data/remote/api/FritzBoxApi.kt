package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.PowerActionResponse
import retrofit2.http.POST

interface FritzBoxApi {

    @POST("fritzbox/wol")
    suspend fun sendWol(): PowerActionResponse
}
