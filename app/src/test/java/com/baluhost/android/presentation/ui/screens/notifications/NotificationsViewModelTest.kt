package com.baluhost.android.presentation.ui.screens.notifications

import app.cash.turbine.test
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.remote.dto.NotificationPreferencesDto
import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.model.NotificationCategory
import com.baluhost.android.domain.model.NotificationType
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.domain.usecase.notification.GetNotificationPreferencesUseCase
import com.baluhost.android.domain.usecase.notification.ObserveNotificationsUseCase
import com.baluhost.android.domain.usecase.notification.ObserveUnreadCountUseCase
import com.baluhost.android.domain.usecase.notification.SyncNotificationsUseCase
import com.baluhost.android.util.Clock
import com.baluhost.android.util.NetworkStateManager
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var observeNotificationsUseCase: ObserveNotificationsUseCase
    private lateinit var syncNotificationsUseCase: SyncNotificationsUseCase
    private lateinit var observeUnreadCountUseCase: ObserveUnreadCountUseCase
    private lateinit var getNotificationPreferencesUseCase: GetNotificationPreferencesUseCase
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var networkStateManager: NetworkStateManager

    // Fixed instant so "snoozed into the future" boundary cases don't race the wall clock.
    private val fixedNow = Instant.parse("2026-08-01T12:00:00Z")
    private val clock = Clock { fixedNow }

    private fun notification(
        id: Int,
        rawCategory: String = "raid",
        type: NotificationType = NotificationType.INFO,
        isRead: Boolean = false,
        deletedAt: String? = null,
        snoozedUntil: String? = null
    ) = AppNotification(
        id = id,
        createdAt = "2026-08-01T12:00:00Z",
        userId = 3,
        type = type,
        rawCategory = rawCategory,
        category = NotificationCategory.entries.find { it.name.equals(rawCategory, ignoreCase = true) }
            ?: NotificationCategory.SYSTEM,
        title = "title-$id",
        message = "message-$id",
        actionUrl = null,
        isRead = isRead,
        deletedAt = deletedAt,
        priority = 0,
        metadata = null,
        timeAgo = null,
        snoozedUntil = snoozedUntil
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        observeNotificationsUseCase = mockk()
        syncNotificationsUseCase = mockk()
        observeUnreadCountUseCase = mockk()
        getNotificationPreferencesUseCase = mockk()
        notificationRepository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        networkStateManager = mockk(relaxed = true)

        // Stub every flow/suspend call the init path touches, per app/src/test/CLAUDE.md.
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(emptyList())
        every { observeNotificationsUseCase(trashed = true) } returns flowOf(emptyList())
        every { observeUnreadCountUseCase() } returns flowOf(0)
        every { preferencesManager.getUserId() } returns flowOf(3)
        // No server URL configured -> the connectivity trigger's flatMapLatest settles on
        // flowOf(null) without ever calling networkStateManager, unless a test overrides this.
        every { preferencesManager.getServerUrl() } returns flowOf(null)
        coEvery { getNotificationPreferencesUseCase() } returns Result.Error(Exception("offline"))
        coEvery { syncNotificationsUseCase() } returns Result.Success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = NotificationsViewModel(
        observeNotificationsUseCase = observeNotificationsUseCase,
        syncNotificationsUseCase = syncNotificationsUseCase,
        observeUnreadCountUseCase = observeUnreadCountUseCase,
        getNotificationPreferencesUseCase = getNotificationPreferencesUseCase,
        notificationRepository = notificationRepository,
        preferencesManager = preferencesManager,
        networkStateManager = networkStateManager,
        clock = clock
    )

    @Test
    fun `the list comes from the cache and is not empty without a server connection`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(listOf(notification(1)))
        coEvery { syncNotificationsUseCase() } returns Result.Error(Exception("Server nicht erreichbar"))

        val vm = createViewModel()

        assertEquals(1, vm.uiState.value.notifications.size)
    }

    @Test
    fun `a failed background sync sets isOffline without an error state`() = runTest {
        coEvery { syncNotificationsUseCase() } returns Result.Error(Exception("Server nicht erreichbar"))

        val vm = createViewModel()

        assertTrue(vm.uiState.value.isOffline)
        // UiState has no `error` field left to accidentally set - the rewrite dropped it
        // together with the request/response loading model it belonged to.
    }

    @Test
    fun `a successful background sync clears isOffline`() = runTest {
        coEvery { syncNotificationsUseCase() } returns Result.Success(Unit)

        val vm = createViewModel()

        assertTrue(!vm.uiState.value.isOffline)
    }

    @Test
    fun `markAsRead writes locally immediately and the state reflects it even though the server is unreachable`() =
        runTest {
            val cache = MutableStateFlow(listOf(notification(1, isRead = false)))
            every { observeNotificationsUseCase(trashed = false) } returns cache
            coEvery { syncNotificationsUseCase() } returns Result.Error(Exception("Server nicht erreichbar"))

            val vm = createViewModel()

            vm.markAsRead(1)

            coVerify(exactly = 1) { notificationRepository.markReadLocally(3, 1) }

            // Simulate what Room would do: the local write becomes visible on the observed flow.
            cache.value = listOf(notification(1, isRead = true))

            assertTrue(vm.uiState.value.notifications.single().isRead)
            assertTrue(vm.uiState.value.isOffline)
        }

    @Test
    fun `hasLoadedOnce becomes true after the first cache emission, even an empty one`() = runTest {
        // Deliberately the default (empty) stub from setup(): a genuinely empty cache
        // must still flip hasLoadedOnce, since the screen's empty-state gate relies on
        // this being "have I heard from the cache flow", not "is the list non-empty".
        val vm = createViewModel()

        assertTrue(vm.uiState.value.hasLoadedOnce)
        assertTrue(vm.uiState.value.notifications.isEmpty())
    }

    @Test
    fun `switching to the trash tab observes the trashed flow`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(listOf(notification(1)))
        every { observeNotificationsUseCase(trashed = true) } returns flowOf(listOf(notification(2)))

        val vm = createViewModel()
        assertEquals(listOf(1), vm.uiState.value.notifications.map { it.id })

        vm.setTab(NotificationsViewModel.Tab.TRASH)

        assertEquals(listOf(2), vm.uiState.value.notifications.map { it.id })
    }

    @Test
    fun `switching tabs clears a category filter that does not occur in the new tab`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(
            listOf(notification(1, rawCategory = "raid"), notification(2, rawCategory = "smart"))
        )
        every { observeNotificationsUseCase(trashed = true) } returns flowOf(
            listOf(notification(3, rawCategory = "backup"))
        )

        val vm = createViewModel()
        vm.setCategory("raid")
        assertEquals(listOf(1), vm.uiState.value.notifications.map { it.id })

        vm.setTab(NotificationsViewModel.Tab.TRASH)

        // "raid" doesn't occur in the trash tab's data - the filter must not survive
        // into a context where its chip isn't offered: the list is the trash tab's
        // real content (not spuriously empty because of a filter the user can no
        // longer see or clear), and selectedCategory is coherent with the chips that
        // would actually be rendered (availableCategories).
        assertEquals(listOf(3), vm.uiState.value.notifications.map { it.id })
        assertEquals(null, vm.uiState.value.selectedCategory)
        assertEquals(listOf("backup"), vm.uiState.value.availableCategories)
    }

    @Test
    fun `a category filter is cleared when its last matching row leaves the tab without a tab switch`() = runTest {
        // This is why the fix lives in observeFilteredNotifications rather than only
        // in setTab (the narrower repair that would restore the pre-regression
        // behaviour): the same "empty list, no visible chip selected" symptom is also
        // reachable by restoring the one remaining trash row of the selected category,
        // with no tab switch involved at all. See Fix round 2 in task-10-report.md.
        val trash = MutableStateFlow(
            listOf(notification(1, rawCategory = "raid", deletedAt = "2026-08-01T11:00:00Z"))
        )
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(emptyList())
        every { observeNotificationsUseCase(trashed = true) } returns trash

        val vm = createViewModel()
        vm.setTab(NotificationsViewModel.Tab.TRASH)
        vm.setCategory("raid")
        assertEquals(listOf(1), vm.uiState.value.notifications.map { it.id })

        // Simulate the cache reacting to a restore: the only "raid" row leaves the trash tab.
        trash.value = emptyList()

        assertEquals(null, vm.uiState.value.selectedCategory)
        assertTrue(vm.uiState.value.availableCategories.isEmpty())
    }

    @Test
    fun `dismiss in the inbox lets the row disappear and appear in the trash`() = runTest {
        val inbox = MutableStateFlow(listOf(notification(1)))
        val trash = MutableStateFlow(emptyList<AppNotification>())
        every { observeNotificationsUseCase(trashed = false) } returns inbox
        every { observeNotificationsUseCase(trashed = true) } returns trash

        val vm = createViewModel()
        assertEquals(listOf(1), vm.uiState.value.notifications.map { it.id })

        vm.dismiss(1)
        coVerify(exactly = 1) { notificationRepository.dismissLocally(3, 1) }

        // Simulate the cache reacting to the trash write.
        inbox.value = emptyList()
        trash.value = listOf(notification(1, deletedAt = "2026-08-01T12:05:00Z"))

        assertTrue(vm.uiState.value.notifications.isEmpty())

        vm.setTab(NotificationsViewModel.Tab.TRASH)
        assertEquals(listOf(1), vm.uiState.value.notifications.map { it.id })
    }

    @Test
    fun `filtering by category is applied to the observed flow, comparing on rawCategory`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(
            listOf(notification(1, rawCategory = "raid"), notification(2, rawCategory = "smart"))
        )

        val vm = createViewModel()
        // setCategory takes the raw string now, not the closed NotificationCategory enum -
        // this exercises the live path; the enum can't express every category the server
        // can send (see the next test), so a call site that still passed the enum here
        // would no longer compile and would be testing dead code.
        vm.setCategory("raid")

        assertEquals(listOf(1), vm.uiState.value.notifications.map { it.id })
    }

    @Test
    fun `a plugin-contributed rawCategory is filterable`() = runTest {
        // "steam_gaming" has no corresponding NotificationCategory entry - it would
        // display-map to SYSTEM (see domain/model/CLAUDE.md) - but filtering compares
        // rawCategory directly, so it must still be selectable and match exactly its
        // own notifications, not everything that falls back to SYSTEM.
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(
            listOf(
                notification(1, rawCategory = "steam_gaming"),
                notification(2, rawCategory = "lifecycle"),
                notification(3, rawCategory = "raid")
            )
        )

        val vm = createViewModel()
        vm.setCategory("steam_gaming")

        assertEquals(listOf(1), vm.uiState.value.notifications.map { it.id })
    }

    @Test
    fun `availableCategories reflects the tab's full list, not the currently filtered one`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(
            listOf(notification(1, rawCategory = "raid"), notification(2, rawCategory = "smart"))
        )

        val vm = createViewModel()
        assertEquals(listOf("raid", "smart"), vm.uiState.value.availableCategories)

        // Narrowing by type must not shrink the category chip set out from under
        // whatever the user currently has selected.
        vm.setTypeFilter(NotificationType.CRITICAL)

        assertEquals(listOf("raid", "smart"), vm.uiState.value.availableCategories)
    }

    @Test
    fun `filtering by type is applied to the observed flow`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(
            listOf(
                notification(1, type = NotificationType.CRITICAL),
                notification(2, type = NotificationType.INFO)
            )
        )

        val vm = createViewModel()
        vm.setTypeFilter(NotificationType.CRITICAL)

        assertEquals(listOf(1), vm.uiState.value.notifications.map { it.id })
    }

    @Test
    fun `a notification snoozed into the future is absent from the inbox list`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(
            listOf(notification(1, snoozedUntil = "2026-08-01T13:00:00Z")) // one hour after fixedNow
        )

        val vm = createViewModel()

        assertTrue(vm.uiState.value.notifications.isEmpty())
    }

    @Test
    fun `a notification with a past snoozedUntil or none is present in the inbox list`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(
            listOf(
                notification(1, snoozedUntil = "2026-08-01T11:00:00Z"), // one hour before fixedNow
                notification(2, snoozedUntil = null)
            )
        )

        val vm = createViewModel()

        assertEquals(listOf(1, 2), vm.uiState.value.notifications.map { it.id })
    }

    @Test
    fun `a snoozed notification that is also trashed still appears in the trash tab`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(emptyList())
        every { observeNotificationsUseCase(trashed = true) } returns flowOf(
            listOf(
                notification(
                    1,
                    deletedAt = "2026-08-01T11:55:00Z",
                    snoozedUntil = "2026-08-01T13:00:00Z" // still in the future, but trash is unaffected
                )
            )
        )

        val vm = createViewModel()
        vm.setTab(NotificationsViewModel.Tab.TRASH)

        assertEquals(listOf(1), vm.uiState.value.notifications.map { it.id })
    }

    @Test
    fun `regaining connectivity triggers a refresh`() = runTest {
        val homeNetworkFlow = MutableSharedFlow<Boolean?>()
        every { preferencesManager.getServerUrl() } returns flowOf("http://192.168.1.10:8000")
        every { networkStateManager.observeHomeNetworkStatus(any()) } returns homeNetworkFlow

        createViewModel()
        // init's own refresh() already ran once.
        coVerify(exactly = 1) { syncNotificationsUseCase() }

        homeNetworkFlow.emit(true)

        coVerify(exactly = 2) { syncNotificationsUseCase() }
    }

    @Test
    fun `restore reports failure via a snackbar because the user triggered it`() = runTest {
        coEvery { syncNotificationsUseCase() } returns Result.Success(Unit)
        val vm = createViewModel()
        coEvery { syncNotificationsUseCase() } returns Result.Error(Exception("Server nicht erreichbar"))

        vm.snackbarEvent.test {
            vm.restore(5)
            assertEquals("Wiederherstellen fehlgeschlagen", awaitItem())
        }
        coVerify(exactly = 1) { notificationRepository.restoreLocally(3, 5) }
    }

    @Test
    fun `deletePermanently reports failure via a snackbar when the server call fails`() = runTest {
        coEvery { notificationRepository.deletePermanently(3, 5) } returns kotlin.Result.failure(
            RuntimeException("Server nicht erreichbar")
        )
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.deletePermanently(5)
            assertEquals("Endgültig löschen fehlgeschlagen", awaitItem())
        }
    }

    @Test
    fun `emptyTrash reports failure via a snackbar when the server call fails`() = runTest {
        coEvery { notificationRepository.emptyTrash(3) } returns kotlin.Result.failure(
            RuntimeException("Server nicht erreichbar")
        )
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.emptyTrash()
            assertEquals("Papierkorb leeren fehlgeschlagen", awaitItem())
        }
    }

    @Test
    fun `dismissAll dismisses every notification currently shown`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(
            listOf(notification(1), notification(2))
        )
        val vm = createViewModel()

        vm.dismissAll()

        coVerify(exactly = 1) { notificationRepository.dismissLocally(3, 1) }
        coVerify(exactly = 1) { notificationRepository.dismissLocally(3, 2) }
    }

    @Test
    fun `dismissAll only dismisses notifications matching the active category filter`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(
            listOf(notification(1, rawCategory = "raid"), notification(2, rawCategory = "smart"))
        )
        val vm = createViewModel()
        vm.setCategory("raid")

        vm.dismissAll()

        // "currently shown" (see markAllAsRead's and dismissAll's KDoc) means what the
        // active filters leave visible, not every row the tab happens to have - id 2 is
        // filtered out by category and must be left alone.
        coVerify(exactly = 1) { notificationRepository.dismissLocally(3, 1) }
        coVerify(exactly = 0) { notificationRepository.dismissLocally(3, 2) }
    }

    @Test
    fun `dismissAll does nothing in the trash tab`() = runTest {
        // The screen only offers the action in the inbox, but the ViewModel is
        // public API: dismissing what the trash shows would re-trash rows that
        // are already there.
        every { observeNotificationsUseCase(trashed = true) } returns flowOf(
            listOf(notification(1, deletedAt = "2026-08-01T11:00:00Z"))
        )
        val vm = createViewModel()
        vm.setTab(NotificationsViewModel.Tab.TRASH)

        vm.dismissAll()

        coVerify(exactly = 0) { notificationRepository.dismissLocally(any(), any()) }
    }

    @Test
    fun `retentionDays falls back to 7 when preferences cannot be loaded`() = runTest {
        coEvery { getNotificationPreferencesUseCase() } returns Result.Error(Exception("Server nicht erreichbar"))

        val vm = createViewModel()

        assertEquals(7, vm.uiState.value.retentionDays)
    }

    @Test
    fun `retentionDays is loaded from notification preferences on success`() = runTest {
        coEvery { getNotificationPreferencesUseCase() } returns Result.Success(
            NotificationPreferencesDto(
                id = 1,
                userId = 3,
                emailEnabled = true,
                pushEnabled = true,
                inAppEnabled = true,
                quietHoursEnabled = false,
                quietHoursStart = null,
                quietHoursEnd = null,
                minPriority = 0,
                categoryPreferences = null,
                trashRetentionDays = 14
            )
        )

        val vm = createViewModel()

        assertEquals(14, vm.uiState.value.retentionDays)
    }
}
