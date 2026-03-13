package com.baluhost.android.presentation.ui.screens.dashboard

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.domain.model.EnergyDashboard
import com.baluhost.android.domain.model.FileAction
import com.baluhost.android.domain.model.FileItem
import com.baluhost.android.domain.model.RecentFile
import com.baluhost.android.domain.model.ShareStatistics
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.components.GlassCard
import com.baluhost.android.presentation.ui.components.GlassIntensity
import com.baluhost.android.presentation.ui.components.NotificationBell
import com.baluhost.android.presentation.ui.components.VpnStatusBanner
import com.baluhost.android.presentation.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dashboard Screen - System overview with telemetry stats.
 * Design based on the webapp dashboard with system insights.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToFiles: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToVpn: () -> Unit = {},
    onNavigateToSync: () -> Unit = {},
    onNavigateToCpuDetail: () -> Unit = {},
    onNavigateToMemoryDetail: () -> Unit = {},
    onNavigateToPowerDetail: () -> Unit = {},
    onNavigateToStorageDetail: () -> Unit = {},
    onNavigateToSharesDetail: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val unreadNotificationCount by viewModel.unreadNotificationCount.collectAsState()

    // Collect VPN state for banner
    val isInHomeNetwork by viewModel.isInHomeNetwork.collectAsState()
    val hasVpnConfig by viewModel.hasVpnConfig.collectAsState()
    val vpnBannerDismissed by viewModel.vpnBannerDismissed.collectAsState()
    val isVpnActive by viewModel.isVpnActive.collectAsState()

    // Card activation state for tap-to-glow-then-navigate
    var activatedCard by remember { mutableStateOf<String?>(null) }

    // Auto-reset after 3 seconds
    LaunchedEffect(activatedCard) {
        if (activatedCard != null) {
            delay(3000L)
            activatedCard = null
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Dashboard",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "Secure personal cloud orchestration overview",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Notification bell
                        NotificationBell(
                            unreadCount = unreadNotificationCount,
                            onClick = onNavigateToNotifications
                        )

                        // Refresh button
                        IconButton(
                            onClick = { viewModel.refresh() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh telemetry",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        
                        // Sync indicator
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color(0xFF0F172A).copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, Color(0xFF1E293B))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            if (uiState.telemetry != null) Color(0xFF10B981) else Sky400,
                                            CircleShape
                                        )
                                )
                                Text(
                                    if (uiState.telemetry != null) "Synced" else "Loading...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
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
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Sky400)
                        Text(
                            "Loading system insights...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
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
                    // VPN Status Banner (shows when outside home network)
                    VpnStatusBanner(
                        isInHomeNetwork = isInHomeNetwork,
                        isVpnActive = isVpnActive,
                        hasVpnConfig = hasVpnConfig,
                        onConnectVpn = onNavigateToVpn,
                        onDismiss = { viewModel.dismissVpnBanner() },
                        isDismissed = vpnBannerDismissed
                    )

                    // Server Status Strip
                    ServerStatusStrip(
                        isOnline = uiState.telemetry != null,
                        uptimeSeconds = uiState.telemetry?.uptime?.toLong()
                    )

                    // System Metrics Grid - 2x2 layout like webapp
                    val telemetry = uiState.telemetry
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SystemMetricCard(
                                title = "CPU USAGE",
                                value = "${telemetry?.cpu?.usagePercent?.toInt() ?: 0}%",
                                subtitle = telemetry?.cpu?.model,
                                meta = buildString {
                                    append("${telemetry?.cpu?.cores ?: 0} cores active")
                                    telemetry?.cpu?.frequencyMhz?.let { mhz ->
                                        if (mhz >= 1000) append(" \u2022 ${"%.1f".format(mhz / 1000)} GHz")
                                        else append(" \u2022 ${mhz.toInt()} MHz")
                                    }
                                    telemetry?.cpu?.temperatureCelsius?.let { temp ->
                                        append(" \u2022 ${"%.0f".format(temp)}\u00B0C")
                                    }
                                },
                                delta = "Live",
                                deltaTone = DeltaTone.LIVE,
                                progress = telemetry?.cpu?.usagePercent?.toFloat() ?: 0f,
                                icon = Icons.Default.Memory,
                                gradientColors = listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)),
                                isActivated = activatedCard == "cpu",
                                onClick = {
                                    if (activatedCard == "cpu") {
                                        activatedCard = null
                                        onNavigateToCpuDetail()
                                    } else {
                                        activatedCard = "cpu"
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )

                            SystemMetricCard(
                                title = "MEMORY",
                                value = formatFileSize(telemetry?.memory?.usedBytes ?: 0),
                                subtitle = buildString {
                                    val memType = telemetry?.memory?.type
                                    val memSpeed = telemetry?.memory?.speedMts
                                    if (memType != null) append(memType)
                                    if (memType != null && memSpeed != null) append(" ")
                                    if (memSpeed != null) append("$memSpeed MT/s")
                                }.ifEmpty { null },
                                meta = "of ${formatFileSize(telemetry?.memory?.totalBytes ?: 0)}",
                                delta = "Live",
                                deltaTone = DeltaTone.LIVE,
                                progress = telemetry?.memory?.usagePercent?.toFloat() ?: 0f,
                                icon = Icons.Default.Storage,
                                gradientColors = listOf(Color(0xFF0EA5E9), Color(0xFF6366F1)),
                                isActivated = activatedCard == "memory",
                                onClick = {
                                    if (activatedCard == "memory") {
                                        activatedCard = null
                                        onNavigateToMemoryDetail()
                                    } else {
                                        activatedCard = "memory"
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SystemMetricCard(
                                title = "TOTAL STORAGE",
                                value = formatFileSize(telemetry?.disk?.usedBytes ?: 0),
                                meta = "of ${formatFileSize(telemetry?.disk?.totalBytes ?: 0)} used",
                                delta = "Live",
                                deltaTone = DeltaTone.LIVE,
                                progress = telemetry?.disk?.usagePercent?.toFloat() ?: 0f,
                                icon = Icons.Default.Folder,
                                gradientColors = listOf(Color(0xFF06B6D4), Color(0xFF0284C7)),
                                isActivated = activatedCard == "storage",
                                onClick = {
                                    if (activatedCard == "storage") {
                                        activatedCard = null
                                        onNavigateToStorageDetail()
                                    } else {
                                        activatedCard = "storage"
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            
                            SystemMetricCard(
                                title = "UPTIME",
                                value = formatUptime(telemetry?.uptime?.toLong() ?: 0),
                                meta = "System availability",
                                delta = "Live",
                                deltaTone = DeltaTone.LIVE,
                                progress = 100f,
                                icon = Icons.Default.Power,
                                gradientColors = listOf(Color(0xFF10B981), Color(0xFF14B8A6)),
                                isActivated = activatedCard == "uptime",
                                onClick = { activatedCard = "uptime" },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    
                    // Power Consumption Card
                    PowerConsumptionCard(
                        energy = uiState.energy,
                        isActivated = activatedCard == "power",
                        onClick = {
                            if (activatedCard == "power") {
                                activatedCard = null
                                onNavigateToPowerDetail()
                            } else {
                                activatedCard = "power"
                            }
                        }
                    )

                    // Quick Share Card
                    QuickShareCard(
                        stats = uiState.shareStats,
                        isActivated = activatedCard == "shares",
                        onClick = {
                            if (activatedCard == "shares") {
                                activatedCard = null
                                onNavigateToSharesDetail()
                            } else {
                                activatedCard = "shares"
                            }
                        }
                    )

                    // Sync Summary Card
                    SyncSummaryCard(
                        pendingCount = uiState.pendingSyncCount,
                        failedCount = uiState.failedSyncCount,
                        activeCount = uiState.activeSyncFolders,
                        onDetailsClick = onNavigateToSync,
                        isActivated = activatedCard == "sync",
                        onClick = {
                            if (activatedCard == "sync") {
                                activatedCard = null
                                onNavigateToSync()
                            } else {
                                activatedCard = "sync"
                            }
                        }
                    )

                    // Recent Activity Section
                    RecentActivityCard(
                        recentFiles = uiState.recentFiles,
                        fallbackFiles = uiState.fallbackFiles,
                        isActivated = activatedCard == "recent",
                        onClick = { activatedCard = "recent" },
                        onViewAll = onNavigateToFiles
                    )
                }
            }
        }
    }
}

enum class DeltaTone {
    INCREASE, DECREASE, STEADY, LIVE
}

@Composable
private fun SystemMetricCard(
    title: String,
    value: String,
    meta: String,
    delta: String,
    deltaTone: DeltaTone,
    progress: Float,
    icon: ImageVector,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    isActivated: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val glowAlpha by animateFloatAsState(
        targetValue = if (isActivated) 1f else 0f,
        animationSpec = tween(300),
        label = "glowAlpha"
    )

    Surface(
        modifier = modifier
            .then(
                if (glowAlpha > 0f) Modifier.neonGlow(
                    color = gradientColors.first(),
                    alpha = glowAlpha * 0.28f,
                    radius = 16.dp,
                    cornerRadius = 16.dp
                ) else Modifier
            )
            .heightIn(min = 160.dp)
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = if (isActivated) {
            BorderStroke(1.5.dp, Brush.linearGradient(gradientColors))
        } else {
            BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(gradientColors)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = delta,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (deltaTone) {
                            DeltaTone.INCREASE -> Color(0xFFFDA4AF)
                            DeltaTone.DECREASE -> Color(0xFF86EFAC)
                            DeltaTone.STEADY -> Color(0xFF94A3B8)
                            DeltaTone.LIVE -> Sky400
                        }
                    )
                }

                // Progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E293B))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress / 100f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Brush.linearGradient(gradientColors))
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = com.baluhost.android.util.ByteFormatter.format(bytes)

private fun formatUptime(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return "${days}d ${hours}h ${minutes}m"
}

@Composable
private fun RaidArrayCard(raid: com.baluhost.android.domain.model.RaidArray) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFF0F172A).copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // RAID Status Icon with gradient background
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = when (raid.status) {
                            com.baluhost.android.domain.model.RaidStatus.OPTIMAL -> 
                                listOf(Color(0xFF10B981), Color(0xFF14B8A6))
                            com.baluhost.android.domain.model.RaidStatus.DEGRADED -> 
                                listOf(Color(0xFFFBBF24), Color(0xFFF59E0B))
                            com.baluhost.android.domain.model.RaidStatus.REBUILDING -> 
                                listOf(Color(0xFF3B82F6), Color(0xFF2563EB))
                            com.baluhost.android.domain.model.RaidStatus.FAILED -> 
                                listOf(Color(0xFFEF4444), Color(0xFFDC2626))
                            com.baluhost.android.domain.model.RaidStatus.UNKNOWN -> 
                                listOf(Color(0xFF64748B), Color(0xFF475569))
                        }
                    ),
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
        
        // RAID Info
        Column(
            modifier = Modifier.weight(1f)
        ) {
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
                    color = when (raid.status) {
                        com.baluhost.android.domain.model.RaidStatus.OPTIMAL -> 
                            Color(0xFF10B981).copy(alpha = 0.2f)
                        com.baluhost.android.domain.model.RaidStatus.DEGRADED -> 
                            Color(0xFFFBBF24).copy(alpha = 0.2f)
                        com.baluhost.android.domain.model.RaidStatus.REBUILDING -> 
                            Color(0xFF3B82F6).copy(alpha = 0.2f)
                        com.baluhost.android.domain.model.RaidStatus.FAILED -> 
                            Color(0xFFEF4444).copy(alpha = 0.2f)
                        com.baluhost.android.domain.model.RaidStatus.UNKNOWN -> 
                            Color(0xFF64748B).copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(4.dp),
                    contentColor = when (raid.status) {
                        com.baluhost.android.domain.model.RaidStatus.OPTIMAL -> Color(0xFF10B981)
                        com.baluhost.android.domain.model.RaidStatus.DEGRADED -> Color(0xFFFBBF24)
                        com.baluhost.android.domain.model.RaidStatus.REBUILDING -> Color(0xFF3B82F6)
                        com.baluhost.android.domain.model.RaidStatus.FAILED -> Color(0xFFEF4444)
                        com.baluhost.android.domain.model.RaidStatus.UNKNOWN -> Color(0xFF64748B)
                    }
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
                text = "${raid.level} • ${formatFileSize(raid.sizeBytes)} • ${raid.devices.size} devices",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(top = 4.dp)
            )
            
            // Resync Progress
            if (raid.resyncProgress != null && raid.resyncProgress > 0) {
                Column(
                    modifier = Modifier.padding(top = 8.dp)
                ) {
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
        }
    }
}

@Composable
private fun RecentFileItem(file: RecentFile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when {
                    file.isDirectory -> Icons.Default.Folder
                    file.fileName.endsWith(".pdf") -> Icons.Default.Description
                    file.fileName.endsWith(".jpg") || file.fileName.endsWith(".png") -> Icons.Default.Image
                    file.fileName.endsWith(".mp4") -> Icons.Default.VideoFile
                    else -> Icons.Default.InsertDriveFile
                },
                contentDescription = null,
                tint = if (file.isDirectory) Sky400 else Color(0xFF818CF8),
                modifier = Modifier.size(32.dp)
            )
            // Action badge
            Icon(
                imageVector = when (file.lastAction) {
                    FileAction.DOWNLOAD -> Icons.Default.Download
                    FileAction.UPLOAD -> Icons.Default.Upload
                    FileAction.EDIT -> Icons.Default.Edit
                    FileAction.DELETE -> Icons.Default.Delete
                    FileAction.SHARE -> Icons.Default.Share
                    FileAction.MOVE -> Icons.Default.DriveFileMove
                    FileAction.RENAME -> Icons.Default.DriveFileRenameOutline
                    else -> Icons.Default.TouchApp
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
                    .background(Color(0xFF3B82F6), CircleShape)
                    .padding(1.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFF1F5F9),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${file.lastAction.displayLabel} · ${formatRelativeTime(file.lastActionAt.epochSecond)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
            )
        }

        if (!file.isDirectory && file.fileSize != null) {
            Text(
                text = file.displaySize,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
private fun FallbackFileItem(file: FileItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                file.isDirectory -> Icons.Default.Folder
                file.name.endsWith(".pdf") -> Icons.Default.Description
                file.name.endsWith(".jpg") || file.name.endsWith(".png") -> Icons.Default.Image
                file.name.endsWith(".mp4") -> Icons.Default.VideoFile
                else -> Icons.Default.InsertDriveFile
            },
            contentDescription = null,
            tint = if (file.isDirectory) Sky400 else Color(0xFF818CF8),
            modifier = Modifier.size(32.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFF1F5F9)
            )
            Text(
                text = formatDate(file.modifiedAt.epochSecond),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
            )
        }

        if (!file.isDirectory) {
            Text(
                text = formatFileSize(file.size),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
            )
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp * 1000))
}

private fun formatRelativeTime(epochSeconds: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - epochSeconds
    return when {
        diff < 60 -> "gerade eben"
        diff < 3600 -> "${diff / 60} Min."
        diff < 86400 -> "${diff / 3600} Std."
        diff < 604800 -> "${diff / 86400} Tage"
        else -> {
            val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            sdf.format(Date(epochSeconds * 1000))
        }
    }
}

@Composable
private fun ServerStatusStrip(
    isOnline: Boolean,
    uptimeSeconds: Long?
) {
    var showPowerDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (isOnline) Green500 else Red500,
                            CircleShape
                        )
                )
                Text(
                    text = if (isOnline) "Server Online" else "Server Offline",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                if (isOnline && uptimeSeconds != null) {
                    Text(
                        text = formatUptimeCompact(uptimeSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                }
            }
            IconButton(
                onClick = { showPowerDialog = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Power settings",
                    tint = Slate400,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showPowerDialog) {
        AlertDialog(
            onDismissRequest = { showPowerDialog = false },
            confirmButton = {
                TextButton(onClick = { showPowerDialog = false }) {
                    Text("OK", color = Sky400)
                }
            },
            title = {
                Text(
                    "Power Management",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    "Stromverwaltung wird in einem zukünftigen Update verfügbar.",
                    color = Slate400
                )
            },
            containerColor = Slate900,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

private fun formatUptimeCompact(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    return "${days}d ${hours}h"
}

@Composable
private fun SyncSummaryCard(
    pendingCount: Int,
    failedCount: Int,
    activeCount: Int,
    onDetailsClick: () -> Unit,
    isActivated: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val syncGradient = listOf(Color(0xFF10B981), Color(0xFF06B6D4))
    val allClear = pendingCount == 0 && failedCount == 0 && activeCount == 0

    val glowAlpha by animateFloatAsState(
        targetValue = if (isActivated) 1f else 0f,
        animationSpec = tween(300),
        label = "syncGlowAlpha"
    )

    val borderModifier = if (failedCount > 0) {
        Modifier.border(1.dp, Red500.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
    } else {
        Modifier
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (glowAlpha > 0f) Modifier.neonGlow(
                    color = syncGradient.first(),
                    alpha = glowAlpha * 0.28f,
                    radius = 18.dp,
                    cornerRadius = 16.dp
                ) else Modifier
            )
            .then(borderModifier)
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = if (isActivated) {
            BorderStroke(1.5.dp, Brush.linearGradient(syncGradient))
        } else {
            BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SYNCHRONISATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "Sync Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                TextButton(onClick = onDetailsClick) {
                    Text(
                        "Details",
                        color = Sky400,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (allClear) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Green500,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Alles synchronisiert",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Green500
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SyncStatColumn(
                        count = pendingCount,
                        label = "Pending",
                        color = Sky400
                    )
                    SyncStatColumn(
                        count = failedCount,
                        label = "Failed",
                        color = Red500
                    )
                    SyncStatColumn(
                        count = activeCount,
                        label = "Active",
                        color = Green500
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncStatColumn(
    count: Int,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Slate400
        )
    }
}

@Composable
private fun PowerConsumptionCard(
    energy: EnergyDashboard?,
    isActivated: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val powerGradient = listOf(Color(0xFFF59E0B), Color(0xFFEF4444))

    val glowAlpha by animateFloatAsState(
        targetValue = if (isActivated) 1f else 0f,
        animationSpec = tween(300),
        label = "powerGlowAlpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (glowAlpha > 0f) Modifier.neonGlow(
                    color = powerGradient.first(),
                    alpha = glowAlpha * 0.28f,
                    radius = 18.dp,
                    cornerRadius = 16.dp
                ) else Modifier
            )
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = if (isActivated) {
            BorderStroke(1.5.dp, Brush.linearGradient(powerGradient))
        } else {
            BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ENERGY MONITORING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "Power Consumption",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFF59E0B), Color(0xFFEF4444))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (energy == null) {
                // No data state
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerOff,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Keine Energiedaten verfügbar",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF64748B)
                    )
                }
            } else {
                // Current power - big number
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "%.1f".format(energy.currentWatts),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "W",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (energy.isOnline) Color(0xFF10B981).copy(alpha = 0.15f)
                                else Color(0xFFEF4444).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (energy.isOnline) "Live" else "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (energy.isOnline) Color(0xFF10B981) else Color(0xFFEF4444),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    PowerStatItem(
                        label = "Heute",
                        value = "%.2f kWh".format(energy.todayKwh)
                    )
                    PowerStatItem(
                        label = "Ø Heute",
                        value = "%.0f W".format(energy.todayAvgWatts)
                    )
                    PowerStatItem(
                        label = "Monat",
                        value = "%.1f kWh".format(energy.monthKwh)
                    )
                }

                // Min/Max bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Min: ${"%.0f".format(energy.todayMinWatts)} W",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "Max: ${"%.0f".format(energy.todayMaxWatts)} W",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                // Power usage bar (current relative to max today)
                val maxWatts = energy.todayMaxWatts.coerceAtLeast(1.0)
                val progress = (energy.currentWatts / maxWatts).toFloat().coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E293B))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFF59E0B), Color(0xFFEF4444))
                                )
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun PowerStatItem(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF94A3B8)
        )
    }
}

@Composable
private fun QuickShareCard(
    stats: ShareStatistics?,
    isActivated: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val shareGradient = listOf(Color(0xFF3B82F6), Color(0xFF6366F1))

    val glowAlpha by animateFloatAsState(
        targetValue = if (isActivated) 1f else 0f,
        animationSpec = tween(300),
        label = "shareGlowAlpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (glowAlpha > 0f) Modifier.neonGlow(
                    color = shareGradient.first(),
                    alpha = glowAlpha * 0.28f,
                    radius = 18.dp,
                    cornerRadius = 16.dp
                ) else Modifier
            )
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = if (isActivated) {
            BorderStroke(1.5.dp, Brush.linearGradient(shareGradient))
        } else {
            BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "FILE SHARING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "Quick Share",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(shareGradient)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (stats == null || (stats.activeShares == 0 && stats.sharedWithMe == 0)) {
                // Empty state
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderShared,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "No active shares",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF64748B)
                    )
                }
            } else {
                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ShareStatColumn(
                        count = stats.activeShares,
                        label = "Shared by me",
                        color = Color(0xFF3B82F6)
                    )
                    ShareStatColumn(
                        count = stats.sharedWithMe,
                        label = "Shared with me",
                        color = Color(0xFF6366F1)
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareStatColumn(
    count: Int,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Slate400
        )
    }
}

@Composable
private fun RecentActivityCard(
    recentFiles: List<RecentFile>,
    fallbackFiles: List<FileItem> = emptyList(),
    isActivated: Boolean = false,
    onClick: (() -> Unit)? = null,
    onViewAll: () -> Unit
) {
    val recentGradient = listOf(Color(0xFF3B82F6), Color(0xFF818CF8))
    val hasActivityData = recentFiles.isNotEmpty()

    val glowAlpha by animateFloatAsState(
        targetValue = if (isActivated) 1f else 0f,
        animationSpec = tween(300),
        label = "recentGlowAlpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (glowAlpha > 0f) Modifier.neonGlow(
                    color = recentGradient.first(),
                    alpha = glowAlpha * 0.28f,
                    radius = 18.dp,
                    cornerRadius = 16.dp
                ) else Modifier
            )
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = if (isActivated) {
            BorderStroke(1.5.dp, Brush.linearGradient(recentGradient))
        } else {
            BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "RECENT ACTIVITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = if (hasActivityData) "Zuletzt verwendet" else "Dateien",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                TextButton(onClick = onViewAll) {
                    Text(
                        "View All",
                        color = Sky400,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (recentFiles.isEmpty() && fallbackFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A).copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Noch keine Aktivität",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B)
                    )
                }
            } else if (hasActivityData) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recentFiles.take(5).forEach { file ->
                        RecentFileItem(file = file)
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    fallbackFiles.take(5).forEach { file ->
                        FallbackFileItem(file = file)
                    }
                }
            }
        }
    }
}

/**
 * Draws a soft neon glow behind the composable using a blurred rounded rect on the Canvas.
 */
private fun Modifier.neonGlow(
    color: Color,
    alpha: Float,
    radius: Dp,
    cornerRadius: Dp
): Modifier = this.drawBehind {
    val radiusPx = radius.toPx()
    val cornerPx = cornerRadius.toPx()
    drawIntoCanvas { canvas ->
        val paint = Paint().also { p ->
            p.asFrameworkPaint().apply {
                isAntiAlias = true
                this.color = color.copy(alpha = alpha).toArgb()
                maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
            }
        }
        canvas.drawRoundRect(
            left = -radiusPx / 2,
            top = -radiusPx / 2,
            right = size.width + radiusPx / 2,
            bottom = size.height + radiusPx / 2,
            radiusX = cornerPx,
            radiusY = cornerPx,
            paint = paint
        )
    }
}