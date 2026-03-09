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
