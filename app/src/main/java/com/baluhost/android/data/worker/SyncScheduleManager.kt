package com.baluhost.android.data.worker

import android.util.Log
import androidx.work.*
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.model.sync.ScheduleType
import com.baluhost.android.domain.model.sync.SyncSchedule
import com.baluhost.android.domain.repository.SyncRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles server-managed sync schedules with local WorkManager jobs.
 *
 * Flow:
 * 1. Fetch schedules from server (fallback: cached)
 * 2. Cancel all existing scheduled sync jobs
 * 3. Enqueue WorkManager periodic jobs for each active schedule
 */
@Singleton
class SyncScheduleManager @Inject constructor(
    private val syncRepository: SyncRepository,
    private val preferencesManager: PreferencesManager,
    private val workManager: WorkManager
) {
    private val mutex = Mutex()

    companion object {
        private const val TAG = "SyncScheduleManager"
        const val SCHEDULE_WORK_PREFIX = "sync_schedule_"
        const val TAG_SCHEDULED_SYNC = "scheduled_sync"
    }

    /**
     * Fetch schedules from server and reconcile with WorkManager.
     */
    suspend fun refreshAndReconcile() = mutex.withLock {
        Log.d(TAG, "Refreshing sync schedules from server")

        val schedules = try {
            val result = syncRepository.getSyncSchedules()
            val serverSchedules = result.getOrNull()
            if (serverSchedules != null) {
                preferencesManager.saveSyncSchedules(serverSchedules)
                serverSchedules
            } else {
                Log.w(TAG, "Failed to fetch schedules, using cache: ${result.exceptionOrNull()?.message}")
                preferencesManager.getCachedSyncSchedules()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching schedules, using cache", e)
            preferencesManager.getCachedSyncSchedules()
        }

        reconcileWorkManagerJobs(schedules)
    }

    private fun reconcileWorkManagerJobs(schedules: List<SyncSchedule>) {
        // Cancel all existing scheduled sync jobs, then re-enqueue active ones
        workManager.cancelAllWorkByTag(TAG_SCHEDULED_SYNC)

        val activeSchedules = schedules.filter { it.enabled }
        Log.d(TAG, "Reconciling ${activeSchedules.size} active schedules (${schedules.size} total)")

        for (schedule in activeSchedules) {
            val intervalMinutes = calculateIntervalMinutes(schedule)
            val initialDelayMs = calculateInitialDelayMs(schedule)

            val work = PeriodicWorkRequestBuilder<ScheduledSyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        ScheduledSyncWorker.INPUT_SCHEDULE_ID to schedule.scheduleId,
                        ScheduledSyncWorker.INPUT_AUTO_VPN to schedule.autoVpn
                    )
                )
                .addTag(TAG_SCHEDULED_SYNC)
                .addTag("schedule:${schedule.scheduleId}")
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                "$SCHEDULE_WORK_PREFIX${schedule.scheduleId}",
                ExistingPeriodicWorkPolicy.UPDATE,
                work
            )

            Log.d(TAG, "Enqueued schedule ${schedule.scheduleId}: ${schedule.scheduleType} " +
                    "interval=${intervalMinutes}min delay=${initialDelayMs / 1000}s autoVpn=${schedule.autoVpn}")
        }
    }

    private fun calculateIntervalMinutes(schedule: SyncSchedule): Long {
        val interval = when (schedule.scheduleType) {
            ScheduleType.DAILY -> 24 * 60L
            ScheduleType.WEEKLY -> 7 * 24 * 60L
            ScheduleType.MONTHLY -> 30 * 24 * 60L
            ScheduleType.ON_CHANGE -> 6 * 60L // Fallback: check every 6 hours
            ScheduleType.MANUAL -> 24 * 60L
        }
        // WorkManager minimum is 15 minutes
        return maxOf(interval, 15L)
    }

    /**
     * Calculate initial delay until the next scheduled run time.
     * Uses server-provided nextRunAt if available, otherwise calculates from timeOfDay.
     */
    private fun calculateInitialDelayMs(schedule: SyncSchedule): Long {
        // Prefer server-calculated nextRunAt
        if (schedule.nextRunAt != null && schedule.nextRunAt > 0) {
            val delay = schedule.nextRunAt - System.currentTimeMillis()
            if (delay > 0) return delay
        }

        // Fallback: calculate from timeOfDay
        val timeOfDay = schedule.timeOfDay ?: return 0L
        try {
            val parts = timeOfDay.split(":")
            if (parts.size < 2) return 0L
            val targetTime = LocalTime.of(parts[0].toInt(), parts[1].toInt())
            val now = java.time.LocalDateTime.now()
            var targetDateTime = now.toLocalDate().atTime(targetTime)

            // If target time already passed today, schedule for next occurrence
            if (targetDateTime.isBefore(now)) {
                targetDateTime = when (schedule.scheduleType) {
                    ScheduleType.DAILY, ScheduleType.ON_CHANGE, ScheduleType.MANUAL ->
                        targetDateTime.plusDays(1)
                    ScheduleType.WEEKLY -> {
                        val targetDay = schedule.dayOfWeek?.let {
                            java.time.DayOfWeek.of(if (it == 0) 7 else it)
                        } ?: java.time.DayOfWeek.MONDAY
                        targetDateTime.with(TemporalAdjusters.next(targetDay))
                    }
                    ScheduleType.MONTHLY -> {
                        val day = schedule.dayOfMonth ?: 1
                        val nextMonth = now.toLocalDate().plusMonths(1).withDayOfMonth(
                            minOf(day, now.toLocalDate().plusMonths(1).lengthOfMonth())
                        )
                        nextMonth.atTime(targetTime)
                    }
                }
            }

            val delayMs = targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() -
                    System.currentTimeMillis()
            return maxOf(delayMs, 0L)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to calculate initial delay for schedule ${schedule.scheduleId}", e)
            return 0L
        }
    }
}
