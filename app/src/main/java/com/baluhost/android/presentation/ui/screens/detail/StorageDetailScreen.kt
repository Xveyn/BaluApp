package com.baluhost.android.presentation.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.domain.model.RaidArray
import com.baluhost.android.domain.model.RaidStatus
import com.baluhost.android.domain.model.SmartDeviceInfo
import com.baluhost.android.domain.model.formatBytesPublic
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: StorageDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val storageGradient = listOf(Color(0xFF06B6D4), Color(0xFF0284C7))

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Storage Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        BaluBackground {
            if (uiState.isLoading && uiState.smartDevices.isEmpty() && uiState.raidArrays.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Sky400)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Total summary with progress bar
                    TotalStorageSummary(
                        usedBytes = uiState.totalUsed,
                        totalBytes = uiState.totalCapacity,
                        usagePercent = uiState.usagePercent,
                        gradientColors = storageGradient
                    )

                    // Mini-cards row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MetricMiniCard(
                            title = "Drives",
                            value = "${uiState.smartDevices.size}",
                            icon = Icons.Default.Storage,
                            gradientColors = storageGradient,
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            title = "Healthy",
                            value = "${uiState.smartDevices.count { it.isHealthy }}",
                            icon = Icons.Default.CheckCircle,
                            gradientColors = listOf(Color(0xFF10B981), Color(0xFF14B8A6)),
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            title = "Hottest",
                            value = uiState.smartDevices
                                .mapNotNull { it.temperature }
                                .maxOrNull()
                                ?.let { "${it}\u00B0C" } ?: "N/A",
                            icon = Icons.Default.Thermostat,
                            gradientColors = listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // RAID Arrays section
                    if (uiState.raidArrays.isNotEmpty()) {
                        StorageSection(title = "RAID ARRAYS") {
                            uiState.raidArrays.forEach { raid ->
                                RaidArrayDetailCard(
                                    raid = raid,
                                    smartDevices = uiState.smartDevices
                                )
                            }
                        }
                    }

                    // Individual Drives section
                    if (uiState.smartDevices.isNotEmpty()) {
                        val grouped = groupDrives(uiState.smartDevices)

                        StorageSection(title = "INDIVIDUAL DRIVES") {
                            grouped.forEach { device ->
                                DriveCard(device = device)
                            }
                        }
                    } else if (uiState.error?.contains("SMART") == true) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0F172A).copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "SMART data not available",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }

                    // Error display
                    uiState.error?.let { error ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Red500.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, Red500.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = Red400,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun groupDrives(devices: List<SmartDeviceInfo>): List<SmartDeviceInfo> {
    val raidMembers = devices.filter { it.raidMemberOf != null }
    val systemDrives = devices.filter { it.raidMemberOf == null && it.mountPoint == "/" }
    val ssdCache = devices.filter {
        it.raidMemberOf == null && it.mountPoint != "/" &&
                (it.name.contains("nvme", ignoreCase = true) || it.name.contains("ssd", ignoreCase = true))
    }
    val others = devices.filter {
        it.raidMemberOf == null && it.mountPoint != "/" &&
                !it.name.contains("nvme", ignoreCase = true) && !it.name.contains("ssd", ignoreCase = true)
    }
    return raidMembers + systemDrives + ssdCache + others
}

@Composable
private fun TotalStorageSummary(
    usedBytes: Long,
    totalBytes: Long,
    usagePercent: Double,
    gradientColors: List<Color>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "TOTAL STORAGE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = formatBytesPublic(usedBytes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    text = "of ${formatBytesPublic(totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8)
                )
            }

            LinearProgressIndicator(
                progress = { (usagePercent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = gradientColors.first(),
                trackColor = Color(0xFF1E293B),
            )

            Text(
                text = "${"%.1f".format(usagePercent)}% used",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
private fun StorageSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B),
                letterSpacing = 1.2.sp
            )
            content()
        }
    }
}

@Composable
private fun RaidArrayDetailCard(
    raid: RaidArray,
    smartDevices: List<SmartDeviceInfo>
) {
    val statusColors = when (raid.status) {
        RaidStatus.OPTIMAL -> listOf(Color(0xFF10B981), Color(0xFF14B8A6))
        RaidStatus.DEGRADED -> listOf(Color(0xFFFBBF24), Color(0xFFF59E0B))
        RaidStatus.REBUILDING -> listOf(Color(0xFF3B82F6), Color(0xFF2563EB))
        RaidStatus.FAILED -> listOf(Color(0xFFEF4444), Color(0xFFDC2626))
        RaidStatus.UNKNOWN -> listOf(Color(0xFF64748B), Color(0xFF475569))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFF0F172A).copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        brush = Brush.linearGradient(statusColors),
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = raid.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Surface(
                        color = statusColors.first().copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp),
                        contentColor = statusColors.first()
                    ) {
                        Text(
                            text = raid.status.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Text(
                    text = "${raid.level} \u2022 ${formatBytesPublic(raid.sizeBytes)} \u2022 ${raid.devices.size} devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Resync Progress
        if (raid.resyncProgress != null && raid.resyncProgress > 0) {
            Column {
                Text(
                    text = "Resyncing: ${(raid.resyncProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
                LinearProgressIndicator(
                    progress = { raid.resyncProgress.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF3B82F6),
                    trackColor = Color(0xFF1E293B),
                )
            }
        }

        // SMART cross-reference per device
        raid.devices.forEach { raidDevice ->
            val smartInfo = smartDevices.find { it.name.contains(raidDevice.name, ignoreCase = true) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            color = when (raidDevice.state) {
                                com.baluhost.android.domain.model.RaidDeviceState.ACTIVE -> Color(0xFF10B981)
                                com.baluhost.android.domain.model.RaidDeviceState.FAILED -> Color(0xFFEF4444)
                                com.baluhost.android.domain.model.RaidDeviceState.REBUILDING -> Color(0xFF3B82F6)
                                else -> Color(0xFF64748B)
                            },
                            shape = RoundedCornerShape(3.dp)
                        )
                )
                Text(
                    text = raidDevice.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF1F5F9),
                    modifier = Modifier.weight(1f)
                )
                if (smartInfo != null) {
                    smartInfo.temperature?.let { temp ->
                        Text(
                            text = "${temp}\u00B0C",
                            style = MaterialTheme.typography.labelSmall,
                            color = temperatureColor(temp)
                        )
                    }
                    Text(
                        text = if (smartInfo.isHealthy) "OK" else "FAIL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (smartInfo.isHealthy) Color(0xFF10B981) else Color(0xFFEF4444)
                    )
                }
            }
        }
    }
}

@Composable
private fun DriveCard(device: SmartDeviceInfo) {
    val healthColor = if (device.isHealthy) Color(0xFF10B981) else Color(0xFFEF4444)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.4f),
        border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Model + health badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = device.model ?: device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = healthColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                    contentColor = healthColor
                ) {
                    Text(
                        text = if (device.isHealthy) "PASSED" else "FAILED",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Serial, temperature, mount point
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                device.serial?.let { serial ->
                    Text(
                        text = serial,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
                device.temperature?.let { temp ->
                    Text(
                        text = "${temp}\u00B0C",
                        style = MaterialTheme.typography.labelSmall,
                        color = temperatureColor(temp)
                    )
                }
                device.mountPoint?.let { mount ->
                    Text(
                        text = mount,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Storage bar
            if (device.capacityBytes != null && device.capacityBytes > 0) {
                val percent = device.usedPercent?.toFloat() ?: if (device.usedBytes != null) {
                    (device.usedBytes.toFloat() / device.capacityBytes.toFloat()) * 100f
                } else 0f

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${formatBytesPublic(device.usedBytes ?: 0)} used",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = formatBytesPublic(device.capacityBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF06B6D4),
                        trackColor = Color(0xFF1E293B),
                    )
                }
            }

            // RAID membership
            device.raidMemberOf?.let { raidName ->
                Surface(
                    color = Color(0xFF3B82F6).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "Member of $raidName",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF60A5FA),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private fun temperatureColor(temp: Int): Color {
    return when {
        temp < 35 -> Color(0xFF10B981)
        temp < 45 -> Color(0xFFFBBF24)
        temp < 55 -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
}
