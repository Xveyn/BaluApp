package com.baluhost.android.domain.repository

import com.baluhost.android.domain.model.NasStatusResult
import com.baluhost.android.domain.model.PowerPermissions
import com.baluhost.android.util.Result

interface PowerRepository {
    suspend fun sendWol(): Result<String>
    suspend fun sendSoftSleep(): Result<String>
    suspend fun sendSuspend(): Result<String>
    suspend fun sendWake(): Result<String>
    suspend fun checkNasStatus(): NasStatusResult
    suspend fun getMyPermissions(): Result<PowerPermissions>
}
