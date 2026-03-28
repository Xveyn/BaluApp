package com.baluhost.android.domain.model

data class EnergyDashboard(
    val deviceName: String,
    val currentWatts: Double,
    val isOnline: Boolean,
    val todayKwh: Double,
    val todayAvgWatts: Double,
    val todayMinWatts: Double,
    val todayMaxWatts: Double,
    val monthKwh: Double,
    val deviceCount: Int = 1
)
