package com.baluhost.android.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NotificationMergeTest {

    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")

    private fun local(
        isRead: Boolean = false,
        deletedAt: Instant? = null,
        snoozedUntil: Instant? = null,
        localReadAt: Instant? = null,
        localTrashedAt: Instant? = null,
        localRestoredAt: Instant? = null,
        localSnoozedUntil: Instant? = null
    ) = NotificationMerge.LocalState(
        isRead, deletedAt, snoozedUntil,
        localReadAt, localTrashedAt, localRestoredAt, localSnoozedUntil
    )

    private fun server(
        isRead: Boolean = false,
        deletedAt: Instant? = null,
        snoozedUntil: Instant? = null
    ) = NotificationMerge.ServerState(isRead, deletedAt, snoozedUntil)

    // --- isRead: monotone OR ---

    @Test
    fun `read on the server wins over unread locally`() {
        val merged = NotificationMerge.merge(local(isRead = false), server(isRead = true), now)

        assertTrue(merged.state.isRead)
        assertTrue(merged.pushes.isEmpty())
    }

    @Test
    fun `read locally is pushed while the server still says unread`() {
        val merged = NotificationMerge.merge(
            local(localReadAt = now.minusSeconds(60)), server(isRead = false), now
        )

        assertTrue(merged.state.isRead)
        assertTrue(merged.pushes.contains(NotificationMerge.Push.MarkRead))
    }

    @Test
    fun `a confirmed read intent is cleared`() {
        val merged = NotificationMerge.merge(
            local(localReadAt = now.minusSeconds(60)), server(isRead = true), now
        )

        assertNull(merged.state.localReadAt)
        assertTrue(merged.pushes.isEmpty())
    }

    // --- trash: the server wins (interim rule until BaluHost#504) ---

    @Test
    fun `the server restore wins over a local dismiss`() {
        // The interim rule. Losing a dismiss is harmless - the user taps again.
        // A deleted notification reappearing is visibly wrong.
        val merged = NotificationMerge.merge(
            local(deletedAt = now.minusSeconds(600), localTrashedAt = now.minusSeconds(600)),
            server(deletedAt = null),
            now
        )

        assertNull(merged.state.deletedAt)
        assertTrue(merged.pushes.contains(NotificationMerge.Push.Dismiss))
    }

    @Test
    fun `a confirmed dismiss intent is cleared`() {
        val trashedAt = now.minusSeconds(600)
        val merged = NotificationMerge.merge(
            local(localTrashedAt = trashedAt), server(deletedAt = trashedAt), now
        )

        assertNull(merged.state.localTrashedAt)
        assertEquals(trashedAt, merged.state.deletedAt)
        assertTrue(merged.pushes.isEmpty())
    }

    @Test
    fun `a local restore is pushed while the server still has it trashed`() {
        val merged = NotificationMerge.merge(
            local(localRestoredAt = now.minusSeconds(30)),
            server(deletedAt = now.minusSeconds(600)),
            now
        )

        assertTrue(merged.pushes.contains(NotificationMerge.Push.Restore))
    }

    @Test
    fun `dismiss and restore both pending resolves to the later one`() {
        // Offline the user dismissed, then changed their mind and restored.
        val merged = NotificationMerge.merge(
            local(
                localTrashedAt = now.minusSeconds(600),
                localRestoredAt = now.minusSeconds(60)
            ),
            server(deletedAt = now.minusSeconds(900)),
            now
        )

        assertTrue(merged.pushes.contains(NotificationMerge.Push.Restore))
        assertFalse(merged.pushes.contains(NotificationMerge.Push.Dismiss))
    }

    // --- snooze ---

    @Test
    fun `a future local snooze is pushed as remaining whole hours`() {
        val merged = NotificationMerge.merge(
            local(localSnoozedUntil = now.plusSeconds(3600 * 2 + 5)), server(), now
        )

        assertEquals(NotificationMerge.Push.Snooze(3), merged.pushes.single())
    }

    @Test
    fun `an expired local snooze is dropped instead of pushed`() {
        val merged = NotificationMerge.merge(
            local(localSnoozedUntil = now.minusSeconds(60)), server(), now
        )

        assertTrue(merged.pushes.isEmpty())
        assertNull(merged.state.localSnoozedUntil)
    }

    @Test
    fun `a snooze shorter than an hour is pushed as one hour`() {
        // The server accepts 1..168 only.
        val merged = NotificationMerge.merge(
            local(localSnoozedUntil = now.plusSeconds(120)), server(), now
        )

        assertEquals(NotificationMerge.Push.Snooze(1), merged.pushes.single())
    }

    @Test
    fun `the server snooze wins when nothing is pending locally`() {
        val until = now.plusSeconds(7200)
        val merged = NotificationMerge.merge(local(), server(snoozedUntil = until), now)

        assertEquals(until, merged.state.snoozedUntil)
        assertTrue(merged.pushes.isEmpty())
    }

    @Test
    fun `a confirmed snooze intent is cleared`() {
        val until = now.plusSeconds(7200)
        val merged = NotificationMerge.merge(
            local(localSnoozedUntil = until), server(snoozedUntil = until), now
        )

        assertNull(merged.state.localSnoozedUntil)
        assertEquals(until, merged.state.snoozedUntil)
        assertTrue(merged.pushes.isEmpty())
    }
}
