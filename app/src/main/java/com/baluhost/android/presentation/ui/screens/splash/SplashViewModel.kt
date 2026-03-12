package com.baluhost.android.presentation.ui.screens.splash

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.remote.api.MobileApi
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * ViewModel for Splash Screen.
 * 
 * Checks if user is already authenticated.
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val mobileApi: MobileApi
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<SplashState>(SplashState.Loading)
    val uiState: StateFlow<SplashState> = _uiState.asStateFlow()
    
    init {
        checkAuthentication()
    }
    
    private fun checkAuthentication() {
        viewModelScope.launch {
            delay(1000) // Show splash for minimum 1 second
            
            // Check authentication FIRST - both token AND device ID must exist
            val accessToken = preferencesManager.getAccessToken().first()
            val deviceId = preferencesManager.getDeviceId().first()
            
            // If not authenticated, check if onboarding is needed
            if (accessToken == null || deviceId == null) {
                // Not authenticated - check if onboarding is completed
                val onboardingCompleted = preferencesManager.isOnboardingCompleted().first()
                
                _uiState.value = if (!onboardingCompleted) {
                    SplashState.OnboardingNeeded
                } else {
                    SplashState.NotAuthenticated
                }
            } else {
                // User has credentials - they are authenticated
                _uiState.value = SplashState.Authenticated

                // Sync FCM push token to backend (fire-and-forget)
                syncFcmToken(deviceId)
            }
        }
    }

    private fun syncFcmToken(deviceId: String) {
        // Use independent scope — viewModelScope gets cancelled when Splash navigates away
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                preferencesManager.saveFcmToken(token)
                mobileApi.registerPushToken(deviceId, token)
                Log.d("SplashVM", "FCM token synced to backend")
            } catch (e: Exception) {
                Log.w("SplashVM", "FCM token sync failed (will retry next launch)", e)
            }
        }
    }
}

sealed class SplashState {
    object Loading : SplashState()
    object OnboardingNeeded : SplashState()
    object Authenticated : SplashState()
    object NotAuthenticated : SplashState()
}
