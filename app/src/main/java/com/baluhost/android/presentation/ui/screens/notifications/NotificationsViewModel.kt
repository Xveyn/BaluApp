package com.baluhost.android.presentation.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.model.NotificationType
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.domain.usecase.notification.GetNotificationPreferencesUseCase
import com.baluhost.android.domain.usecase.notification.ObserveNotificationsUseCase
import com.baluhost.android.domain.usecase.notification.ObserveUnreadCountUseCase
import com.baluhost.android.domain.usecase.notification.SyncNotificationsUseCase
import com.baluhost.android.util.Clock
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
import java.time.Instant
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
    private val networkStateManager: NetworkStateManager,
    private val clock: Clock
) : ViewModel() {

    enum class Tab { INBOX, TRASH }

    data class UiState(
        val notifications: List<AppNotification> = emptyList(),
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        // Raw server category string, not the closed NotificationCategory enum: the
        // server's category set is open (core categories, "lifecycle", plugin names),
        // and filtering through the enum would make anything outside its 8 values
        // unreachable. See NotificationCategory's doc comment on AppNotification.category
        // (domain/model/Notification.kt) and the corresponding entry in domain/model/CLAUDE.md.
        val selectedCategory: String? = null,
        // Distinct rawCategory values present in the current tab, unfiltered by
        // selectedCategory/typeFilter/unreadOnly - this is what the category chip
        // row is built from, so narrowing by type or unread-only never makes the
        // currently selected category chip (and its filter) disappear out from
        // under the user. See observeFilteredNotifications.
        val availableCategories: List<String> = emptyList(),
        val typeFilter: NotificationType? = null,
        val unreadOnly: Boolean = false,
        // No server-side pagination anymore: the cache flow always delivers the
        // full (filtered) list, so this stays false and loadMore() is a no-op.
        val hasMore: Boolean = false,
        val tab: Tab = Tab.INBOX,
        val isOffline: Boolean = false,
        val retentionDays: Int = 7,
        // True once observeFilteredNotifications has delivered at least one real
        // emission (of either tab, whether that first list is empty or not) since
        // this ViewModel was constructed. Never reset back to false - the ViewModel
        // outlives screen recompositions (rotation, navigating to Preferences and
        // back), so a screen that reads this directly from uiState, instead of
        // tracking its own local "have I seen an update yet" flag, gets the right
        // answer immediately on every recomposition instead of only on the first one.
        val hasLoadedOnce: Boolean = false
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
    private val _selectedCategory = MutableStateFlow<String?>(null)
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
                        val now = clock.now()
                        // Tab-scoped and snooze-resolved, but not yet narrowed by
                        // category/type/unread: availableCategories is deliberately
                        // derived from this list, not from `filtered` below, so the
                        // category chip row doesn't shrink out from under the user
                        // as they narrow by type or unread-only (see UiState.availableCategories).
                        val visible = list.filter { notification ->
                            // A snooze still in effect hides the row from the inbox; the trash
                            // tab is unaffected because every row it observes already has
                            // deletedAt set (see NotificationDao.observe's trashed condition).
                            notification.deletedAt != null || !isSnoozedIntoFuture(notification, now)
                        }
                        val availableCategories = visible.map { it.rawCategory }.distinct().sorted()
                        // A selected category with no matching chip in availableCategories
                        // is treated as if it were unset for this emission - both for what
                        // gets filtered (so the list isn't spuriously empty for a frame) and
                        // for what gets written back below (so it doesn't stay silently
                        // active). This is deliberately general rather than only resetting
                        // on setTab: the same "empty list, no chip selected" symptom is also
                        // reachable without switching tabs at all, e.g. restoring the one
                        // remaining trash row of the selected category. See Fix round 2 in
                        // task-10-report.md for the reasoning against the narrower repair.
                        val effectiveCategory = filter.category?.takeIf { selected ->
                            availableCategories.any { it.equals(selected, ignoreCase = true) }
                        }
                        val filtered = visible.filter { notification ->
                            (effectiveCategory == null ||
                                notification.rawCategory.equals(effectiveCategory, ignoreCase = true)) &&
                                (filter.type == null || notification.type == filter.type) &&
                                (!filter.unreadOnly || !notification.isRead)
                        }
                        FilteredNotifications(
                            filtered = filtered,
                            availableCategories = availableCategories,
                            requestedCategory = filter.category,
                            effectiveCategory = effectiveCategory
                        )
                    }
                }
                .collect { result ->
                    if (result.effectiveCategory != result.requestedCategory) {
                        // Correct the upstream filter, not just this emission's display -
                        // otherwise the stale selection would silently reactivate the
                        // moment the user lands back on a tab/state where it happens to
                        // match again. Feeds back into the combine() above; settles within
                        // one extra (cheap, idempotent) cycle since effectiveCategory ==
                        // requestedCategory afterwards.
                        _selectedCategory.value = result.effectiveCategory
                    }
                    _uiState.update {
                        it.copy(
                            notifications = result.filtered,
                            availableCategories = result.availableCategories,
                            selectedCategory = result.effectiveCategory,
                            // Monotonic: this is "has the cache flow ever delivered a real
                            // list", not "is the current list non-empty" - flips once, on
                            // the very first emission of either tab, and never resets. A
                            // screen reading this straight from uiState (instead of racing
                            // its own drop(1)-on-a-fresh-subscription flag) gets the right
                            // answer immediately even when it recomposes long after this
                            // ViewModel already settled - see the Fix round 1 note on the
                            // Critical finding in task-10-report.md for why that mattered.
                            hasLoadedOnce = true
                        )
                    }
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

    /**
     * No-op. Server-side pagination doesn't exist anymore: [observeFilteredNotifications]'s
     * cache flow always delivers the full (filtered) list for the current tab in one shot,
     * so there is nothing to load more of. Kept as a stable, harmless entry point - deleting
     * it is a separate call from fixing its comment, and nothing here decides that on its own.
     */
    fun loadMore() = Unit

    fun setTab(tab: Tab) {
        _tab.value = tab
        _uiState.update { it.copy(tab = tab) }
    }

    /**
     * @param category Raw server category string (e.g. "raid", "lifecycle", a plugin
     * name), not the closed NotificationCategory enum - see [UiState.selectedCategory].
     */
    fun setCategory(category: String?) {
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

    /**
     * Whether [notification]'s snooze is still in effect at [now]. Filtered here, where
     * the flow is collected, rather than in `NotificationDao`'s query: the DAO is the one
     * part of this feature plain JUnit cannot exercise at all (no `androidTest` source
     * set), so time semantics belong in plain Kotlin the ViewModel test can pin with an
     * injected [Clock] instead. The trade-off is the same either way and is accepted: a
     * `Flow` bound to a "now" parameter does not re-emit purely because time passed, so an
     * expired snooze only reappears on the next value the underlying cache flow produces
     * (a sync, another local write, tab/filter change) - not automatically the instant it
     * expires.
     */
    private fun isSnoozedIntoFuture(notification: AppNotification, now: Instant): Boolean {
        val snoozedUntil = notification.snoozedUntil ?: return false
        return runCatching { Instant.parse(snoozedUntil) }.getOrNull()?.isAfter(now) == true
    }

    private data class FilterState(
        val tab: Tab,
        val category: String?,
        val type: NotificationType?,
        val unreadOnly: Boolean
    )

    private data class FilteredNotifications(
        val filtered: List<AppNotification>,
        val availableCategories: List<String>,
        val requestedCategory: String?,
        val effectiveCategory: String?
    )
}
