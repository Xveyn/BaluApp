package com.baluhost.android.data.repository

import com.baluhost.android.data.local.database.dao.NotificationDao
import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.remote.api.NotificationsApi
import com.baluhost.android.data.remote.dto.MarkReadRequest
import com.baluhost.android.data.remote.dto.NotificationDto
import com.baluhost.android.data.remote.dto.NotificationListResponse
import com.baluhost.android.data.remote.dto.NotificationPreferencesDto
import com.baluhost.android.data.remote.dto.NotificationPreferencesUpdate
import com.baluhost.android.data.remote.dto.UnreadCountResponse
import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.model.toDomain
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.domain.service.NotificationMerge
import com.baluhost.android.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

class NotificationRepositoryImpl @Inject constructor(
    private val notificationsApi: NotificationsApi,
    private val notificationDao: NotificationDao,
    private val clock: Clock
) : NotificationRepository {

    companion object {
        /** Per-account cap on cached rows; see NotificationDao.getEvictable for the exemption rule. */
        private const val CACHE_LIMIT = 500

        /** Page size for the reconcile's two paged fetches; also the server's own default. */
        private const val PAGE_SIZE = 50
    }

    private val emptyLocalState = NotificationMerge.LocalState(
        isRead = false,
        deletedAt = null,
        snoozedUntil = null,
        localReadAt = null,
        localTrashedAt = null,
        localRestoredAt = null,
        localSnoozedUntil = null
    )

    override suspend fun getNotifications(
        unreadOnly: Boolean,
        category: String?,
        page: Int,
        pageSize: Int
    ): Result<NotificationListResponse> {
        return try {
            val response = notificationsApi.getNotifications(
                unreadOnly = unreadOnly,
                category = category,
                page = page,
                pageSize = pageSize
            )
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUnreadCount(): Result<UnreadCountResponse> {
        return try {
            Result.success(notificationsApi.getUnreadCount())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markAsRead(id: Int): Result<AppNotification> {
        return try {
            val dto = notificationsApi.markAsRead(id)
            Result.success(dto.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markAllAsRead(category: String?): Result<Int> {
        return try {
            val response = notificationsApi.markAllAsRead(MarkReadRequest(category))
            Result.success(response.count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun dismiss(id: Int): Result<AppNotification> {
        return try {
            val dto = notificationsApi.dismiss(id)
            Result.success(dto.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun snooze(id: Int, hours: Int): Result<AppNotification> {
        return try {
            val dto = notificationsApi.snooze(id, hours)
            Result.success(dto.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getPreferences(): Result<NotificationPreferencesDto> {
        return try {
            Result.success(notificationsApi.getPreferences())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updatePreferences(
        update: NotificationPreferencesUpdate
    ): Result<NotificationPreferencesDto> {
        return try {
            Result.success(notificationsApi.updatePreferences(update))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Cache-first API ---

    override fun observeNotifications(ownerUserId: Int, trashed: Boolean): Flow<List<AppNotification>> =
        notificationDao.observe(ownerUserId, trashed).map { rows ->
            val now = clock.now()
            rows.map { it.toDomain(now) }
        }

    /**
     * Counts what the inbox actually shows: unread, not trashed, and not snoozed
     * into the future. The snooze condition mirrors
     * `NotificationsViewModel.isSnoozedIntoFuture` - a badge promising unread
     * items the list deliberately hides leaves the user nothing to act on. It is
     * evaluated here rather than in a Room query for the same reason the
     * ViewModel evaluates it in Kotlin: there is no androidTest source set, so
     * time semantics expressed in SQL cannot be tested at all.
     */
    override fun observeUnreadCount(ownerUserId: Int): Flow<Int> =
        notificationDao.observe(ownerUserId, trashed = false).map { rows ->
            val now = clock.now()
            rows.count { row ->
                val snoozedUntil = row.snoozedUntil
                !row.isRead && (snoozedUntil == null || !snoozedUntil.isAfter(now))
            }
        }

    override suspend fun sync(ownerUserId: Int): Result<Unit> {
        val active: PagedFetch
        val trash: PagedFetch
        try {
            active = fetchAllPages { page -> notificationsApi.getNotifications(page = page, pageSize = PAGE_SIZE) }
            trash = fetchAllPages { page -> notificationsApi.getTrash(page = page, pageSize = PAGE_SIZE) }
        } catch (e: Exception) {
            // Half-applying a sync is worse than not syncing: bail out before the cache is touched.
            return Result.failure(e)
        }

        val serverDtos = active.dtos + trash.dtos
        val localRows = notificationDao.getAll(ownerUserId)
        val localRowsById = localRows.associateBy { it.id }
        val now = clock.now()

        val mergedRows = serverDtos.map { dto -> reconcile(dto, localRowsById[dto.id], ownerUserId, now) }

        val serverIds = serverDtos.mapTo(mutableSetOf()) { it.id }

        // A pending intent on a row the server did not return is skipped by the loop
        // above (there is no DTO to merge against) and exempted from the cleanup
        // below, so without this it would sit in the cache forever, undeliverable -
        // exactly what emptyTrash() leaves behind.
        val strandedRows = notificationDao.getWithPendingIntent(ownerUserId)
            .filter { it.id !in serverIds }
            .map { deliverStrandedIntents(it) }

        val coveredFrom = coveredFrom(active, trash)
        val toDelete = localRows
            .filter { it.id !in serverIds && !it.hasPendingIntent && it.id >= coveredFrom }
            .map { it.id }

        notificationDao.replaceReconciled(ownerUserId, toDelete, mergedRows + strandedRows)
        evictOverflow(ownerUserId)

        return Result.success(Unit)
    }

    private data class PagedFetch(val dtos: List<NotificationDto>, val exhaustive: Boolean)

    /**
     * Pages one list endpoint until the server has nothing more to give, or until
     * [CACHE_LIMIT] rows have been collected.
     *
     * Fetching a single page and then deleting every local row missing from it
     * (which is what this replaced) silently dropped everything past the first
     * page and made [CACHE_LIMIT] unreachable. Stopping at the cap is reported
     * via [PagedFetch.exhaustive] rather than pretended away - see [coveredFrom].
     */
    private suspend fun fetchAllPages(
        fetchPage: suspend (page: Int) -> NotificationListResponse
    ): PagedFetch {
        val collected = mutableListOf<NotificationDto>()
        var page = 1
        while (true) {
            val response = fetchPage(page)
            collected += response.notifications
            // A short page and a satisfied total both mean "that was everything".
            // The size check also terminates a server that reports a wrong total.
            if (response.notifications.size < PAGE_SIZE || collected.size >= response.total) {
                return PagedFetch(collected, exhaustive = true)
            }
            if (collected.size >= CACHE_LIMIT) return PagedFetch(collected, exhaustive = false)
            page++
        }
    }

    /**
     * The lowest id for which absence from the fetched pages is evidence that the
     * server dropped the row for good.
     *
     * Deletion by absence is only sound over the range the fetch actually
     * covered. When every fetch ran to exhaustion that is the whole table
     * ([Int.MIN_VALUE]); when one stopped at the cap, anything older than the
     * oldest id it saw is simply unknown, and deleting it would throw away rows
     * the server still holds. Ids are server-assigned and increase over time, so
     * the smallest id a fetch saw is its floor - and a row absent from all of
     * them has to clear the highest floor among the ones that were cut short,
     * because it could have belonged to any of those lists.
     */
    private fun coveredFrom(vararg fetches: PagedFetch): Int =
        fetches.filterNot { it.exhaustive }
            .map { fetch -> fetch.dtos.minOfOrNull { it.id } ?: Int.MAX_VALUE }
            .maxOrNull() ?: Int.MIN_VALUE

    /**
     * Merges one server row with what this device knows, delivers whatever that
     * merge decided to push, and returns the row as it stands *after* those
     * pushes.
     *
     * The list fetches are a snapshot from before the pushes. Writing that
     * snapshot back is how a dismiss the user just made came back as active a
     * second later: the server was told, then contradicted with the state it
     * held before being told. Every push endpoint answers with the updated
     * notification, so the last delivered push is the cheapest correct picture
     * of the server's post-push state.
     */
    private suspend fun reconcile(
        dto: NotificationDto,
        local: NotificationEntity?,
        ownerUserId: Int,
        now: Instant
    ): NotificationEntity {
        val serverEntity = dto.toEntity(ownerUserId, source = "REST")
        val merged = NotificationMerge.merge(
            local?.toLocalState() ?: emptyLocalState,
            NotificationMerge.ServerState(
                isRead = serverEntity.isRead,
                deletedAt = serverEntity.deletedAt,
                snoozedUntil = serverEntity.snoozedUntil
            ),
            now
        )

        var latest = dto
        var state = merged.state
        for (push in merged.pushes) {
            val outcome = deliver {
                when (push) {
                    is NotificationMerge.Push.MarkRead -> notificationsApi.markAsRead(dto.id)
                    is NotificationMerge.Push.Dismiss -> notificationsApi.dismiss(dto.id)
                    is NotificationMerge.Push.Restore -> notificationsApi.restore(dto.id)
                    is NotificationMerge.Push.Snooze -> notificationsApi.snooze(dto.id, push.hours)
                }
            }
            when (outcome) {
                is PushOutcome.Delivered -> {
                    latest = outcome.dto
                    state = state.withIntentCleared(push)
                }
                // The server answered no; re-pushing on every sync forever cannot
                // change that, so the intent is retired rather than kept.
                PushOutcome.Rejected -> state = state.withIntentCleared(push)
                // Nothing reached the server: keep the intent, the next sync retries.
                PushOutcome.Unreachable -> Unit
            }
        }

        val postPush = latest.toEntity(ownerUserId, source = "REST")
        return postPush.applyMerged(
            state.copy(
                isRead = state.isRead || postPush.isRead,
                deletedAt = postPush.deletedAt,
                snoozedUntil = postPush.snoozedUntil
            )
        )
    }

    /**
     * Delivers the pending intents of a row the server did not return.
     *
     * There is no server state to merge against, so each intent is simply
     * attempted once. A delivered or refused intent is cleared, which both stops
     * the row re-pushing forever and makes it an ordinary orphan for the next
     * reconcile to clean up; an intent that never reached the server is kept.
     */
    private suspend fun deliverStrandedIntents(row: NotificationEntity): NotificationEntity {
        var result = row

        if (row.localReadAt != null) {
            result = when (deliver { notificationsApi.markAsRead(row.id) }) {
                is PushOutcome.Delivered, PushOutcome.Rejected -> result.copy(localReadAt = null)
                PushOutcome.Unreachable -> result
            }
        }

        when (val intent = NotificationMerge.effectiveTrashIntent(row.localTrashedAt, row.localRestoredAt)) {
            null -> Unit
            is NotificationMerge.TrashIntent.Restore -> {
                result = when (deliver { notificationsApi.restore(row.id) }) {
                    is PushOutcome.Delivered, PushOutcome.Rejected ->
                        result.copy(localRestoredAt = null, localTrashedAt = null)
                    PushOutcome.Unreachable -> result
                }
            }
            is NotificationMerge.TrashIntent.Dismiss -> {
                result = when (deliver { notificationsApi.dismiss(row.id) }) {
                    is PushOutcome.Delivered, PushOutcome.Rejected ->
                        result.copy(localTrashedAt = null, localRestoredAt = null)
                    PushOutcome.Unreachable -> result
                }
            }
        }

        if (row.localSnoozedUntil != null) {
            val snoozePush = NotificationMerge.snoozePushFor(row.localSnoozedUntil, clock.now())
            result = if (snoozePush == null) {
                // Expired while stranded: there is nothing left to ask the server for.
                result.copy(localSnoozedUntil = null)
            } else {
                when (deliver { notificationsApi.snooze(row.id, snoozePush.hours) }) {
                    is PushOutcome.Delivered, PushOutcome.Rejected -> result.copy(localSnoozedUntil = null)
                    PushOutcome.Unreachable -> result
                }
            }
        }

        return result
    }

    private sealed interface PushOutcome {
        /** The server accepted the intent and answered with the row as it now stands. */
        data class Delivered(val dto: NotificationDto) : PushOutcome

        /** The server refused (4xx): the intent can never land and is retired. */
        data object Rejected : PushOutcome

        /** Nothing reached the server: the intent stays pending for the next sync. */
        data object Unreachable : PushOutcome
    }

    /**
     * Runs one push and classifies the outcome. The distinction between "refused"
     * and "unreachable" is the same one the rest of this layer draws for error
     * mapping (see data/repository/CLAUDE.md); here it decides whether an
     * unconfirmed local decision is kept or dropped, so collapsing the two would
     * either lose the decision or repeat it forever.
     */
    private suspend fun deliver(call: suspend () -> NotificationDto): PushOutcome = try {
        PushOutcome.Delivered(call())
    } catch (e: HttpException) {
        if (e.code() in 400..499) PushOutcome.Rejected else PushOutcome.Unreachable
    } catch (e: Exception) {
        PushOutcome.Unreachable
    }

    private fun NotificationMerge.LocalState.withIntentCleared(
        push: NotificationMerge.Push
    ): NotificationMerge.LocalState = when (push) {
        is NotificationMerge.Push.MarkRead -> copy(localReadAt = null)
        is NotificationMerge.Push.Dismiss -> copy(localTrashedAt = null)
        is NotificationMerge.Push.Restore -> copy(localRestoredAt = null)
        is NotificationMerge.Push.Snooze -> copy(localSnoozedUntil = null)
    }

    /** Enforces the per-account cache cap, evicting the oldest exempt-free rows first. */
    suspend fun evictOverflow(ownerUserId: Int) {
        val overflow = notificationDao.count(ownerUserId) - CACHE_LIMIT
        if (overflow <= 0) return

        val evictable = notificationDao.getEvictable(ownerUserId)
        val toEvict = evictable.take(overflow).map { it.id }
        if (toEvict.isNotEmpty()) {
            notificationDao.deleteAllByIdChunked(ownerUserId, toEvict)
        }
    }

    override suspend fun markReadLocally(ownerUserId: Int, id: Int) {
        val row = notificationDao.find(ownerUserId, id) ?: return
        val now = clock.now()
        notificationDao.update(row.copy(isRead = true, localReadAt = now))
    }

    override suspend fun dismissLocally(ownerUserId: Int, id: Int) {
        val row = notificationDao.find(ownerUserId, id) ?: return
        val now = clock.now()
        notificationDao.update(
            row.copy(deletedAt = now, localTrashedAt = now, localRestoredAt = null)
        )
    }

    override suspend fun restoreLocally(ownerUserId: Int, id: Int) {
        val row = notificationDao.find(ownerUserId, id) ?: return
        val now = clock.now()
        notificationDao.update(
            row.copy(deletedAt = null, localRestoredAt = now, localTrashedAt = null)
        )
    }

    override suspend fun snoozeLocally(ownerUserId: Int, id: Int, hours: Int) {
        val row = notificationDao.find(ownerUserId, id) ?: return
        val until = clock.now().plus(Duration.ofHours(hours.toLong()))
        notificationDao.update(row.copy(snoozedUntil = until, localSnoozedUntil = until))
    }

    override suspend fun deletePermanently(ownerUserId: Int, id: Int): Result<Unit> {
        return try {
            notificationsApi.deletePermanently(id)
            notificationDao.delete(ownerUserId, id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun emptyTrash(ownerUserId: Int): Result<Unit> {
        return try {
            notificationsApi.emptyTrash()
            // A row whose deletedAt is only an unpushed dismissLocally intent must survive:
            // the server never learned it was trashed, so wiping it here would drop the
            // user's decision — the next sync() is still the way that intent gets pushed.
            val trashedIds = notificationDao.getAll(ownerUserId)
                .filter { it.deletedAt != null && !it.hasPendingIntent }
                .map { it.id }
            if (trashedIds.isNotEmpty()) {
                notificationDao.deleteAllByIdChunked(ownerUserId, trashedIds)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun upsertFromPush(entity: NotificationEntity) {
        // A push (FCM/WebSocket) knows nothing about this device's own pending decisions.
        // Blindly REPLACE-ing the row would silently clobber an unpushed dismiss/read/
        // snooze/restore intent — the same failure shape emptyTrash() had. Merge onto the
        // existing row instead: content comes from the push, state that only this device
        // knows about comes from what's already cached.
        val existing = notificationDao.find(entity.ownerUserId, entity.id)
        val toStore = if (existing != null) {
            entity.copy(
                // isRead is monotone: never let a push flip an already-read row back to unread.
                isRead = existing.isRead || entity.isRead,
                // A push carries no trash/snooze information at all; the authoritative
                // correction for these comes from sync(), not from a partial push payload.
                deletedAt = existing.deletedAt,
                snoozedUntil = existing.snoozedUntil,
                localReadAt = existing.localReadAt,
                localTrashedAt = existing.localTrashedAt,
                localRestoredAt = existing.localRestoredAt,
                localSnoozedUntil = existing.localSnoozedUntil
            )
        } else {
            entity
        }
        notificationDao.upsertAll(listOf(toStore))
    }

    override suspend fun clearAll() {
        notificationDao.deleteAll()
    }
}
