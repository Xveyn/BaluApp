package com.baluhost.android.data.repository

import com.baluhost.android.data.local.database.dao.NotificationDao
import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.remote.api.NotificationsApi
import com.baluhost.android.data.remote.dto.NotificationDto
import com.baluhost.android.data.remote.dto.NotificationListResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class NotificationRepositoryImplTest {

    private lateinit var api: NotificationsApi
    private lateinit var dao: NotificationDao
    private lateinit var repository: NotificationRepositoryImpl

    private val owner = 3

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        repository = NotificationRepositoryImpl(api, dao)
    }

    private fun dto(id: Int, isRead: Boolean = false, deletedAt: String? = null) = NotificationDto(
        id = id,
        createdAt = "2026-08-01T10:00:00Z",
        userId = null,
        notificationType = "info",
        category = "system",
        title = "Title $id",
        message = "Message $id",
        actionUrl = null,
        isRead = isRead,
        deletedAt = deletedAt,
        priority = 0,
        metadata = null,
        timeAgo = null,
        snoozedUntil = null
    )

    private fun listOfDto(vararg items: NotificationDto) = NotificationListResponse(
        notifications = items.toList(), total = items.size, unreadCount = 0, page = 1, pageSize = 50
    )

    private fun entity(
        id: Int,
        isRead: Boolean = false,
        localReadAt: Instant? = null,
        localTrashedAt: Instant? = null
    ) = NotificationEntity(
        ownerUserId = owner,
        id = id,
        createdAt = Instant.parse("2026-08-01T10:00:00Z"),
        notificationType = "info",
        category = "system",
        title = "Title $id",
        message = "Message $id",
        isRead = isRead,
        localReadAt = localReadAt,
        localTrashedAt = localTrashedAt,
        source = "REST"
    )

    @Test
    fun `sync pushes a local read intent the server does not know yet`() = runTest {
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } returns
            listOfDto(dto(id = 1, isRead = false))
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(entity(id = 1, localReadAt = Instant.now()))

        repository.sync(owner)

        coVerify(exactly = 1) { api.markAsRead(1) }
    }

    @Test
    fun `sync does not push a read intent the server already confirmed`() = runTest {
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } returns
            listOfDto(dto(id = 1, isRead = true))
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(entity(id = 1, localReadAt = Instant.now()))

        repository.sync(owner)

        coVerify(exactly = 0) { api.markAsRead(any()) }
    }

    @Test
    fun `a server row replaces a partial FCM row`() = runTest {
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } returns
            listOfDto(dto(id = 9))
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(
            entity(id = 9).copy(source = "FCM", isPartial = true, title = "from push")
        )
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.upsertAll(capture(stored)) } returns Unit

        repository.sync(owner)

        val row = stored.captured.single { it.id == 9 }
        assertEquals("Title 9", row.title)
        assertEquals("REST", row.source)
        assertTrue(!row.isPartial)
    }

    @Test
    fun `a failing server call leaves the cache untouched and reports failure`() = runTest {
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } throws
            java.io.IOException("offline")

        val result = repository.sync(owner)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }

    @Test
    fun `eviction keeps rows whose local decision has not been pushed`() = runTest {
        coEvery { dao.count(owner) } returns 502
        coEvery { dao.getEvictable(owner) } returns listOf(entity(id = 1), entity(id = 2))
        val evicted = slot<List<Int>>()
        coEvery { dao.deleteAllById(owner, capture(evicted)) } returns Unit

        repository.evictOverflow(owner)

        assertEquals(listOf(1, 2), evicted.captured)
    }

    @Test
    fun `markReadLocally records the intent without calling the server`() = runTest {
        coEvery { dao.find(owner, 4) } returns entity(id = 4)
        val updated = slot<NotificationEntity>()
        coEvery { dao.update(capture(updated)) } returns Unit

        repository.markReadLocally(owner, 4)

        assertTrue(updated.captured.isRead)
        assertTrue(updated.captured.localReadAt != null)
        coVerify(exactly = 0) { api.markAsRead(any()) }
    }

    @Test
    fun `markReadLocally on an unknown id does nothing rather than crashing`() = runTest {
        coEvery { dao.find(owner, 99) } returns null

        repository.markReadLocally(owner, 99)

        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun `a permanent delete removes the row locally too`() = runTest {
        val result = repository.deletePermanently(owner, 5)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { api.deletePermanently(5) }
        coVerify(exactly = 1) { dao.delete(owner, 5) }
    }

    @Test
    fun `a failed permanent delete keeps the row`() = runTest {
        coEvery { api.deletePermanently(5) } throws java.io.IOException("offline")

        val result = repository.deletePermanently(owner, 5)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dao.delete(owner, 5) }
    }
}
