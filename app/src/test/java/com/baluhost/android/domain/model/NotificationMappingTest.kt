package com.baluhost.android.domain.model

import com.baluhost.android.data.remote.dto.NotificationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationMappingTest {

    private fun dto(
        deletedAt: String? = null,
        category: String = "raid"
    ) = NotificationDto(
        id = 7,
        createdAt = "2026-08-01T10:00:00Z",
        userId = null,
        notificationType = "warning",
        category = category,
        title = "RAID degraded",
        message = "Disk 2 missing",
        actionUrl = "/storage",
        isRead = false,
        deletedAt = deletedAt,
        priority = 2,
        metadata = null,
        timeAgo = "5m ago",
        snoozedUntil = null
    )

    @Test
    fun `an active notification maps to a null deletedAt`() {
        assertNull(dto().toDomain().deletedAt)
    }

    @Test
    fun `a trashed notification keeps the server timestamp`() {
        val mapped = dto(deletedAt = "2026-08-01T11:00:00Z").toDomain()

        assertEquals("2026-08-01T11:00:00Z", mapped.deletedAt)
    }

    @Test
    fun `the raw category string survives the mapping`() {
        // The server's category set is open: core categories, "lifecycle", and
        // plugin names. The enum is display-only and must not be the carrier.
        val mapped = dto(category = "steam_gaming").toDomain()

        assertEquals("steam_gaming", mapped.rawCategory)
        assertEquals(NotificationCategory.SYSTEM, mapped.category)
    }
}
