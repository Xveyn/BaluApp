package com.baluhost.android.presentation.ui.screens.shares

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.domain.model.FileShareInfo
import com.baluhost.android.domain.model.SharedWithMeInfo
import com.baluhost.android.domain.usecase.shares.DeleteShareUseCase
import com.baluhost.android.domain.usecase.shares.GetMySharesUseCase
import com.baluhost.android.domain.usecase.shares.GetSharedWithMeUseCase
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SharesViewModel @Inject constructor(
    private val getMySharesUseCase: GetMySharesUseCase,
    private val getSharedWithMeUseCase: GetSharedWithMeUseCase,
    private val deleteShareUseCase: DeleteShareUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharesUiState())
    val uiState: StateFlow<SharesUiState> = _uiState.asStateFlow()

    init {
        loadShares()
    }

    fun loadShares() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val mySharesResult = getMySharesUseCase()
                val sharedWithMeResult = getSharedWithMeUseCase()

                val myShares = when (mySharesResult) {
                    is Result.Success -> mySharesResult.data
                    is Result.Error -> {
                        Log.e("SharesViewModel", "Failed to load my shares", mySharesResult.exception)
                        emptyList()
                    }
                    else -> emptyList()
                }

                val sharedWithMe = when (sharedWithMeResult) {
                    is Result.Success -> sharedWithMeResult.data
                    is Result.Error -> {
                        Log.e("SharesViewModel", "Failed to load shared with me", sharedWithMeResult.exception)
                        emptyList()
                    }
                    else -> emptyList()
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    myShares = myShares,
                    sharedWithMe = sharedWithMe
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load shares: ${e.message}"
                )
            }
        }
    }

    fun deleteShare(shareId: Int) {
        viewModelScope.launch {
            try {
                val result = deleteShareUseCase(shareId)
                if (result is Result.Success) {
                    loadShares()
                } else if (result is Result.Error) {
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to revoke share: ${result.exception.message}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to revoke share: ${e.message}"
                )
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

data class SharesUiState(
    val isLoading: Boolean = false,
    val myShares: List<FileShareInfo> = emptyList(),
    val sharedWithMe: List<SharedWithMeInfo> = emptyList(),
    val error: String? = null
)
