package com.baluhost.android.data.remote.dto.sync

import com.google.gson.annotations.SerializedName

data class SyncPreflightDto(
    @SerializedName("sync_allowed")
    val syncAllowed: Boolean,
    @SerializedName("current_sleep_state")
    val currentSleepState: String,
    @SerializedName("sleep_schedule")
    val sleepSchedule: SleepScheduleInfoDto?,
    @SerializedName("next_wake_at")
    val nextWakeAt: String?,
    @SerializedName("block_reason")
    val blockReason: String?
)

data class SleepScheduleInfoDto(
    val enabled: Boolean,
    @SerializedName("sleep_time")
    val sleepTime: String,
    @SerializedName("wake_time")
    val wakeTime: String,
    val mode: String
)
