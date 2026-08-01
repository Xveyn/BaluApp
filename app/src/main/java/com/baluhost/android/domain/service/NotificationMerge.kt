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

    /**
     * How far the server's echo of a snooze may fall short of the local intent
     * and still count as confirming it.
     *
     * The endpoint takes whole hours counted from the *server's* now, while the
     * intent is `Instant.now() + hours` computed on this device when the user
     * tapped. The two can never be equal, so an exact comparison never confirms
     * anything: every sync would re-push the snooze and push the server-side
     * expiry out by another hour. Because the push rounds up
     * ([hoursUntil]), a healthy server answers at or after the intent - only
     * request latency and modest clock skew can land it slightly earlier.
     */
    private val SNOOZE_CONFIRMATION_TOLERANCE: Duration = Duration.ofMinutes(5)

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

    /**
     * The trash decision that is actually in force on a row, once a pending
     * dismiss and a pending restore are weighed against each other.
     *
     * The later timestamp wins. An exact tie resolves to [Dismiss]: the two
     * outcomes have to be ranked somehow, and hiding a row the user acted on is
     * recoverable from the trash tab, while spuriously restoring one is not
     * something the user is given a way to notice.
     */
    sealed interface TrashIntent {
        val at: Instant

        data class Dismiss(override val at: Instant) : TrashIntent
        data class Restore(override val at: Instant) : TrashIntent
    }

    /**
     * See [TrashIntent]. Public because the repository's reconcile has to apply
     * the same rule to rows the server did not return, which never reach [merge].
     */
    fun effectiveTrashIntent(localTrashedAt: Instant?, localRestoredAt: Instant?): TrashIntent? = when {
        localTrashedAt == null && localRestoredAt == null -> null
        localRestoredAt == null -> TrashIntent.Dismiss(localTrashedAt!!)
        localTrashedAt == null -> TrashIntent.Restore(localRestoredAt)
        localRestoredAt.isAfter(localTrashedAt) -> TrashIntent.Restore(localRestoredAt)
        else -> TrashIntent.Dismiss(localTrashedAt)
    }

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
        var trashedIntent: Instant? = null
        var restoredIntent: Instant? = null
        when (val intent = effectiveTrashIntent(local.localTrashedAt, local.localRestoredAt)) {
            null -> Unit
            is TrashIntent.Restore -> {
                if (server.deletedAt != null) {
                    pushes.add(Push.Restore)
                    restoredIntent = intent.at
                }
            }
            is TrashIntent.Dismiss -> {
                if (server.deletedAt == null) {
                    pushes.add(Push.Dismiss)
                    trashedIntent = intent.at
                }
            }
        }

        // Snooze is stored absolute; the endpoint takes whole hours from now.
        // An expired intent is dropped; a confirmed one (server already agrees)
        // is cleared the same way readIntent is once server.isRead is true.
        val pendingSnoozeIntent = local.localSnoozedUntil?.takeIf { it.isAfter(now) }
        val snoozeIntent = pendingSnoozeIntent?.takeUnless { isSnoozeConfirmed(it, server.snoozedUntil) }
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

    /**
     * Whether [serverSnoozedUntil] can be read as the server's answer to a push
     * of [intent] - see [SNOOZE_CONFIRMATION_TOLERANCE] for why this is not an
     * equality check.
     */
    private fun isSnoozeConfirmed(intent: Instant, serverSnoozedUntil: Instant?): Boolean {
        val server = serverSnoozedUntil ?: return false
        return !server.isBefore(intent.minus(SNOOZE_CONFIRMATION_TOLERANCE))
    }

    /**
     * The snooze push that a still-pending [localSnoozedUntil] amounts to at
     * [now], or null once it has expired and there is nothing left to ask for.
     * Public for the same reason as [effectiveTrashIntent], and so the rounding
     * rule in [hoursUntil] stays in one place.
     */
    fun snoozePushFor(localSnoozedUntil: Instant?, now: Instant): Push.Snooze? {
        val until = localSnoozedUntil?.takeIf { it.isAfter(now) } ?: return null
        return Push.Snooze(hoursUntil(until, now))
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
