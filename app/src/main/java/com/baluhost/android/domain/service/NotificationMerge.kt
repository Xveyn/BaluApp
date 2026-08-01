package com.baluhost.android.domain.service

import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/**
 * Reconciles this device's view of a notification with the server's.
 *
 * Deliberately free of Room, Android and Retrofit: this repo has no
 * androidTest source set, so logic only counts as tested when it can run under
 * plain JUnit. Everything around this object stays mechanical.
 */
object NotificationMerge {

    private const val MIN_SNOOZE_HOURS = 1
    private const val MAX_SNOOZE_HOURS = 168

    data class LocalState(
        val isRead: Boolean,
        val deletedAt: Instant?,
        val snoozedUntil: Instant?,
        val localReadAt: Instant?,
        val localTrashedAt: Instant?,
        val localRestoredAt: Instant?,
        val localSnoozedUntil: Instant?
    )

    data class ServerState(
        val isRead: Boolean,
        val deletedAt: Instant?,
        val snoozedUntil: Instant?
    )

    sealed interface Push {
        data object MarkRead : Push
        data object Dismiss : Push
        data object Restore : Push
        data class Snooze(val hours: Int) : Push
    }

    data class Merged(val state: LocalState, val pushes: List<Push>)

    fun merge(local: LocalState, server: ServerState, now: Instant): Merged {
        val pushes = mutableListOf<Push>()

        // isRead is monotone: the server offers /read and /read-all but nothing
        // that marks something unread again. An OR needs no timestamps and
        // cannot conflict.
        val mergedRead = local.isRead || server.isRead || local.localReadAt != null
        val readIntent = if (server.isRead) null else local.localReadAt
        if (readIntent != null) pushes.add(Push.MarkRead)

        // Trash: the server wins (interim rule until BaluHost#504 adds a write
        // timestamp for restore). The local intent is still pushed, so the
        // user's decision is not silently dropped - it just does not override
        // what the server currently says.
        val effectiveTrashIntent = latestOf(local.localTrashedAt, local.localRestoredAt)
        var trashedIntent: Instant? = null
        var restoredIntent: Instant? = null
        when {
            effectiveTrashIntent == null -> Unit
            effectiveTrashIntent == local.localRestoredAt -> {
                if (server.deletedAt != null) {
                    pushes.add(Push.Restore)
                    restoredIntent = local.localRestoredAt
                }
            }
            else -> {
                if (server.deletedAt == null) {
                    pushes.add(Push.Dismiss)
                    trashedIntent = local.localTrashedAt
                }
            }
        }

        // Snooze is stored absolute; the endpoint takes whole hours from now.
        // An expired intent is dropped; a confirmed one (server already agrees)
        // is cleared the same way readIntent is once server.isRead is true.
        val pendingSnoozeIntent = local.localSnoozedUntil?.takeIf { it.isAfter(now) }
        val snoozeIntent = pendingSnoozeIntent?.takeUnless { it == server.snoozedUntil }
        if (snoozeIntent != null) {
            pushes.add(Push.Snooze(hoursUntil(snoozeIntent, now)))
        }

        return Merged(
            state = LocalState(
                isRead = mergedRead,
                deletedAt = server.deletedAt,
                snoozedUntil = server.snoozedUntil,
                localReadAt = readIntent,
                localTrashedAt = trashedIntent,
                localRestoredAt = restoredIntent,
                localSnoozedUntil = snoozeIntent
            ),
            pushes = pushes
        )
    }

    private fun latestOf(a: Instant?, b: Instant?): Instant? = when {
        a == null -> b
        b == null -> a
        b.isAfter(a) -> b
        else -> a
    }

    private fun hoursUntil(target: Instant, now: Instant): Int {
        // Duration.toMinutes() truncates any leftover seconds, which would
        // round 2h 0m 5s down to 2 whole hours instead of up to 3. Round on
        // total seconds so any excess over a whole hour is not lost.
        val seconds = Duration.between(now, target).seconds
        val hours = ceil(seconds / 3600.0).toInt()
        return hours.coerceIn(MIN_SNOOZE_HOURS, MAX_SNOOZE_HOURS)
    }
}
