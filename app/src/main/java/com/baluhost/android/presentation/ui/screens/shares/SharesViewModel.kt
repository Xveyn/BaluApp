package com.baluhost.android.presentation.ui.screens.shares

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.remote.api.FilesApi
import com.baluhost.android.data.remote.dto.FileItemDto
import com.baluhost.android.data.remote.dto.ShareableUserDto
import com.baluhost.android.domain.model.FileShareInfo
import com.baluhost.android.domain.model.SharedWithMeInfo
import com.baluhost.android.domain.usecase.shares.CreateShareUseCase
import com.baluhost.android.domain.usecase.shares.DeleteShareUseCase
import com.baluhost.android.domain.usecase.shares.GetMySharesUseCase
import com.baluhost.android.domain.usecase.shares.GetShareableUsersUseCase
import com.baluhost.android.domain.usecase.shares.GetSharedWithMeUseCase
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

@HiltViewModel
class SharesViewModel @Inject constructor(
    private val getMySharesUseCase: GetMySharesUseCase,
    private val getSharedWithMeUseCase: GetSharedWithMeUseCase,
    private val deleteShareUseCase: DeleteShareUseCase,
    private val createShareUseCase: CreateShareUseCase,
    private val getShareableUsersUseCase: GetShareableUsersUseCase,
    private val filesApi: FilesApi
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

    // ── Create Share Dialog ──

    fun showCreateShareDialog() {
        _uiState.value = _uiState.value.copy(showCreateDialog = true)
        loadShareableUsers()
        loadBrowserFiles("")
    }

    fun dismissCreateShareDialog() {
        _uiState.value = _uiState.value.copy(
            showCreateDialog = false,
            shareableUsers = emptyList(),
            browserFiles = emptyList(),
            browserPath = "",
            selectedFile = null,
            selectedUser = null,
            canRead = true,
            canWrite = false,
            canDelete = false,
            canShare = false,
            expiresAt = null,
            isLoadingUsers = false,
            isLoadingFiles = false,
            isCreating = false,
            createError = null
        )
    }

    private fun loadShareableUsers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingUsers = true)
            when (val result = getShareableUsersUseCase()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        shareableUsers = result.data,
                        isLoadingUsers = false
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoadingUsers = false,
                        createError = "Failed to load users: ${result.exception.message}"
                    )
                }
                else -> {
                    _uiState.value = _uiState.value.copy(isLoadingUsers = false)
                }
            }
        }
    }

    fun loadBrowserFiles(path: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingFiles = true)
            try {
                val response = filesApi.listFiles(path.ifEmpty { "/" })
                _uiState.value = _uiState.value.copy(
                    browserFiles = response.files,
                    browserPath = path,
                    isLoadingFiles = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingFiles = false,
                    createError = "Failed to load files: ${e.message}"
                )
            }
        }
    }

    fun navigateToFolder(path: String) {
        loadBrowserFiles(path)
    }

    fun navigateUp() {
        val current = _uiState.value.browserPath
        val parent = if (current.contains("/")) {
            current.substringBeforeLast("/")
        } else {
            ""
        }
        loadBrowserFiles(parent)
    }

    fun selectFile(file: FileItemDto) {
        if (file.fileId != null) {
            _uiState.value = _uiState.value.copy(selectedFile = file)
        }
    }

    fun selectUser(user: ShareableUserDto) {
        _uiState.value = _uiState.value.copy(selectedUser = user)
    }

    fun setCanRead(value: Boolean) {
        _uiState.value = _uiState.value.copy(canRead = value)
    }

    fun setCanWrite(value: Boolean) {
        _uiState.value = _uiState.value.copy(canWrite = value)
    }

    fun setCanDelete(value: Boolean) {
        _uiState.value = _uiState.value.copy(canDelete = value)
    }

    fun setCanShare(value: Boolean) {
        _uiState.value = _uiState.value.copy(canShare = value)
    }

    fun setExpiresAt(millis: Long?) {
        _uiState.value = _uiState.value.copy(expiresAt = millis)
    }

    fun createShare() {
        val state = _uiState.value
        val file = state.selectedFile ?: return
        val user = state.selectedUser ?: return
        val fileId = file.fileId ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true, createError = null)

            val expiresAtStr = state.expiresAt?.let { millis ->
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.format(Date(millis))
            }

            when (val result = createShareUseCase(
                fileId = fileId,
                sharedWithUserId = user.id,
                canRead = state.canRead,
                canWrite = state.canWrite,
                canDelete = state.canDelete,
                canShare = state.canShare,
                expiresAt = expiresAtStr
            )) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isCreating = false,
                        createSuccess = true
                    )
                    dismissCreateShareDialog()
                    loadShares()
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isCreating = false,
                        createError = "Failed to create share: ${result.exception.message}"
                    )
                }
                else -> {
                    _uiState.value = _uiState.value.copy(isCreating = false)
                }
            }
        }
    }

    fun dismissCreateSuccess() {
        _uiState.value = _uiState.value.copy(createSuccess = false)
    }
}

data class SharesUiState(
    val isLoading: Boolean = false,
    val myShares: List<FileShareInfo> = emptyList(),
    val sharedWithMe: List<SharedWithMeInfo> = emptyList(),
    val error: String? = null,
    // Create Share Dialog State
    val showCreateDialog: Boolean = false,
    val shareableUsers: List<ShareableUserDto> = emptyList(),
    val browserFiles: List<FileItemDto> = emptyList(),
    val browserPath: String = "",
    val selectedFile: FileItemDto? = null,
    val selectedUser: ShareableUserDto? = null,
    val canRead: Boolean = true,
    val canWrite: Boolean = false,
    val canDelete: Boolean = false,
    val canShare: Boolean = false,
    val expiresAt: Long? = null,
    val isLoadingUsers: Boolean = false,
    val isLoadingFiles: Boolean = false,
    val isCreating: Boolean = false,
    val createError: String? = null,
    val createSuccess: Boolean = false
)
