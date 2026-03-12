package com.baluhost.android.util

/**
 * Central notification ID constants to avoid collisions.
 */
object NotificationIds {
    // Sync
    const val SYNC_PROGRESS = 1001
    const val SYNC_COMPLETE = 1002
    const val SYNC_ERROR = 1003
    const val SYNC_CONFLICTS = 1004

    // Connection
    const val CONNECTION = 1010

    // FCM Push
    const val DEVICE_EXPIRATION = 2001
    const val DEVICE_STATUS = 2002

    // Backend Notifications (dynamic)
    fun forNotification(id: Int) = 3000 + id
}
