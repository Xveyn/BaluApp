package com.baluhost.android.data.notification

import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.domain.usecase.notification.SyncNotificationsUseCase
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class PushNotificationStoreTest {

    private lateinit var repository: NotificationRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var syncNotificationsUseCase: SyncNotificationsUseCase
    private lateinit var store: PushNotificationStore

    private val receivedAt = Instant.parse("2026-08-01T12:00:00Z")

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        syncNotificationsUseCase = mockk()
        every { preferencesManager.getUserId() } returns flowOf(3)
        coEvery { syncNotificationsUseCase() } returns Result.Success(Unit)
        store = PushNotificationStore(repository, preferencesManager, syncNotificationsUseCase)
    }

    @Test
    fun `a push is stored as a partial row for the signed-in account`() = runTest {
        val stored = slot<NotificationEntity>()
        coEvery { repository.upsertFromPush(capture(stored)) } returns Unit

        val result = store.store(
            data = mapOf(
                "notification_id" to "42",
                "category" to "raid",
                "priority" to "2",
                "action_url" to "/storage"
            ),
            title = "RAID degraded",
            body = "Disk 2 missing",
            receivedAt = receivedAt
        )

        assertTrue(result)
        assertEquals(3, stored.captured.ownerUserId)
        assertEquals(42, stored.captured.id)
        assertEquals("raid", stored.captured.category)
        assertEquals(2, stored.captured.priority)
        assertEquals("/storage", stored.captured.actionUrl)
        assertEquals("RAID degraded", stored.captured.title)
        assertEquals(receivedAt, stored.captured.createdAt)
        assertEquals("FCM", stored.captured.source)
        assertTrue(stored.captured.isPartial)
    }

    @Test
    fun `nothing is stored while no account is signed in`() = runTest {
        // The row could not be attributed to anyone, and handing it to whoever
        // signs in next would be wrong.
        every { preferencesManager.getUserId() } returns flowOf(null)

        val result = store.store(
            data = mapOf("notification_id" to "42"),
            title = "t", body = "b", receivedAt = receivedAt
        )

        assertFalse(result)
        coVerify(exactly = 0) { repository.upsertFromPush(any()) }
    }

    @Test
    fun `a push without a usable notification id is not stored`() = runTest {
        val result = store.store(
            data = mapOf("category" to "raid"),
            title = "t", body = "b", receivedAt = receivedAt
        )

        assertFalse(result)
        coVerify(exactly = 0) { repository.upsertFromPush(any()) }
    }

    @Test
    fun `a missing category falls back to system rather than dropping the push`() = runTest {
        val stored = slot<NotificationEntity>()
        coEvery { repository.upsertFromPush(capture(stored)) } returns Unit

        store.store(
            data = mapOf("notification_id" to "7"),
            title = "t", body = "b", receivedAt = receivedAt
        )

        assertEquals("system", stored.captured.category)
    }

    @Test
    fun `a failing background sync does not undo the store`() = runTest {
        coEvery { repository.upsertFromPush(any()) } returns Unit
        coEvery { syncNotificationsUseCase() } throws RuntimeException("server unreachable")

        val result = store.store(
            data = mapOf("notification_id" to "42", "category" to "raid"),
            title = "t", body = "b", receivedAt = receivedAt
        )

        assertTrue(result)
        coVerify(exactly = 1) { repository.upsertFromPush(any()) }
    }

    @Test
    fun `a best-effort sync runs after a successful store`() = runTest {
        coEvery { repository.upsertFromPush(any()) } returns Unit

        store.store(
            data = mapOf("notification_id" to "42", "category" to "raid"),
            title = "t", body = "b", receivedAt = receivedAt
        )

        coVerify(exactly = 1) { syncNotificationsUseCase() }
    }
}
