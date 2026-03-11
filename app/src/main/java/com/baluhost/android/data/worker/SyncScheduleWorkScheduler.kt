package com.baluhost.android.data.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Schedules and manages the periodic schedule-refresh worker.
 * Pattern follows OfflineQueueWorkScheduler.
 */
object SyncScheduleWorkScheduler {

    /**
     * Schedule periodic refresh of sync schedules (every 30 minutes).
     * Requires network connectivity.
     */
    fun schedulePeriodicRefresh(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = PeriodicWorkRequestBuilder<ScheduleRefreshWorker>(
            repeatInterval = 30,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ScheduleRefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }

    /**
     * Trigger an immediate schedule refresh (e.g., on app foreground or after schedule change).
     */
    fun triggerImmediateRefresh(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = OneTimeWorkRequestBuilder<ScheduleRefreshWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(work)
    }

    /**
     * Cancel all scheduled sync work.
     */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(ScheduleRefreshWorker.WORK_NAME)
        wm.cancelAllWorkByTag(SyncScheduleManager.TAG_SCHEDULED_SYNC)
    }
}
