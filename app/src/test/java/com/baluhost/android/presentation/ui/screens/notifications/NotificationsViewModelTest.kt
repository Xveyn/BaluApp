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

    private fun notification(
        id: Int,
        rawCategory: String = "raid",
        type: NotificationType = NotificationType.INFO,
        isRead: Boolean = false,
        deletedAt: String? = null
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
        snoozedUntil = null
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
        networkStateManager = networkStateManager
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
    fun `switching to the trash tab observes the trashed flow`() = runTest {
        every { observeNotificationsUseCase(trashed = false) } returns flowOf(listOf(notification(1)))
        every { observeNotificationsUseCase(trashed = true) } returns flowOf(listOf(notification(2)))

        val vm = createViewModel()
        assertEquals(listOf(1), vm.uiState.value.notifications.map { it.id })

        vm.setTab(NotificationsViewModel.Tab.TRASH)

        assertEquals(listOf(2), vm.uiState.value.notifications.map { it.id })
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
        vm.setCategory(NotificationCategory.RAID)

        assertEquals(listOf(1), vm.uiState.value.notifications.map { it.id })
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
