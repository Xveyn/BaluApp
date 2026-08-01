package com.baluhost.android.data.repository

import com.baluhost.android.data.local.database.dao.NotificationDao
import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.remote.api.NotificationsApi
import com.baluhost.android.data.remote.dto.NotificationDto
import com.baluhost.android.data.remote.dto.NotificationListResponse
import com.baluhost.android.util.Clock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.time.Instant

class NotificationRepositoryImplTest {

    private lateinit var api: NotificationsApi
    private lateinit var dao: NotificationDao
    private lateinit var repository: NotificationRepositoryImpl

    private val owner = 3

    /** Fixed so the snooze and time-ago boundaries don't race the wall clock. */
    private val fixedNow: Instant = Instant.parse("2026-08-01T12:00:00Z")

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        repository = NotificationRepositoryImpl(api, dao, Clock { fixedNow })
    }

    /** The server answered, and answered no — the shape that retires an intent. */
    private fun httpError(code: Int) =
        HttpException(Response.error<Any>(code, "".toResponseBody("text/plain".toMediaType())))

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

    /** A full page, i.e. one the reconcile has to follow up on. */
    private fun fullPage(page: Int, ids: List<Int>, total: Int) = NotificationListResponse(
        notifications = ids.map { dto(id = it) },
        total = total,
        unreadCount = 0,
        page = page,
        pageSize = 50
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
        coEvery { dao.replaceReconciled(owner, any(), capture(stored)) } returns Unit

        repository.sync(owner)

        val row = stored.captured.single { it.id == 9 }
        assertEquals("Title 9", row.title)
        assertEquals("REST", row.source)
        assertTrue(!row.isPartial)
    }

    // --- what the reconcile stores after it has pushed (C1) ---

    @Test
    fun `sync stores the trash state the server reports after the dismiss it just pushed`() = runTest {
        // The list fetch still reports the row as active, because it happened before
        // the dismiss below. Writing that snapshot back is what made a dismissed row
        // reappear in the inbox a moment later.
        val trashedAt = fixedNow.minusSeconds(5)
        val serverDeletedAt = "2026-08-01T12:00:01Z"
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } returns listOfDto(dto(id = 1))
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(
            entity(id = 1, localTrashedAt = trashedAt).copy(deletedAt = trashedAt)
        )
        coEvery { api.dismiss(1) } returns dto(id = 1, deletedAt = serverDeletedAt)
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.replaceReconciled(owner, any(), capture(stored)) } returns Unit

        repository.sync(owner)

        val row = stored.captured.single { it.id == 1 }
        assertEquals(Instant.parse(serverDeletedAt), row.deletedAt)
        assertNull(row.localTrashedAt)
    }

    @Test
    fun `a dismiss that never reached the server keeps its intent for the next sync`() = runTest {
        val trashedAt = fixedNow.minusSeconds(5)
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } returns listOfDto(dto(id = 1))
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(
            entity(id = 1, localTrashedAt = trashedAt).copy(deletedAt = trashedAt)
        )
        coEvery { api.dismiss(1) } throws java.io.IOException("offline")
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.replaceReconciled(owner, any(), capture(stored)) } returns Unit

        repository.sync(owner)

        assertEquals(trashedAt, stored.captured.single { it.id == 1 }.localTrashedAt)
    }

    @Test
    fun `sync stores the restore the server confirmed instead of the trash state it was fetched with`() = runTest {
        val restoredAt = fixedNow.minusSeconds(5)
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } returns listOfDto()
        coEvery { api.getTrash(any(), any(), any(), any()) } returns
            listOfDto(dto(id = 2, deletedAt = "2026-08-01T09:00:00Z"))
        coEvery { dao.getAll(owner) } returns listOf(
            entity(id = 2).copy(localRestoredAt = restoredAt)
        )
        coEvery { api.restore(2) } returns dto(id = 2, deletedAt = null)
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.replaceReconciled(owner, any(), capture(stored)) } returns Unit

        repository.sync(owner)

        val row = stored.captured.single { it.id == 2 }
        assertNull(row.deletedAt)
        assertNull(row.localRestoredAt)
    }

    // --- what the reconcile is allowed to delete (C2) ---

    @Test
    fun `sync pages through the server instead of stopping after the first page`() = runTest {
        coEvery { api.getNotifications(any(), any(), any(), 1, any()) } returns
            fullPage(page = 1, ids = (51..100).toList(), total = 60)
        coEvery { api.getNotifications(any(), any(), any(), 2, any()) } returns
            NotificationListResponse(
                notifications = (41..50).map { dto(id = it) },
                total = 60, unreadCount = 0, page = 2, pageSize = 50
            )
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns emptyList()
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.replaceReconciled(owner, any(), capture(stored)) } returns Unit

        repository.sync(owner)

        assertEquals(60, stored.captured.size)
        coVerify(exactly = 1) { api.getNotifications(any(), any(), any(), 2, any()) }
    }

    @Test
    fun `the orphan cleanup targets exactly the cached rows the server no longer has`() = runTest {
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } returns
            listOfDto(dto(id = 5), dto(id = 7))
        coEvery { api.getTrash(any(), any(), any(), any()) } returns
            listOfDto(dto(id = 9, deletedAt = "2026-08-01T09:00:00Z"))
        coEvery { dao.getAll(owner) } returns listOf(
            entity(id = 5),
            entity(id = 7),
            entity(id = 9),
            entity(id = 11), // gone from the server, nothing pending -> the only deletion
            entity(id = 13, localReadAt = fixedNow) // gone, but still owes the server a read
        )
        val deleted = slot<List<Int>>()
        coEvery { dao.replaceReconciled(owner, capture(deleted), any()) } returns Unit

        repository.sync(owner)

        assertEquals(listOf(11), deleted.captured)
    }

    @Test
    fun `a fetch that stopped at the cache limit does not delete rows older than it saw`() = runTest {
        // 10 full pages of 50 = CACHE_LIMIT, with the server reporting far more:
        // everything below the oldest id fetched is simply unknown, not deleted.
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } answers {
            // Odd ids only, so an even id can be absent from a page the fetch did cover.
            val page = arg<Int>(3)
            val first = 10_001 - (page - 1) * 100
            fullPage(page = page, ids = (first downTo first - 98 step 2).toList(), total = 10_000)
        }
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(
            entity(id = 9_600), // inside the fetched range and absent -> really gone
            entity(id = 100) // far below the oldest page fetched -> unknown, keep
        )
        val deleted = slot<List<Int>>()
        coEvery { dao.replaceReconciled(owner, capture(deleted), any()) } returns Unit

        repository.sync(owner)

        assertTrue(deleted.captured.contains(9_600))
        assertFalse(deleted.captured.contains(100))
    }

    // --- intents on rows the server does not return at all (I1) ---

    @Test
    fun `a pending intent on a row the server no longer returns is still pushed`() = runTest {
        val trashedAt = fixedNow.minusSeconds(60)
        val stranded = entity(id = 21, localTrashedAt = trashedAt).copy(deletedAt = trashedAt)
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } returns listOfDto()
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(stranded)
        coEvery { dao.getWithPendingIntent(owner) } returns listOf(stranded)
        coEvery { api.dismiss(21) } returns dto(id = 21, deletedAt = "2026-08-01T12:00:01Z")
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.replaceReconciled(owner, any(), capture(stored)) } returns Unit

        repository.sync(owner)

        coVerify(exactly = 1) { api.dismiss(21) }
        assertNull(stored.captured.single { it.id == 21 }.localTrashedAt)
    }

    @Test
    fun `an intent the server refuses is retired rather than re-pushed forever`() = runTest {
        // emptyTrash() left this row behind; the server has since forgotten it, so
        // the dismiss can never land. Keeping the intent would exempt the row from
        // eviction and orphan cleanup for good.
        val stranded = entity(id = 22, localTrashedAt = fixedNow.minusSeconds(60))
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } returns listOfDto()
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(stranded)
        coEvery { dao.getWithPendingIntent(owner) } returns listOf(stranded)
        coEvery { api.dismiss(22) } throws httpError(404)
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.replaceReconciled(owner, any(), capture(stored)) } returns Unit

        repository.sync(owner)

        assertNull(stored.captured.single { it.id == 22 }.localTrashedAt)
    }

    @Test
    fun `a stranded intent that could not be delivered stays pending`() = runTest {
        val trashedAt = fixedNow.minusSeconds(60)
        val stranded = entity(id = 23, localTrashedAt = trashedAt)
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } returns listOfDto()
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(stranded)
        coEvery { dao.getWithPendingIntent(owner) } returns listOf(stranded)
        coEvery { api.dismiss(23) } throws java.io.IOException("offline")
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.replaceReconciled(owner, any(), capture(stored)) } returns Unit

        repository.sync(owner)

        assertEquals(trashedAt, stored.captured.single { it.id == 23 }.localTrashedAt)
    }

    // --- the unread badge and the list have to agree (I4) ---

    @Test
    fun `the unread count leaves out the rows the inbox hides as snoozed`() = runTest {
        coEvery { dao.observe(owner, false) } returns flowOf(
            listOf(
                entity(id = 1),
                entity(id = 2).copy(snoozedUntil = fixedNow.plusSeconds(3600)),
                entity(id = 3).copy(snoozedUntil = fixedNow.minusSeconds(60)),
                entity(id = 4, isRead = true)
            )
        )

        assertEquals(2, repository.observeUnreadCount(owner).first())
    }

    @Test
    fun `a failing server call leaves the cache untouched and reports failure`() = runTest {
        coEvery { api.getNotifications(any(), any(), any(), any(), any()) } throws
            java.io.IOException("offline")

        val result = repository.sync(owner)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dao.replaceReconciled(any(), any(), any()) }
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }

    @Test
    fun `eviction takes the oldest rows the DAO reports as evictable`() = runTest {
        coEvery { dao.count(owner) } returns 502
        coEvery { dao.getEvictable(owner) } returns listOf(entity(id = 1), entity(id = 2))
        val evicted = slot<List<Int>>()
        coEvery { dao.deleteAllByIdChunked(owner, capture(evicted)) } returns Unit

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

    @Test
    fun `emptyTrash deletes a locally-cached row the server had trashed`() = runTest {
        coEvery { dao.getAll(owner) } returns listOf(
            entity(id = 7).copy(deletedAt = Instant.now())
        )

        val result = repository.emptyTrash(owner)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { api.emptyTrash() }
        coVerify(exactly = 1) { dao.deleteAllByIdChunked(owner, listOf(7)) }
    }

    @Test
    fun `emptyTrash keeps a row whose trash intent has not been pushed yet`() = runTest {
        coEvery { dao.getAll(owner) } returns listOf(
            entity(id = 8, localTrashedAt = Instant.now()).copy(deletedAt = Instant.now())
        )

        val result = repository.emptyTrash(owner)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { api.emptyTrash() }
        coVerify(exactly = 0) { dao.deleteAllByIdChunked(owner, any()) }
    }

    @Test
    fun `a push for an id with an unpushed trash intent keeps that intent and deletedAt`() = runTest {
        val trashedAt = Instant.now()
        val deletedAt = Instant.now()
        coEvery { dao.find(owner, 11) } returns entity(id = 11, localTrashedAt = trashedAt).copy(deletedAt = deletedAt)
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.upsertAll(capture(stored)) } returns Unit
        val push = entity(id = 11).copy(source = "FCM", isPartial = true, title = "pushed title")

        repository.upsertFromPush(push)

        val row = stored.captured.single()
        assertEquals(trashedAt, row.localTrashedAt)
        assertEquals(deletedAt, row.deletedAt)
    }

    @Test
    fun `a push for an id with an unpushed read intent keeps it and leaves isRead true`() = runTest {
        val readAt = Instant.now()
        coEvery { dao.find(owner, 12) } returns entity(id = 12, isRead = true, localReadAt = readAt)
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.upsertAll(capture(stored)) } returns Unit
        val push = entity(id = 12, isRead = false).copy(source = "FCM", isPartial = true)

        repository.upsertFromPush(push)

        val row = stored.captured.single()
        assertTrue(row.isRead)
        assertEquals(readAt, row.localReadAt)
    }

    @Test
    fun `a push for an id with no existing row inserts it unchanged`() = runTest {
        coEvery { dao.find(owner, 13) } returns null
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.upsertAll(capture(stored)) } returns Unit
        val push = entity(id = 13).copy(source = "FCM", isPartial = true, title = "brand new")

        repository.upsertFromPush(push)

        assertEquals(listOf(push), stored.captured)
    }

    @Test
    fun `a push for an existing row with no pending intent still updates the content fields`() = runTest {
        coEvery { dao.find(owner, 14) } returns entity(id = 14)
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.upsertAll(capture(stored)) } returns Unit
        val push = entity(id = 14).copy(
            title = "Updated title",
            message = "Updated message",
            priority = 5,
            actionUrl = "https://example.test/action"
        )

        repository.upsertFromPush(push)

        val row = stored.captured.single()
        assertEquals("Updated title", row.title)
        assertEquals("Updated message", row.message)
        assertEquals(5, row.priority)
        assertEquals("https://example.test/action", row.actionUrl)
    }
}
