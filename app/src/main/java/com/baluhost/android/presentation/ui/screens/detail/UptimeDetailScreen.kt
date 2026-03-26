package com.baluhost.android.presentation.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.components.TelemetryChart
import com.baluhost.android.presentation.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UptimeDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: UptimeDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val uptimeGradient = listOf(Color(0xFF10B981), Color(0xFF14B8A6))
    val timeRanges = listOf("10m", "1h", "24h", "7d")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Uptime Details",
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
            if (uiState.isLoading && uiState.currentUptime == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF10B981))
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Current uptime hero card
                    val current = uiState.currentUptime
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(uptimeGradient.map { it.copy(alpha = 0.15f) })
                                )
                                .padding(20.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "System Uptime",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF94A3B8)
                                )
                                Text(
                                    text = formatUptimeLong(current?.systemUptimeSeconds ?: 0),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Server: ${formatUptimeLong(current?.serverUptimeSeconds ?: 0)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }

                    // Info cards row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        UptimeInfoCard(
                            icon = Icons.Default.Computer,
                            label = "System Boot",
                            value = formatTimestamp(current?.systemBootTime),
                            gradient = uptimeGradient,
                            modifier = Modifier.weight(1f)
                        )
                        UptimeInfoCard(
                            icon = Icons.Default.Storage,
                            label = "Server Start",
                            value = formatTimestamp(current?.serverStartTime),
                            gradient = uptimeGradient,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Sleep events summary
                    val sleepEvents = uiState.uptimeHistory?.sleepEvents
                    if (!sleepEvents.isNullOrEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.6f)),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Sleep Events",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                sleepEvents.takeLast(5).reversed().forEach { event ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${event.previousState} → ${event.newState}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF94A3B8)
                                        )
                                        Text(
                                            text = buildString {
                                                append(formatTimestamp(event.timestamp))
                                                if (event.durationSeconds != null) {
                                                    append(" (${formatDuration(event.durationSeconds)})")
                                                }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Time range selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        timeRanges.forEach { range ->
                            FilterChip(
                                selected = uiState.selectedTimeRange == range,
                                onClick = { viewModel.selectTimeRange(range) },
                                label = {
                                    Text(
                                        text = range,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                                    selectedLabelColor = Color(0xFF10B981),
                                    containerColor = Slate800.copy(alpha = 0.4f),
                                    labelColor = Color(0xFF94A3B8)
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = Color.Transparent,
                                    selectedBorderColor = Color(0xFF10B981).copy(alpha = 0.3f),
                                    enabled = true,
                                    selected = uiState.selectedTimeRange == range
                                )
                            )
                        }
                    }

                    // System uptime chart
                    val history = uiState.uptimeHistory
                    if (history != null && history.samples.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.6f)),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "System Uptime History",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                TelemetryChart(
                                    data = history.samples.map {
                                        Pair(it.timestamp, (it.systemUptimeSeconds / 3600.0).toFloat())
                                    },
                                    gradientColors = uptimeGradient,
                                    yAxisLabel = "Hours",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                )
                            }
                        }

                        // Server uptime chart
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.6f)),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Server Uptime History",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                TelemetryChart(
                                    data = history.samples.map {
                                        Pair(it.timestamp, (it.serverUptimeSeconds / 3600.0).toFloat())
                                    },
                                    gradientColors = listOf(Color(0xFF14B8A6), Color(0xFF0EA5E9)),
                                    yAxisLabel = "Hours",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                )
                            }
                        }
                    }

                    // Data source indicator
                    if (history != null) {
                        Text(
                            text = "Source: ${history.source} · ${history.sampleCount} samples",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF475569),
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun UptimeInfoCard(
    icon: ImageVector,
    label: String,
    value: String,
    gradient: List<Color>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.6f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = gradient.first(),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

private fun formatUptimeLong(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h ${minutes}m"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

private fun formatTimestamp(epochSeconds: Long?): String {
    if (epochSeconds == null || epochSeconds == 0L) return "—"
    return try {
        val instant = Instant.ofEpochSecond(epochSeconds)
        val formatter = DateTimeFormatter.ofPattern("dd.MM. HH:mm")
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (_: Exception) {
        "—"
    }
}

private fun formatDuration(seconds: Double): String {
    val totalSecs = seconds.toLong()
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return when {
        mins > 0 -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}
