package com.baluhost.android.domain.model

data class CpuSample(
    val timestamp: Long,
    val usagePercent: Double,
    val frequencyMhz: Double?,
    val temperatureCelsius: Double?
)

data class MemorySample(
    val timestamp: Long,
    val usedBytes: Long,
    val totalBytes: Long,
    val percent: Double
)

data class CpuHistory(
    val samples: List<CpuSample>,
    val sampleCount: Int
)

data class MemoryHistory(
    val samples: List<MemorySample>,
    val sampleCount: Int
)

data class PowerHourlySample(
    val timestamp: Long,
    val avgWatts: Double
)

data class EnergyDashboardFull(
    val deviceName: String,
    val currentWatts: Double,
    val isOnline: Boolean,
    val todayKwh: Double,
    val todayAvgWatts: Double,
    val todayMinWatts: Double,
    val todayMaxWatts: Double,
    val monthKwh: Double,
    val hourlySamples: List<PowerHourlySample>
)
