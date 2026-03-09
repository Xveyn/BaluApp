package com.baluhost.android.presentation.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
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
fun PowerDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: PowerDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val powerGradient = listOf(Color(0xFFF59E0B), Color(0xFFEF4444))

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Power Details",
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
            if (uiState.isLoading && uiState.energyDashboard == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Sky400)
                }
            } else if (uiState.energyDashboard == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.error ?: "Keine Energiedaten verfügbar",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF64748B)
                    )
                }
            } else {
                val dashboard = uiState.energyDashboard!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Current watts - big display
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "%.1f".format(dashboard.currentWatts),
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "W",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color(0xFF94A3B8),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (dashboard.isOnline) Color(0xFF10B981).copy(alpha = 0.15f)
                                        else Color(0xFFEF4444).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = if (dashboard.isOnline) "Live" else "Offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (dashboard.isOnline) Color(0xFF10B981) else Color(0xFFEF4444),
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Stats row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MetricMiniCard(
                            title = "Heute",
                            value = "%.2f kWh".format(dashboard.todayKwh),
                            icon = Icons.Default.Bolt,
                            gradientColors = powerGradient,
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            title = "Ø Heute",
                            value = "%.0f W".format(dashboard.todayAvgWatts),
                            icon = Icons.AutoMirrored.Filled.TrendingUp,
                            gradientColors = listOf(Color(0xFF06B6D4), Color(0xFF0284C7)),
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            title = "Monat",
                            value = "%.1f kWh".format(dashboard.monthKwh),
                            icon = Icons.Default.Bolt,
                            gradientColors = listOf(Color(0xFF10B981), Color(0xFF14B8A6)),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Power chart
                    ChartSection(
                        title = "POWER CONSUMPTION",
                        subtitle = "Last 24 Hours",
                        gradientColors = powerGradient
                    ) {
                        val powerData = dashboard.hourlySamples.map {
                            Pair(it.timestamp, it.avgWatts.toFloat())
                        }

                        if (powerData.isNotEmpty()) {
                            TelemetryChart(
                                data = powerData,
                                gradientColors = powerGradient,
                                yAxisLabel = "W",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        } else {
                            EmptyChartPlaceholder()
                        }
                    }

                    // Min/Max info
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "%.0f W".format(dashboard.todayMinWatts),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF10B981)
                                )
                                Text(
                                    text = "Min",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "%.0f W".format(dashboard.todayMaxWatts),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFEF4444)
                                )
                                Text(
                                    text = "Max",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
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
