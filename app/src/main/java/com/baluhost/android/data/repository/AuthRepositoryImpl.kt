package com.baluhost.android.data.repository

import com.baluhost.android.data.remote.api.AuthApi
import com.baluhost.android.data.remote.dto.LoginRequest
import com.baluhost.android.data.remote.dto.LoginResponse
import com.baluhost.android.data.remote.dto.RefreshTokenRequest
import com.baluhost.android.data.remote.dto.RefreshTokenResponse
import com.baluhost.android.domain.repository.AuthRepository
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi
) : AuthRepository {

    override suspend fun login(username: String, password: String): Result<LoginResponse> {
        return try {
            val response = authApi.login(LoginRequest(username, password))
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refreshToken(refreshToken: String): Result<RefreshTokenResponse> {
        return try {
            val response = authApi.refreshToken(RefreshTokenRequest(refreshToken))
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
