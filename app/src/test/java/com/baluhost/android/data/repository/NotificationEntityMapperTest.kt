package com.baluhost.android.data.repository

import com.baluhost.android.data.local.database.entities.NotificationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NotificationEntityMapperTest {

    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")

    private fun entity(createdAt: Instant) = NotificationEntity(
        ownerUserId = 3,
        id = 1,
        createdAt = createdAt,
        notificationType = "info",
        category = "system",
        title = "Title",
        message = "Message",
        source = "REST"
    )

    @Test
    fun `a cached row carries a time again instead of a blank line`() {
        // The whole list is served from the cache now, so a null here means every
        // row on screen is timeless - which is what the mapper used to produce.
        val mapped = entity(now.minusSeconds(600)).toDomain(now)

        assertEquals("vor 10 Min.", mapped.timeAgo)
    }

    @Test
    fun `the age is derived from createdAt, not frozen when the response was built`() {
        val row = entity(now.minusSeconds(3600))

        assertEquals("vor 1 Stunde", row.toDomain(now).timeAgo)
        // Same cached row, an hour later: the server's time_ago string could not
        // have done this.
        assertEquals("vor 2 Stunden", row.toDomain(now.plusSeconds(3600)).timeAgo)
    }

    @Test
    fun `a fresh notification reads as just now`() {
        assertEquals("gerade eben", formatTimeAgo(now.minusSeconds(30), now))
    }

    @Test
    fun `a server clock running ahead does not produce a time in the future`() {
        assertEquals("gerade eben", formatTimeAgo(now.plusSeconds(120), now))
    }

    @Test
    fun `singular and plural days are both spelled correctly`() {
        assertEquals("vor 1 Tag", formatTimeAgo(now.minusSeconds(86_400), now))
        assertEquals("vor 3 Tagen", formatTimeAgo(now.minusSeconds(3 * 86_400), now))
    }

    @Test
    fun `beyond a week the relative form gives way to a date`() {
        val formatted = formatTimeAgo(Instant.parse("2026-06-15T08:00:00Z"), now)

        // Rendered in the device's zone, so only the shape is pinned here.
        assertTrue(formatted.matches(Regex("""\d{2}\.\d{2}\.\d{4}""")))
    }
}
