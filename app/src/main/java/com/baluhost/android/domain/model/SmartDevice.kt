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

fun formatBytesPublic(bytes: Long): String = com.baluhost.android.util.ByteFormatter.format(bytes)
