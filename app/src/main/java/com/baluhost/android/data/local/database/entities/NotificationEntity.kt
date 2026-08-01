package com.baluhost.android.data.local.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.time.Instant

/**
 * A notification as this device knows it, scoped to the account that owns it.
 *
 * The primary key is (ownerUserId, id): [ownerUserId] is the signed-in account
 * from PreferencesManager, NOT the notification's own [userId] — that one is
 * null for broadcasts to admins and cannot identify an owner.
 *
 * The four local* timestamps ARE the outbox. There is no separate operation
 * log: notification state is a small idempotent set of fields, so recording the
 * intent on the row itself avoids ordering and duplicate problems entirely, and
 * survives process death for free.
 */
@Entity(tableName = "notifications", primaryKeys = ["owner_user_id", "id"])
data class NotificationEntity(
    @ColumnInfo(name = "owner_user_id")
    val ownerUserId: Int,

    @ColumnInfo(name = "id")
    val id: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    /** The notification's own target user; null means a broadcast to admins. */
    @ColumnInfo(name = "user_id")
    val userId: Int? = null,

    @ColumnInfo(name = "notification_type")
    val notificationType: String,

    /** Raw server category. Open set: core categories, "lifecycle", plugin names. */
    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "message")
    val message: String,

    @ColumnInfo(name = "action_url")
    val actionUrl: String? = null,

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,

    /** Server timestamp of the move to trash; null means active. */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant? = null,

    @ColumnInfo(name = "priority")
    val priority: Int = 0,

    @ColumnInfo(name = "metadata")
    val metadata: Map<String, Any>? = null,

    @ColumnInfo(name = "snoozed_until")
    val snoozedUntil: Instant? = null,

    @ColumnInfo(name = "local_read_at")
    val localReadAt: Instant? = null,

    @ColumnInfo(name = "local_trashed_at")
    val localTrashedAt: Instant? = null,

    @ColumnInfo(name = "local_restored_at")
    val localRestoredAt: Instant? = null,

    @ColumnInfo(name = "local_snoozed_until")
    val localSnoozedUntil: Instant? = null,

    /** REST, WEBSOCKET or FCM — where this row's content last came from. */
    @ColumnInfo(name = "source")
    val source: String,

    /** True while the row was built from an FCM payload and lacks server fields. */
    @ColumnInfo(name = "is_partial")
    val isPartial: Boolean = false
) {
    /** True while any local decision still has to reach the server. */
    val hasPendingIntent: Boolean
        get() = localReadAt != null || localTrashedAt != null ||
            localRestoredAt != null || localSnoozedUntil != null
}
