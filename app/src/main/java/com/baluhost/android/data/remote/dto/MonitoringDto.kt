package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

data class CpuSampleDto(
    @SerializedName("timestamp")
    val timestamp: String,
    @SerializedName("usage_percent")
    val usagePercent: Double,
    @SerializedName("frequency_mhz")
    val frequencyMhz: Double?,
    @SerializedName("temperature_celsius")
    val temperatureCelsius: Double?
)

data class MemorySampleDto(
    @SerializedName("timestamp")
    val timestamp: String,
    @SerializedName("used_bytes")
    val usedBytes: Long,
    @SerializedName("total_bytes")
    val totalBytes: Long,
    @SerializedName("percent")
    val percent: Double
)

data class CpuHistoryResponseDto(
    @SerializedName("samples")
    val samples: List<CpuSampleDto>,
    @SerializedName("sample_count")
    val sampleCount: Int,
    @SerializedName("source")
    val source: String
)

data class MemoryHistoryResponseDto(
    @SerializedName("samples")
    val samples: List<MemorySampleDto>,
    @SerializedName("sample_count")
    val sampleCount: Int,
    @SerializedName("source")
    val source: String
)

data class UptimeSampleDto(
    @SerializedName("timestamp")
    val timestamp: String,
    @SerializedName("server_uptime_seconds")
    val serverUptimeSeconds: Long,
    @SerializedName("system_uptime_seconds")
    val systemUptimeSeconds: Long,
    @SerializedName("server_start_time")
    val serverStartTime: String,
    @SerializedName("system_boot_time")
    val systemBootTime: String
)

data class SleepEventDto(
    @SerializedName("timestamp")
    val timestamp: String,
    @SerializedName("previous_state")
    val previousState: String,
    @SerializedName("new_state")
    val newState: String,
    @SerializedName("duration_seconds")
    val durationSeconds: Double?
)

data class CurrentUptimeResponseDto(
    @SerializedName("timestamp")
    val timestamp: String,
    @SerializedName("server_uptime_seconds")
    val serverUptimeSeconds: Long,
    @SerializedName("system_uptime_seconds")
    val systemUptimeSeconds: Long,
    @SerializedName("server_start_time")
    val serverStartTime: String,
    @SerializedName("system_boot_time")
    val systemBootTime: String
)

data class UptimeHistoryResponseDto(
    @SerializedName("samples")
    val samples: List<UptimeSampleDto>,
    @SerializedName("sleep_events")
    val sleepEvents: List<SleepEventDto>,
    @SerializedName("sample_count")
    val sampleCount: Int,
    @SerializedName("source")
    val source: String
)
