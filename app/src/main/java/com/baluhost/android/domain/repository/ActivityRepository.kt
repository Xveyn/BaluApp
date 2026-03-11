package com.baluhost.android.domain.repository

import com.baluhost.android.domain.model.RecentFile
import com.baluhost.android.util.Result

/**
 * Repository for file activity tracking.
 * Handles fetching recent files from server and reporting client-side activities.
 */
interface ActivityRepository {

    /**
     * Get recently accessed files from the server.
     */
    suspend fun getRecentFiles(limit: Int = 10): Result<List<RecentFile>>

    /**
     * Track a file activity locally (buffered for offline sync).
     */
    suspend fun trackActivity(
        actionType: String,
        filePath: String,
        fileName: String,
        isDirectory: Boolean = false,
        fileSize: Long? = null,
        mimeType: String? = null
    )

    /**
     * Sync buffered activities to the server.
     */
    suspend fun syncPendingActivities(): Result<Int>
}
