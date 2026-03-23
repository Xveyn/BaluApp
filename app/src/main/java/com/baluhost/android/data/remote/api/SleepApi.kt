package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.PowerActionResponse
import retrofit2.http.POST

interface SleepApi {

    @POST("system/sleep/soft")
    suspend fun sendSoftSleep(): PowerActionResponse

    @POST("system/sleep/suspend")
    suspend fun sendSuspend(): PowerActionResponse
}
