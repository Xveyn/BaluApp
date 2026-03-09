package com.baluhost.android.domain.model.sync

data class SyncSchedule(
    val scheduleId: Int,
    val deviceId: String,
    val scheduleType: ScheduleType,
    val timeOfDay: String?,
    val dayOfWeek: Int?,
    val dayOfMonth: Int?,
    val nextRunAt: Long?,
    val lastRunAt: Long?,
    val enabled: Boolean,
    val syncDeletions: Boolean,
    val resolveConflicts: String
)

enum class ScheduleType {
    DAILY, WEEKLY, MONTHLY, MANUAL;

    companion object {
        fun fromString(value: String): ScheduleType {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: MANUAL
        }
    }

    fun toApiString(): String = name.lowercase()
}
