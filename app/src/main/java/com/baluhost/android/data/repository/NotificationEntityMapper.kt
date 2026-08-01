package com.baluhost.android.data.repository

import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.remote.dto.NotificationDto
import com.baluhost.android.domain.model.AppNotification
import com.baluhost.android.domain.model.NotificationCategory
import com.baluhost.android.domain.model.NotificationType
import com.baluhost.android.domain.service.NotificationMerge
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Conversions between the server DTO, the Room cache entity, and the domain
 * model used by the rest of the app. Kept separate from Notification.kt (the
 * legacy DTO -> domain mapping used by the non-cached repository methods)
 * because this file is specifically the cache's mapping layer.
 */

/**
 * Maps a server DTO into a cache row for [ownerUserId], tagging it with where
 * the row's content came from. The server's ISO-8601 timestamps are parsed
 * into [Instant]; an unexpected format on [NotificationDto.createdAt] falls
 * back to [Instant.EPOCH] rather than losing the row entirely. The nullable
 * timestamps ([NotificationDto.deletedAt], [NotificationDto.snoozedUntil])
 * fall back to null on a parse failure, since EPOCH would misrepresent them
 * as set.
 */
fun NotificationDto.toEntity(ownerUserId: Int, source: String): NotificationEntity {
    val parsedCreatedAt = runCatching { Instant.parse(createdAt) }.getOrDefault(Instant.EPOCH)
    val parsedDeletedAt = deletedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val parsedSnoozedUntil = snoozedUntil?.let { runCatching { Instant.parse(it) }.getOrNull() }

    return NotificationEntity(
        ownerUserId = ownerUserId,
        id = id,
        createdAt = parsedCreatedAt,
        userId = userId,
        notificationType = notificationType,
        category = category,
        title = title,
        message = message,
        actionUrl = actionUrl,
        isRead = isRead,
        deletedAt = parsedDeletedAt,
        priority = priority,
        metadata = metadata,
        snoozedUntil = parsedSnoozedUntil,
        source = source,
        isPartial = false
    )
}

/**
 * Maps a cache row into the domain model the rest of the app consumes.
 *
 * [now] is passed in rather than read from the clock here so the caller decides
 * when "now" is - the repository takes it once per emission of the cache flow,
 * and tests can pin it.
 */
fun NotificationEntity.toDomain(now: Instant = Instant.now()): AppNotification = AppNotification(
    id = id,
    createdAt = createdAt.toString(),
    userId = userId,
    type = NotificationType.entries.find {
        it.name.equals(notificationType, ignoreCase = true)
    } ?: NotificationType.INFO,
    rawCategory = category,
    category = NotificationCategory.entries.find {
        it.name.equals(category, ignoreCase = true)
    } ?: NotificationCategory.SYSTEM,
    title = title,
    message = message,
    actionUrl = actionUrl,
    isRead = isRead,
    deletedAt = deletedAt?.toString(),
    priority = priority,
    metadata = metadata,
    timeAgo = formatTimeAgo(createdAt, now),
    snoozedUntil = snoozedUntil?.toString()
)

/**
 * German relative age of [createdAt] as at [now], for the one line of the
 * notification card that shows a time.
 *
 * Derived here instead of carrying the server's `time_ago` string: that string
 * is a snapshot taken when the response was built, and this list is served from
 * a cache that outlives the response by days - a row would keep claiming it
 * arrived "vor 5 Minuten" for as long as it sat there. Beyond a week the
 * relative form stops helping, so it turns into a plain date.
 */
internal fun formatTimeAgo(createdAt: Instant, now: Instant): String {
    val seconds = Duration.between(createdAt, now).seconds
    val minutes = seconds / 60
    val hours = seconds / 3600
    val days = seconds / 86_400
    return when {
        // A negative age means the server's clock runs ahead of this device's;
        // "in 3 minutes" would be worse than rounding it to the present.
        seconds < 60 -> "gerade eben"
        minutes < 60 -> "vor $minutes Min."
        hours < 24 -> if (hours == 1L) "vor 1 Stunde" else "vor $hours Stunden"
        days < 7 -> if (days == 1L) "vor 1 Tag" else "vor $days Tagen"
        else -> DATE_FORMAT.format(createdAt)
    }
}

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())

/** Extracts the fields [NotificationMerge] needs to decide what to reconcile. */
fun NotificationEntity.toLocalState(): NotificationMerge.LocalState = NotificationMerge.LocalState(
    isRead = isRead,
    deletedAt = deletedAt,
    snoozedUntil = snoozedUntil,
    localReadAt = localReadAt,
    localTrashedAt = localTrashedAt,
    localRestoredAt = localRestoredAt,
    localSnoozedUntil = localSnoozedUntil
)

/** Writes a merged [NotificationMerge.LocalState] back onto the row's state fields. */
fun NotificationEntity.applyMerged(state: NotificationMerge.LocalState): NotificationEntity = copy(
    isRead = state.isRead,
    deletedAt = state.deletedAt,
    snoozedUntil = state.snoozedUntil,
    localReadAt = state.localReadAt,
    localTrashedAt = state.localTrashedAt,
    localRestoredAt = state.localRestoredAt,
    localSnoozedUntil = state.localSnoozedUntil
)
