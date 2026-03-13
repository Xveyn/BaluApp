package com.baluhost.android.domain.model

/**
 * Domain models for system information and storage.
 */

data class SystemInfo(
    val cpu: CpuStats,
    val memory: MemoryStats,
    val disk: DiskStats,
    val uptime: Double,
    val devMode: Boolean = false
)

data class CpuStats(
    val usagePercent: Double,
    val cores: Int,
    val frequencyMhz: Double?,
    val temperatureCelsius: Double? = null,
    val model: String? = null
)

data class MemoryStats(
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val usagePercent: Double,
    val speedMts: Int? = null,
    val type: String? = null
)

data class DiskStats(
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val usagePercent: Double
)

data class RaidArray(
    val name: String,
    val level: String,
    val sizeBytes: Long,
    val status: RaidStatus,
    val devices: List<RaidDevice>,
    val resyncProgress: Double?,
    val bitmap: String?,
    val syncAction: String?
) {
    val formattedSize: String
        get() = formatBytes(sizeBytes)
    
    val statusColor: RaidStatusColor
        get() = when (status) {
            RaidStatus.OPTIMAL -> RaidStatusColor.GREEN
            RaidStatus.DEGRADED, RaidStatus.REBUILDING -> RaidStatusColor.YELLOW
            RaidStatus.FAILED -> RaidStatusColor.RED
            RaidStatus.UNKNOWN -> RaidStatusColor.GRAY
        }
}

data class RaidDevice(
    val name: String,
    val state: RaidDeviceState
)

enum class RaidStatus {
    OPTIMAL,
    DEGRADED,
    REBUILDING,
    FAILED,
    UNKNOWN;
    
    companion object {
        fun fromString(value: String): RaidStatus {
            return when (value.lowercase()) {
                "optimal", "active", "clean" -> OPTIMAL
                "degraded" -> DEGRADED
                "rebuilding", "resyncing" -> REBUILDING
                "failed", "faulty" -> FAILED
                else -> UNKNOWN
            }
        }
    }
}

enum class RaidDeviceState {
    ACTIVE,
    FAILED,
    REBUILDING,
    SPARE,
    UNKNOWN;
    
    companion object {
        fun fromString(value: String): RaidDeviceState {
            return when (value.lowercase()) {
                "active", "in_sync" -> ACTIVE
                "failed", "faulty" -> FAILED
                "rebuilding", "recovery" -> REBUILDING
                "spare" -> SPARE
                else -> UNKNOWN
            }
        }
    }
}

enum class RaidStatusColor {
    GREEN,
    YELLOW,
    RED,
    GRAY
}

data class StorageDisk(
    val name: String,
    val sizeBytes: Long,
    val model: String?,
    val isPartitioned: Boolean,
    val partitions: List<String>,
    val inRaid: Boolean
) {
    val formattedSize: String
        get() = formatBytes(sizeBytes)
}

private fun formatBytes(bytes: Long): String = com.baluhost.android.util.ByteFormatter.format(bytes)
