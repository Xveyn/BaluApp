package com.baluhost.android.domain.model

data class SmartStatusInfo(
    val checkedAt: String,
    val devices: List<SmartDeviceInfo>
)

data class SmartDeviceInfo(
    val name: String,
    val model: String?,
    val serial: String?,
    val temperature: Int?,
    val status: String,
    val capacityBytes: Long?,
    val usedBytes: Long?,
    val usedPercent: Double?,
    val mountPoint: String?,
    val raidMemberOf: String?,
    val lastSelfTest: SmartSelfTest?,
    val attributes: List<SmartAttribute>
) {
    val isHealthy: Boolean
        get() = status.equals("PASSED", ignoreCase = true)

    val formattedCapacity: String
        get() = capacityBytes?.let { formatBytesPublic(it) } ?: "N/A"
}

data class SmartSelfTest(
    val testType: String?,
    val status: String?,
    val passed: Boolean?,
    val powerOnHours: Int?
)

data class SmartAttribute(
    val id: Int,
    val name: String,
    val value: Int,
    val worst: Int,
    val threshold: Int,
    val raw: String?,
    val status: String?
)

fun formatBytesPublic(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIndex = 0

    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }

    return "%.1f %s".format(size, units[unitIndex])
}
