package com.baluhost.android.presentation.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.components.TelemetryChart
import com.baluhost.android.presentation.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpuDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: CpuDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val cpuGradient = listOf(Color(0xFF8B5CF6), Color(0xFFD946EF))

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "CPU Details",
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
            if (uiState.isLoading && uiState.cpuHistory == null) {
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
                    // Current values cards
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MetricMiniCard(
                            title = "Usage",
                            value = "${uiState.currentUsage?.toInt() ?: 0}%",
                            icon = Icons.Default.Memory,
                            gradientColors = cpuGradient,
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            title = "Temperature",
                            value = uiState.currentTemp?.let { "${"%.0f".format(it)}\u00B0C" } ?: "N/A",
                            icon = Icons.Default.Thermostat,
                            gradientColors = listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            title = "Frequency",
                            value = uiState.currentFreq?.let {
                                if (it >= 1000) "${"%.1f".format(it / 1000)} GHz"
                                else "${it.toInt()} MHz"
                            } ?: "N/A",
                            icon = Icons.Default.Speed,
                            gradientColors = listOf(Color(0xFF06B6D4), Color(0xFF0284C7)),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // CPU model info
                    uiState.cpuModel?.let { model ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0F172A).copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = model,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF94A3B8)
                                )
                                uiState.cores?.let { cores ->
                                    Text(
                                        text = "\u2022 $cores Cores",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }

                    // CPU Usage chart
                    ChartSection(
                        title = "CPU USAGE",
                        subtitle = "Last Hour",
                        gradientColors = cpuGradient
                    ) {
                        val cpuData = uiState.cpuHistory?.samples?.map {
                            Pair(it.timestamp, it.usagePercent.toFloat())
                        } ?: emptyList()

                        if (cpuData.isNotEmpty()) {
                            TelemetryChart(
                                data = cpuData,
                                gradientColors = cpuGradient,
                                yAxisLabel = "%",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        } else {
                            EmptyChartPlaceholder()
                        }
                    }

                    // CPU Temperature chart (if data available)
                    val tempData = uiState.cpuHistory?.samples
                        ?.filter { it.temperatureCelsius != null }
                        ?.map { Pair(it.timestamp, it.temperatureCelsius!!.toFloat()) }
                        ?: emptyList()

                    if (tempData.isNotEmpty()) {
                        ChartSection(
                            title = "CPU TEMPERATURE",
                            subtitle = "Last Hour",
                            gradientColors = listOf(Color(0xFFF59E0B), Color(0xFFEF4444))
                        ) {
                            TelemetryChart(
                                data = tempData,
                                gradientColors = listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
                                yAxisLabel = "\u00B0C",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
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

@Composable
internal fun MetricMiniCard(
    title: String,
    value: String,
    icon: ImageVector,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(gradientColors)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B)
            )
        }
    }
}

@Composable
internal fun ChartSection(
    title: String,
    subtitle: String,
    gradientColors: List<Color>,
    content: @Composable () -> Unit
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = gradientColors.first().copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "Live",
                        style = MaterialTheme.typography.labelSmall,
                        color = gradientColors.first(),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            content()
        }
    }
}

@Composable
internal fun EmptyChartPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Keine Daten verfügbar",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF64748B)
        )
    }
}
