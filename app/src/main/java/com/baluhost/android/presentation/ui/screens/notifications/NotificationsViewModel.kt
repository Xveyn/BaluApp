package com.baluhost.android.presentation.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.model.NotificationCategory
import com.baluhost.android.domain.model.NotificationType
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.domain.usecase.notification.GetNotificationPreferencesUseCase
import com.baluhost.android.domain.usecase.notification.ObserveNotificationsUseCase
import com.baluhost.android.domain.usecase.notification.ObserveUnreadCountUseCase
import com.baluhost.android.domain.usecase.notification.SyncNotificationsUseCase
import com.baluhost.android.util.NetworkStateManager
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the notifications screen from the local cache (see [ObserveNotificationsUseCase])
 * instead of one-shot network calls, so the list survives without a server connection.
 * [SyncNotificationsUseCase] reconciles that cache with the server on four triggers:
 * the screen opening (this ViewModel's [init]), app start ([com.baluhost.android.BaluHostApplication]),
 * connectivity regained ([observeConnectivity]), and after a push
 * ([com.baluhost.android.data.notification.PushNotificationStore]).
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val observeNotificationsUseCase: ObserveNotificationsUseCase,
    private val syncNotificationsUseCase: SyncNotificationsUseCase,
    private val observeUnreadCountUseCase: ObserveUnreadCountUseCase,
    private val getNotificationPreferencesUseCase: GetNotificationPreferencesUseCase,
    private val notificationRepository: NotificationRepository,
    private val preferencesManager: PreferencesManager,
    private val networkStateManager: NetworkStateManager
) : ViewModel() {

    enum class Tab { INBOX, TRASH }

    data class UiState(
        val notifications: List<AppNotification> = emptyList(),
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val selectedCategory: NotificationCategory? = null,
        val typeFilter: NotificationType? = null,
        val unreadOnly: Boolean = false,
        // No server-side pagination anymore: the cache flow always delivers the
        // full (filtered) list, so this stays false and loadMore() is a no-op.
        val hasMore: Boolean = false,
        val tab: Tab = Tab.INBOX,
        val isOffline: Boolean = false,
        val retentionDays: Int = 7
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // One-shot failures for actions the user explicitly triggered (restore, delete
    // permanently, empty trash, dismiss all) - a background sync failure is not one
    // of these, see refresh().
    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    val unreadCount: StateFlow<Int> = observeUnreadCountUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _tab = MutableStateFlow(Tab.INBOX)
    private val _selectedCategory = MutableStateFlow<NotificationCategory?>(null)
    private val _typeFilter = MutableStateFlow<NotificationType?>(null)
    private val _unreadOnly = MutableStateFlow(false)

    init {
        observeFilteredNotifications()
        observeConnectivity()
        loadRetentionDays()
        refresh()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFilteredNotifications() {
        viewModelScope.launch {
            combine(_tab, _selectedCategory, _typeFilter, _unreadOnly) { tab, category, type, unreadOnly ->
                FilterState(tab, category, type, unreadOnly)
            }
                .distinctUntilChanged()
                .flatMapLatest { filter ->
                    observeNotificationsUseCase(trashed = filter.tab == Tab.TRASH).map { list ->
                        list.filter { notification ->
                            (filter.category == null ||
                                notification.rawCategory.equals(filter.category.name, ignoreCase = true)) &&
                                (filter.type == null || notification.type == filter.type) &&
                                (!filter.unreadOnly || !notification.isRead)
                        }
                    }
                }
                .collect { filtered ->
                    _uiState.update { it.copy(notifications = filtered) }
                }
        }
    }

    /**
     * Connectivity-regained trigger. Follows the same NetworkStateManager API
     * DashboardViewModel already uses (observeHomeNetworkState()): the server only
     * matters for this app when the device is on the home network or on VPN, so
     * that combined signal - not raw internet reachability - is what "regained
     * connectivity" means here.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeConnectivity() {
        viewModelScope.launch {
            preferencesManager.getServerUrl()
                .flatMapLatest { serverUrl ->
                    if (serverUrl == null) flowOf(null) else networkStateManager.observeHomeNetworkStatus(serverUrl)
                }
                .distinctUntilChanged()
                .collect { isHome ->
                    if (isHome == true) refresh()
                }
        }
    }

    private fun loadRetentionDays() {
        viewModelScope.launch {
            when (val result = getNotificationPreferencesUseCase()) {
                is Result.Success -> _uiState.update { it.copy(retentionDays = result.data.trashRetentionDays) }
                is Result.Error -> Unit // keep the UiState default of 7
                is Result.Loading -> Unit
            }
        }
    }

    /**
     * Reconciles the cache with the server. A failure here is a discovery
     * operation, not something the user asked for: it flips [UiState.isOffline]
     * and leaves the cached list on screen, without surfacing an error.
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            when (syncNotificationsUseCase()) {
                is Result.Success -> _uiState.update { it.copy(isRefreshing = false, isOffline = false) }
                is Result.Error -> _uiState.update { it.copy(isRefreshing = false, isOffline = true) }
                is Result.Loading -> _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /** No-op: the observed cache flow always delivers the full list, kept only so the screen still compiles. */
    fun loadMore() = Unit

    fun setTab(tab: Tab) {
        _tab.value = tab
        _uiState.update { it.copy(tab = tab) }
    }

    fun setCategory(category: NotificationCategory?) {
        _selectedCategory.value = category
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun setTypeFilter(type: NotificationType?) {
        _typeFilter.value = type
        _uiState.update { it.copy(typeFilter = type) }
    }

    fun toggleUnreadOnly() {
        val next = !_uiState.value.unreadOnly
        _unreadOnly.value = next
        _uiState.update { it.copy(unreadOnly = next) }
    }

    fun markAsRead(id: Int) {
        viewModelScope.launch {
            val ownerUserId = preferencesManager.getUserId().first() ?: return@launch
            notificationRepository.markReadLocally(ownerUserId, id)
            refresh() // best-effort push; a failed push just leaves isOffline = true
        }
    }

    fun dismiss(id: Int) {
        viewModelScope.launch {
            val ownerUserId = preferencesManager.getUserId().first() ?: return@launch
            notificationRepository.dismissLocally(ownerUserId, id)
            refresh()
        }
    }

    fun snooze(id: Int, hours: Int = 1) {
        viewModelScope.launch {
            val ownerUserId = preferencesManager.getUserId().first() ?: return@launch
            notificationRepository.snoozeLocally(ownerUserId, id, hours)
            refresh()
        }
    }

    /** Marks every notification currently shown (respecting the active filters) as read. */
    fun markAllAsRead() {
        viewModelScope.launch {
            val ownerUserId = preferencesManager.getUserId().first() ?: return@launch
            _uiState.value.notifications.filterNot { it.isRead }.forEach {
                notificationRepository.markReadLocally(ownerUserId, it.id)
            }
            refresh()
        }
    }

    /**
     * Dismisses every notification currently shown in the inbox. There is no
     * bulk-dismiss entry on [NotificationRepository]'s local-write API, so this
     * loops the single-item [NotificationRepository.dismissLocally] the same way
     * [markAllAsRead] loops [NotificationRepository.markReadLocally] - user
     * triggered, so a failed push is reported via [snackbarEvent].
     */
    fun dismissAll() {
        viewModelScope.launch {
            val ownerUserId = preferencesManager.getUserId().first() ?: return@launch
            _uiState.value.notifications.forEach {
                notificationRepository.dismissLocally(ownerUserId, it.id)
            }
            pushAndReport("Verwerfen fehlgeschlagen")
        }
    }

    /** Restores a notification from the trash. User triggered, so a failed push is reported. */
    fun restore(id: Int) {
        viewModelScope.launch {
            val ownerUserId = preferencesManager.getUserId().first() ?: return@launch
            notificationRepository.restoreLocally(ownerUserId, id)
            pushAndReport("Wiederherstellen fehlgeschlagen")
        }
    }

    fun deletePermanently(id: Int) {
        viewModelScope.launch {
            val ownerUserId = preferencesManager.getUserId().first() ?: return@launch
            notificationRepository.deletePermanently(ownerUserId, id).fold(
                onSuccess = { _uiState.update { it.copy(isOffline = false) } },
                onFailure = { _snackbarEvent.emit("Endgültig löschen fehlgeschlagen") }
            )
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val ownerUserId = preferencesManager.getUserId().first() ?: return@launch
            notificationRepository.emptyTrash(ownerUserId).fold(
                onSuccess = { _uiState.update { it.copy(isOffline = false) } },
                onFailure = { _snackbarEvent.emit("Papierkorb leeren fehlgeschlagen") }
            )
        }
    }

    /** Pushes a pending local intent to the server and reports failure - for user-triggered actions only. */
    private suspend fun pushAndReport(errorMessage: String) {
        when (syncNotificationsUseCase()) {
            is Result.Success -> _uiState.update { it.copy(isOffline = false) }
            is Result.Error -> {
                _uiState.update { it.copy(isOffline = true) }
                _snackbarEvent.emit(errorMessage)
            }
            is Result.Loading -> Unit
        }
    }

    private data class FilterState(
        val tab: Tab,
        val category: NotificationCategory?,
        val type: NotificationType?,
        val unreadOnly: Boolean
    )
}
