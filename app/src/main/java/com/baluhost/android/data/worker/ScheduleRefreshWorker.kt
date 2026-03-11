package com.baluhost.android.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic worker that refreshes sync schedules from the server
 * and reconciles them with WorkManager jobs.
 *
 * Runs every 30 minutes + on app foreground.
 */
@HiltWorker
class ScheduleRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncScheduleManager: SyncScheduleManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ScheduleRefreshWorker"
        const val WORK_NAME = "schedule_refresh_work"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Refreshing sync schedules")
            syncScheduleManager.refreshAndReconcile()
            Log.d(TAG, "Schedule refresh complete")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Schedule refresh failed", e)
            Result.retry()
        }
    }
}
