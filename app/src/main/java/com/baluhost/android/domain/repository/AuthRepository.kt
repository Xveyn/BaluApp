package com.baluhost.android.domain.repository

import com.baluhost.android.data.remote.dto.LoginResponse
import com.baluhost.android.data.remote.dto.RefreshTokenResponse

interface AuthRepository {
    suspend fun login(username: String, password: String): Result<LoginResponse>
    suspend fun refreshToken(refreshToken: String): Result<RefreshTokenResponse>
}
