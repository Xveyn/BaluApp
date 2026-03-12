package com.baluhost.android.presentation.ui.screens.notifications

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.notification.NotificationWebSocketManager
import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.model.NotificationCategory
import com.baluhost.android.domain.model.toDomain
import com.baluhost.android.domain.usecase.notification.*
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val getNotificationsUseCase: GetNotificationsUseCase,
    private val markNotificationReadUseCase: MarkNotificationReadUseCase,
    private val markAllReadUseCase: MarkAllReadUseCase,
    private val dismissNotificationUseCase: DismissNotificationUseCase,
    private val snoozeNotificationUseCase: SnoozeNotificationUseCase,
    private val webSocketManager: NotificationWebSocketManager
) : ViewModel() {

    data class UiState(
        val notifications: List<AppNotification> = emptyList(),
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val selectedCategory: NotificationCategory? = null,
        val unreadOnly: Boolean = false,
        val totalCount: Int = 0,
        val currentPage: Int = 1,
        val hasMore: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val unreadCount: StateFlow<Int> = webSocketManager.unreadCount

    init {
        loadNotifications()
        observeWebSocket()
    }

    fun loadNotifications(refresh: Boolean = false) {
        viewModelScope.launch {
            val state = _uiState.value
            if (refresh) {
                _uiState.value = state.copy(isRefreshing = true, currentPage = 1)
            } else if (state.isLoading) return@launch
            else {
                _uiState.value = state.copy(isLoading = true)
            }

            val categoryStr = state.selectedCategory?.name?.lowercase()
            val page = if (refresh) 1 else state.currentPage

            when (val result = getNotificationsUseCase(
                unreadOnly = state.unreadOnly,
                category = categoryStr,
                page = page,
                pageSize = 20
            )) {
                is Result.Success -> {
                    val mapped = result.data.notifications.map { it.toDomain() }
                    val allNotifications = if (page == 1) mapped
                    else state.notifications + mapped
                    _uiState.value = state.copy(
                        notifications = allNotifications,
                        isLoading = false,
                        isRefreshing = false,
                        error = null,
                        totalCount = result.data.total,
                        currentPage = page,
                        hasMore = allNotifications.size < result.data.total
                    )
                }
                is Result.Error -> {
                    _uiState.value = state.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = "Benachrichtigungen konnten nicht geladen werden"
                    )
                    Log.e("NotificationsVM", "Load failed", result.exception)
                }
                is Result.Loading -> {}
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoading) return
        _uiState.value = state.copy(currentPage = state.currentPage + 1)
        loadNotifications()
    }

    fun refresh() = loadNotifications(refresh = true)

    fun setCategory(category: NotificationCategory?) {
        _uiState.value = _uiState.value.copy(
            selectedCategory = category,
            currentPage = 1,
            notifications = emptyList()
        )
        loadNotifications(refresh = true)
    }

    fun toggleUnreadOnly() {
        _uiState.value = _uiState.value.copy(
            unreadOnly = !_uiState.value.unreadOnly,
            currentPage = 1,
            notifications = emptyList()
        )
        loadNotifications(refresh = true)
    }

    fun markAsRead(id: Int) {
        viewModelScope.launch {
            webSocketManager.sendMarkRead(id)
            when (val result = markNotificationReadUseCase(id)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        notifications = _uiState.value.notifications.map {
                            if (it.id == id) it.copy(isRead = true) else it
                        }
                    )
                }
                is Result.Error -> Log.e("NotificationsVM", "Mark read failed", result.exception)
                is Result.Loading -> {}
            }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            val categoryStr = _uiState.value.selectedCategory?.name?.lowercase()
            when (markAllReadUseCase(categoryStr)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        notifications = _uiState.value.notifications.map {
                            it.copy(isRead = true)
                        }
                    )
                }
                is Result.Error -> {}
                is Result.Loading -> {}
            }
        }
    }

    fun dismiss(id: Int) {
        viewModelScope.launch {
            when (dismissNotificationUseCase(id)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        notifications = _uiState.value.notifications.filter { it.id != id }
                    )
                }
                is Result.Error -> {}
                is Result.Loading -> {}
            }
        }
    }

    fun snooze(id: Int, hours: Int = 1) {
        viewModelScope.launch {
            when (snoozeNotificationUseCase(id, hours)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        notifications = _uiState.value.notifications.filter { it.id != id }
                    )
                }
                is Result.Error -> {}
                is Result.Loading -> {}
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun observeWebSocket() {
        viewModelScope.launch {
            webSocketManager.latestNotification.collect { dto ->
                val notification = dto.toDomain()
                _uiState.value = _uiState.value.copy(
                    notifications = listOf(notification) + _uiState.value.notifications,
                    totalCount = _uiState.value.totalCount + 1
                )
            }
        }
    }
}
