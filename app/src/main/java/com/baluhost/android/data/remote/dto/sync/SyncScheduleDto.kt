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
    @SerializedName("resolve_conflicts") val resolveConflicts: String,
    @SerializedName("auto_vpn") val autoVpn: Boolean = false
)

data class SyncScheduleCreateDto(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("schedule_type") val scheduleType: String,
    @SerializedName("time_of_day") val timeOfDay: String?,
    @SerializedName("day_of_week") val dayOfWeek: Int? = null,
    @SerializedName("day_of_month") val dayOfMonth: Int? = null,
    @SerializedName("sync_deletions") val syncDeletions: Boolean = true,
    @SerializedName("resolve_conflicts") val resolveConflicts: String = "keep_newest",
    @SerializedName("auto_vpn") val autoVpn: Boolean = false
)

data class SyncScheduleUpdateDto(
    @SerializedName("schedule_type") val scheduleType: String? = null,
    @SerializedName("time_of_day") val timeOfDay: String? = null,
    @SerializedName("day_of_week") val dayOfWeek: Int? = null,
    @SerializedName("day_of_month") val dayOfMonth: Int? = null,
    @SerializedName("is_active") val isActive: Boolean? = null,
    @SerializedName("sync_deletions") val syncDeletions: Boolean? = null,
    @SerializedName("resolve_conflicts") val resolveConflicts: String? = null,
    @SerializedName("auto_vpn") val autoVpn: Boolean? = null
)
