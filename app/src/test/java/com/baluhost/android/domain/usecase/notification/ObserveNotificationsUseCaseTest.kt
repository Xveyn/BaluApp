package com.baluhost.android.domain.usecase.notification

import app.cash.turbine.test
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.model.NotificationCategory
import com.baluhost.android.domain.model.NotificationType
import com.baluhost.android.domain.repository.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveNotificationsUseCaseTest {

    private val repository: NotificationRepository = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)

    private fun notification(id: Int) = AppNotification(
        id = id,
        createdAt = "2026-08-01T12:00:00Z",
        userId = 3,
        type = NotificationType.INFO,
        rawCategory = "raid",
        category = NotificationCategory.RAID,
        title = "t",
        message = "m",
        actionUrl = null,
        isRead = false,
        deletedAt = null,
        priority = 0,
        metadata = null,
        timeAgo = null,
        snoozedUntil = null
    )

    @Test
    fun `the active list comes from the cache for the signed-in account`() = runTest {
        every { preferencesManager.getUserId() } returns flowOf(3)
        every { repository.observeNotifications(3, false) } returns flowOf(listOf(notification(1)))

        ObserveNotificationsUseCase(repository, preferencesManager)(trashed = false).test {
            assertEquals(listOf(1), awaitItem().map { it.id })
            awaitComplete()
        }
    }

    @Test
    fun `the trashed flag is forwarded to the repository`() = runTest {
        every { preferencesManager.getUserId() } returns flowOf(3)
        every { repository.observeNotifications(3, true) } returns flowOf(listOf(notification(2)))

        ObserveNotificationsUseCase(repository, preferencesManager)(trashed = true).test {
            assertEquals(listOf(2), awaitItem().map { it.id })
            awaitComplete()
        }
    }

    @Test
    fun `without a signed-in account the list is empty`() = runTest {
        every { preferencesManager.getUserId() } returns flowOf(null)

        ObserveNotificationsUseCase(repository, preferencesManager)(trashed = false).test {
            assertEquals(emptyList<AppNotification>(), awaitItem())
            awaitComplete()
        }
    }
}
