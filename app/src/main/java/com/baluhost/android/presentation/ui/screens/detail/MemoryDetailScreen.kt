package com.baluhost.android.presentation.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.components.TelemetryChart
import com.baluhost.android.presentation.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: MemoryDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val memGradient = listOf(Color(0xFF0EA5E9), Color(0xFF6366F1))

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Memory Details",
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
            if (uiState.isLoading && uiState.memoryHistory == null) {
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
                    // Current values
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MetricMiniCard(
                            title = "Used",
                            value = formatFileSize(uiState.currentUsed ?: 0),
                            icon = Icons.Default.Storage,
                            gradientColors = memGradient,
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            title = "Total",
                            value = formatFileSize(uiState.currentTotal ?: 0),
                            icon = Icons.Default.Storage,
                            gradientColors = listOf(Color(0xFF06B6D4), Color(0xFF0284C7)),
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            title = "Usage",
                            value = "${uiState.currentPercent?.toInt() ?: 0}%",
                            icon = Icons.Default.Storage,
                            gradientColors = listOf(Color(0xFF10B981), Color(0xFF14B8A6)),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Memory type info
                    val memInfo = buildString {
                        uiState.memoryType?.let { append(it) }
                        if (uiState.memoryType != null && uiState.memorySpeed != null) append(" ")
                        uiState.memorySpeed?.let { append("$it MT/s") }
                    }
                    if (memInfo.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0F172A).copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = memInfo,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    // Memory Usage chart
                    ChartSection(
                        title = "MEMORY USAGE",
                        subtitle = "Last Hour",
                        gradientColors = memGradient
                    ) {
                        val memData = uiState.memoryHistory?.samples?.map {
                            Pair(it.timestamp, it.percent.toFloat())
                        } ?: emptyList()

                        if (memData.isNotEmpty()) {
                            TelemetryChart(
                                data = memData,
                                gradientColors = memGradient,
                                yAxisLabel = "%",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        } else {
                            EmptyChartPlaceholder()
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

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
