package com.baluhost.android.data.remote.dto.sync

import com.google.gson.annotations.SerializedName

data class SyncScheduleListResponseDto(
    val schedules: List<SyncScheduleDto>
)

data class SyncScheduleDto(
    @SerializedName("schedule_id") val scheduleId: Int,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("schedule_type") val scheduleType: String,
    @SerializedName("time_of_day") val timeOfDay: String?,
    @SerializedName("day_of_week") val dayOfWeek: Int?,
    @SerializedName("day_of_month") val dayOfMonth: Int?,
    @SerializedName("next_run_at") val nextRunAt: String?,
    @SerializedName("last_run_at") val lastRunAt: String?,
    val enabled: Boolean,
    @SerializedName("sync_deletions") val syncDeletions: Boolean,
    @SerializedName("resolve_conflicts") val resolveConflicts: String
)
