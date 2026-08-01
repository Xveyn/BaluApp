package com.baluhost.android.data.notification

import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.repository.NotificationRepository
import com.baluhost.android.domain.usecase.notification.SyncNotificationsUseCase
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists an FCM push notification into the local cache so it survives even
 * when the server's REST API is unreachable (e.g. off the home WLAN, no VPN).
 *
 * Kept out of [com.baluhost.android.services.BaluFirebaseMessagingService] on
 * purpose: `FirebaseMessagingService` cannot be instantiated under plain JUnit,
 * and this repo has no `androidTest` source set, so any logic left in the
 * service would be untestable.
 *
 * The FCM data payload only ever carries `type`, `notification_id`, `category`,
 * `priority`, `action_url` (plus `title`/`body` from the notification block) —
 * see `backend/app/services/notifications/firebase.py`. Fields the payload does
 * not carry (`notification_type`, `user_id`, `metadata`, `snoozed_until`) are
 * exactly why the resulting row is marked [NotificationEntity.isPartial]; the
 * next successful sync replaces it with the complete server row.
 */
@Singleton
class PushNotificationStore @Inject constructor(
    private val repository: NotificationRepository,
    private val preferencesManager: PreferencesManager,
    private val syncNotificationsUseCase: SyncNotificationsUseCase
) {

    /**
     * Stores [data] as a partial [NotificationEntity] owned by the signed-in
     * account. Returns false, and stores nothing, when the push cannot be
     * attributed to an account or lacks a usable notification id.
     */
    suspend fun store(
        data: Map<String, String>,
        title: String,
        body: String,
        receivedAt: Instant
    ): Boolean {
        // The row could not be attributed to anyone, and handing it to
        // whoever signs in next would be wrong.
        val ownerUserId = preferencesManager.getUserId().first() ?: return false

        val notificationId = data["notification_id"]?.toIntOrNull()
        if (notificationId == null || notificationId == 0) return false

        val entity = NotificationEntity(
            ownerUserId = ownerUserId,
            id = notificationId,
            createdAt = receivedAt,
            notificationType = "info",
            category = data["category"] ?: "system",
            title = title,
            message = body,
            actionUrl = data["action_url"]?.takeIf { it.isNotBlank() },
            isRead = false,
            priority = data["priority"]?.toIntOrNull() ?: 0,
            source = "FCM",
            isPartial = true
        )

        repository.upsertFromPush(entity)

        // Best-effort reconcile: the server is typically unreachable in exactly the
        // scenario this project exists for (push arrived while offline / app was
        // dead), so any failure here must not undo the store above.
        runCatching { syncNotificationsUseCase() }

        return true
    }
}
