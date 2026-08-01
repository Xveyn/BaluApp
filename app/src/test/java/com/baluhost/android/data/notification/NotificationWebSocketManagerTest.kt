package com.baluhost.android.data.notification

import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.remote.dto.NotificationDto
import com.baluhost.android.domain.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationWebSocketManagerTest {

    private val okHttpClient: okhttp3.OkHttpClient = mockk(relaxed = true)
    private val api: com.baluhost.android.data.remote.api.NotificationsApi = mockk(relaxed = true)
    private val repository: NotificationRepository = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)

    private fun manager(userId: Int?): NotificationWebSocketManager {
        every { preferencesManager.getUserId() } returns flowOf(userId)
        return NotificationWebSocketManager(okHttpClient, api, preferencesManager, repository)
    }

    private val dto = NotificationDto(
        id = 11,
        createdAt = "2026-08-01T10:00:00Z",
        userId = null,
        notificationType = "critical",
        category = "smart",
        title = "SMART failure",
        message = "sda is failing",
        actionUrl = null,
        isRead = false,
        deletedAt = null,
        priority = 3,
        metadata = null,
        timeAgo = null,
        snoozedUntil = null
    )

    @Test
    fun `a live notification is cached as a complete row`() = runTest {
        val stored = slot<NotificationEntity>()
        coEvery { repository.upsertFromPush(capture(stored)) } returns Unit

        manager(userId = 3).persist(dto)

        assertEquals(3, stored.captured.ownerUserId)
        assertEquals(11, stored.captured.id)
        assertEquals("WEBSOCKET", stored.captured.source)
        assertFalse(stored.captured.isPartial)
    }

    @Test
    fun `nothing is cached while no account is signed in`() = runTest {
        manager(userId = null).persist(dto)

        coVerify(exactly = 0) { repository.upsertFromPush(any()) }
    }
}
