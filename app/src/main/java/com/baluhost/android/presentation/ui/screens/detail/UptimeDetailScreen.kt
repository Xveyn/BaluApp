package com.baluhost.android.presentation.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.domain.model.SleepEvent
import com.baluhost.android.domain.model.UptimeSample
import com.baluhost.android.presentation.ui.components.BaluBackground
import com.baluhost.android.presentation.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Status bar colors matching server
private val ColorOnline = Color(0xFF22C55E)
private val ColorLime = Color(0xFF84CC16)
private val ColorYellow = Color(0xFFEAB308)
private val ColorOrange = Color(0xFFF97316)
private val ColorRed = Color(0xFFEF4444)
private val ColorDarkRed = Color(0xFFDC2626)
private val ColorSoftSleep = Color(0xFF6366F1)
private val ColorSuspended = Color(0xFF7C3AED)
private val ColorNoData = Color(0xFF334155)

// Stat card accent colors
private val ServerBlue = Color(0xFF3B82F6)
private val SystemGreen = Color(0xFF22C55E)

private data class SegmentConfig(val segments: Int, val durationMs: Long)

private val SEGMENT_CONFIGS = mapOf(
    "10m" to SegmentConfig(60, 10L * 60 * 1000),
    "1h" to SegmentConfig(72, 60L * 60 * 1000),
    "24h" to SegmentConfig(90, 24L * 60 * 60 * 1000),
    "7d" to SegmentConfig(90, 7L * 24 * 60 * 60 * 1000),
)

private data class Timeslot(
    val uptimePercent: Float,
    val status: String // "online", "no-data", "soft_sleep", "suspended", "partial"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UptimeDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: UptimeDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val timeRanges = listOf("10m", "1h", "24h", "7d")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Uptime",
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
                    CircularProgressIndicator(color = SystemGreen)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val current = uiState.currentUptime

                    // Two stat cards: Server (blue) + System (green)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        UptimeStatCard(
                            label = "Server Uptime",
                            value = formatUptimeLong(current?.serverUptimeSeconds ?: 0),
                            subValue = formatTimestamp(current?.serverStartTime),
                            accentColor = ServerBlue,
                            badge = "S",
                            modifier = Modifier.weight(1f)
                        )
                        UptimeStatCard(
                            label = "System Uptime",
                            value = formatUptimeLong(current?.systemUptimeSeconds ?: 0),
                            subValue = formatTimestamp(current?.systemBootTime),
                            accentColor = SystemGreen,
                            badge = "OS",
                            modifier = Modifier.weight(1f)
                        )
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
                                    selectedContainerColor = ServerBlue.copy(alpha = 0.2f),
                                    selectedLabelColor = ServerBlue,
                                    containerColor = Slate800.copy(alpha = 0.4f),
                                    labelColor = Color(0xFF94A3B8)
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = Color(0xFF334155).copy(alpha = 0.5f),
                                    selectedBorderColor = ServerBlue.copy(alpha = 0.4f),
                                    enabled = true,
                                    selected = uiState.selectedTimeRange == range
                                )
                            )
                        }
                    }

                    // Status bars
                    val history = uiState.uptimeHistory
                    if (history != null) {
                        val timeRange = uiState.selectedTimeRange

                        // Server status bar
                        UptimeStatusBar(
                            label = "Server",
                            samples = history.samples,
                            sleepEvents = history.sleepEvents,
                            timeRange = timeRange,
                            uptimeField = "server"
                        )

                        // System status bar
                        UptimeStatusBar(
                            label = "System",
                            samples = history.samples,
                            sleepEvents = history.sleepEvents,
                            timeRange = timeRange,
                            uptimeField = "system"
                        )
                    }

                    // Incidents section
                    IncidentsSection(
                        samples = history?.samples ?: emptyList(),
                        sleepEvents = history?.sleepEvents ?: emptyList()
                    )

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

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

// --- Stat Card (matching server's StatCard with colored badge) ---

@Composable
private fun UptimeStatCard(
    label: String,
    value: String,
    subValue: String,
    accentColor: Color,
    badge: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Badge circle
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = subValue,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B)
            )
        }
    }
}

// --- Status Bar (statuspage.io style) ---

@Composable
private fun UptimeStatusBar(
    label: String,
    samples: List<UptimeSample>,
    sleepEvents: List<SleepEvent>,
    timeRange: String,
    uptimeField: String // "server" or "system"
) {
    val config = SEGMENT_CONFIGS[timeRange] ?: return
    val slots = remember(samples, sleepEvents, timeRange, uptimeField) {
        buildTimeslots(samples, sleepEvents, config, uptimeField)
    }

    val overallUptime = remember(slots) {
        val monitored = slots.filter { it.status != "no-data" }
        if (monitored.isEmpty()) null
        else monitored.sumOf { it.uptimePercent.toDouble() } / monitored.size
    }

    val dotColor = when {
        overallUptime == null -> ColorNoData
        overallUptime >= 99.5 -> ColorOnline
        overallUptime >= 95 -> ColorLime
        else -> ColorRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, Slate800.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
                Text(
                    text = if (overallUptime != null) "%.2f%% uptime".format(overallUptime) else "No data",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Segment bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                slots.forEach { slot ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(getSlotColor(slot))
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Time labels
            val (leftLabel, rightLabel) = getTimeLabels(timeRange)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = leftLabel, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Color(0xFF64748B))
                Text(text = rightLabel, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Color(0xFF64748B))
            }
        }
    }
}

// --- Incidents Section ---

@Composable
private fun IncidentsSection(
    samples: List<UptimeSample>,
    sleepEvents: List<SleepEvent>
) {
    // Detect restarts from server uptime drops
    val restarts = remember(samples) {
        if (samples.size < 2) emptyList()
        else {
            val events = mutableListOf<Pair<Long, Long>>() // timestamp, previous session duration
            for (i in 1 until samples.size) {
                if (samples[i].serverUptimeSeconds < samples[i - 1].serverUptimeSeconds) {
                    events.add(Pair(samples[i].timestamp, samples[i - 1].serverUptimeSeconds))
                }
            }
            events
        }
    }

    val hasIncidents = restarts.isNotEmpty() || sleepEvents.isNotEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.6f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (hasIncidents) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (hasIncidents) ColorOrange else SystemGreen,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Incidents",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            if (!hasIncidents) {
                // No incidents card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SystemGreen.copy(alpha = 0.05f),
                    border = BorderStroke(1.dp, SystemGreen.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = SystemGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "No incidents in this period",
                            style = MaterialTheme.typography.bodySmall,
                            color = SystemGreen
                        )
                    }
                }
            } else {
                // Restart events
                restarts.forEach { (timestamp, prevDuration) ->
                    IncidentCard(
                        accentColor = ColorOrange,
                        badge = "Restart",
                        time = formatTimestamp(timestamp),
                        detail = "Previous session: ${formatUptimeLong(prevDuration)}"
                    )
                }
                // Sleep events
                sleepEvents.forEach { event ->
                    val isSuspend = event.newState == "true_suspend"
                    IncidentCard(
                        accentColor = if (isSuspend) ColorSuspended else ColorSoftSleep,
                        badge = if (isSuspend) "Suspended" else "Soft Sleep",
                        time = formatTimestamp(event.timestamp),
                        detail = if (event.durationSeconds != null) "Duration: ${formatDuration(event.durationSeconds)}" else "${event.previousState} → ${event.newState}"
                    )
                }
            }
        }
    }
}

@Composable
private fun IncidentCard(
    accentColor: Color,
    badge: String,
    time: String,
    detail: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = accentColor.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = time, style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = accentColor.copy(alpha = 0.2f)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// --- Slot computation (matching server logic) ---

private fun buildTimeslots(
    samples: List<UptimeSample>,
    sleepEvents: List<SleepEvent>,
    config: SegmentConfig,
    uptimeField: String
): List<Timeslot> {
    val now = if (samples.isNotEmpty()) {
        maxOf(System.currentTimeMillis(), samples.last().timestamp * 1000)
    } else {
        System.currentTimeMillis()
    }
    val rangeStart = now - config.durationMs
    val bucketDuration = config.durationMs / config.segments

    val parsed = samples
        .map { it to (it.timestamp * 1000) }
        .filter { it.second in rangeStart..now }
        .sortedBy { it.second }

    val sleepParsed = sleepEvents
        .map { it to (it.timestamp * 1000) }
        .sortedBy { it.second }

    return List(config.segments) { i ->
        val bucketStart = rangeStart + i * bucketDuration
        val bucketEnd = bucketStart + bucketDuration

        val bucketSamples = parsed.filter { it.second in bucketStart until bucketEnd }

        // Determine sleep state for this bucket
        val sleepState = getDominantSleepState(sleepParsed, bucketStart, bucketEnd)

        if (bucketSamples.isEmpty()) {
            when (sleepState) {
                "true_suspend" -> Timeslot(0f, "suspended")
                "soft_sleep" -> Timeslot(100f, "soft_sleep")
                else -> Timeslot(0f, "no-data")
            }
        } else {
            // Detect restarts
            var restartCount = 0
            val uptimeValues = bucketSamples.map {
                if (uptimeField == "server") it.first.serverUptimeSeconds else it.first.systemUptimeSeconds
            }
            for (j in 1 until uptimeValues.size) {
                if (uptimeValues[j] < uptimeValues[j - 1]) restartCount++
            }

            when {
                sleepState == "true_suspend" -> Timeslot(50f, "suspended")
                sleepState == "soft_sleep" -> Timeslot(100f, if (restartCount > 0) "partial" else "soft_sleep")
                restartCount > 0 -> Timeslot(maxOf(0f, (1f - restartCount * 0.2f) * 100f), "partial")
                else -> Timeslot(100f, "online")
            }
        }
    }
}

private fun getDominantSleepState(
    sleepParsed: List<Pair<SleepEvent, Long>>,
    bucketStart: Long,
    bucketEnd: Long
): String {
    if (sleepParsed.isEmpty()) return "awake"

    // State at bucket start
    var stateAtStart = "awake"
    for (i in sleepParsed.indices.reversed()) {
        if (sleepParsed[i].second <= bucketStart) {
            stateAtStart = sleepParsed[i].first.newState
            break
        }
    }

    val bucketEvents = sleepParsed.filter { it.second in (bucketStart + 1) until bucketEnd }
    if (bucketEvents.isEmpty()) return stateAtStart

    // Calculate time in each state
    val timeIn = mutableMapOf("awake" to 0L, "soft_sleep" to 0L, "true_suspend" to 0L)
    var currentState = stateAtStart
    var currentTime = bucketStart

    for ((event, time) in bucketEvents) {
        timeIn[currentState] = (timeIn[currentState] ?: 0) + (time - currentTime)
        currentState = event.newState
        currentTime = time
    }
    timeIn[currentState] = (timeIn[currentState] ?: 0) + (bucketEnd - currentTime)

    val suspendTime = timeIn["true_suspend"] ?: 0
    val sleepTime = timeIn["soft_sleep"] ?: 0

    return when {
        suspendTime > 0 && suspendTime >= sleepTime -> "true_suspend"
        sleepTime > 0 -> "soft_sleep"
        else -> "awake"
    }
}

private fun getSlotColor(slot: Timeslot): Color = when {
    slot.status == "no-data" -> ColorNoData
    slot.status == "soft_sleep" -> ColorSoftSleep
    slot.status == "suspended" -> ColorSuspended
    slot.uptimePercent >= 100f -> ColorOnline
    slot.uptimePercent >= 95f -> ColorLime
    slot.uptimePercent >= 75f -> ColorYellow
    slot.uptimePercent >= 50f -> ColorOrange
    slot.uptimePercent > 0f -> ColorRed
    else -> ColorDarkRed
}

private fun getTimeLabels(timeRange: String): Pair<String, String> = when (timeRange) {
    "10m" -> "10 min ago" to "Now"
    "1h" -> "1 hour ago" to "Now"
    "24h" -> "24 hours ago" to "Now"
    "7d" -> "7 days ago" to "Today"
    else -> "" to ""
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
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
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
