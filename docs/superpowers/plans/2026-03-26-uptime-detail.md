# Uptime Detail Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Uptime Detail screen that opens when the user taps the UPTIME panel on the Dashboard, showing current server/system uptime, boot times, and a historical uptime chart with time-range selector — matching the existing CPU/Memory detail pattern.

**Architecture:** Clean Architecture layers — Retrofit `MonitoringApi` (extend existing) → `MonitoringRepository` (extend existing) → `GetUptimeHistoryUseCase` + `GetCurrentUptimeUseCase` → `UptimeDetailViewModel` → `UptimeDetailScreen` Composable. Dashboard wiring adds `onNavigateToUptimeDetail` callback following the same double-tap-navigate pattern as CPU/Memory/Storage/Power panels.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Retrofit 2, Hilt DI, StateFlow, TelemetryChart (existing composable)

---

## Server API Reference

These endpoints already exist on the BaluHost server:

- `GET /api/monitoring/uptime/current` → `CurrentUptimeResponse`
  ```json
  {
    "timestamp": "2026-03-26T12:34:56.789+00:00",
    "server_uptime_seconds": 86400,
    "system_uptime_seconds": 345600,
    "server_start_time": "2026-03-25T12:34:56+00:00",
    "system_boot_time": "2026-03-22T12:34:56+00:00"
  }
  ```

- `GET /api/monitoring/uptime/history?time_range=1h&source=auto&limit=1000` → `UptimeHistoryResponse`
  ```json
  {
    "samples": [
      {
        "timestamp": "2026-03-26T12:00:00+00:00",
        "server_uptime_seconds": 82800,
        "system_uptime_seconds": 342000,
        "server_start_time": "2026-03-25T12:34:56+00:00",
        "system_boot_time": "2026-03-22T12:34:56+00:00"
      }
    ],
    "sleep_events": [
      {
        "timestamp": "2026-03-26T10:00:00+00:00",
        "previous_state": "awake",
        "new_state": "soft_sleep",
        "duration_seconds": 120.0
      }
    ],
    "sample_count": 12,
    "source": "memory"
  }
  ```

  Query params: `time_range` = "10m" | "1h" | "24h" | "7d", `source` = "auto" | "memory" | "database", `limit` = 1–10000

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `data/remote/api/MonitoringApi.kt` | Add uptime current + history endpoints |
| Modify | `data/remote/dto/MonitoringDto.kt` | Add uptime DTOs |
| Modify | `domain/model/MonitoringHistory.kt` | Add uptime domain models |
| Modify | `domain/repository/MonitoringRepository.kt` | Add uptime repository methods |
| Modify | `data/repository/MonitoringRepositoryImpl.kt` | Implement uptime repository methods |
| Create | `domain/usecase/system/GetCurrentUptimeUseCase.kt` | Current uptime use case |
| Create | `domain/usecase/system/GetUptimeHistoryUseCase.kt` | Uptime history use case |
| Create | `presentation/ui/screens/detail/UptimeDetailViewModel.kt` | ViewModel + UiState |
| Create | `presentation/ui/screens/detail/UptimeDetailScreen.kt` | Composable detail screen |
| Modify | `presentation/navigation/Screen.kt` | Add `UptimeDetail` route |
| Modify | `presentation/navigation/NavGraph.kt` | Wire composable for uptime route |
| Modify | `presentation/ui/screens/main/MainScreen.kt` | Pass `onNavigateToUptimeDetail` to DashboardScreen |
| Modify | `presentation/ui/screens/dashboard/DashboardScreen.kt` | Add callback + enable double-tap navigation on UPTIME card |

All paths relative to `app/src/main/java/com/baluhost/android/` unless noted.

---

### Task 1: API Interface & DTOs

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/remote/api/MonitoringApi.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/remote/dto/MonitoringDto.kt`

- [ ] **Step 1: Add uptime DTOs to MonitoringDto.kt**

Append at the end of the file (after line 43):

```kotlin

data class UptimeSampleDto(
    @SerializedName("timestamp")
    val timestamp: String,
    @SerializedName("server_uptime_seconds")
    val serverUptimeSeconds: Long,
    @SerializedName("system_uptime_seconds")
    val systemUptimeSeconds: Long,
    @SerializedName("server_start_time")
    val serverStartTime: String,
    @SerializedName("system_boot_time")
    val systemBootTime: String
)

data class SleepEventDto(
    @SerializedName("timestamp")
    val timestamp: String,
    @SerializedName("previous_state")
    val previousState: String,
    @SerializedName("new_state")
    val newState: String,
    @SerializedName("duration_seconds")
    val durationSeconds: Double?
)

data class CurrentUptimeResponseDto(
    @SerializedName("timestamp")
    val timestamp: String,
    @SerializedName("server_uptime_seconds")
    val serverUptimeSeconds: Long,
    @SerializedName("system_uptime_seconds")
    val systemUptimeSeconds: Long,
    @SerializedName("server_start_time")
    val serverStartTime: String,
    @SerializedName("system_boot_time")
    val systemBootTime: String
)

data class UptimeHistoryResponseDto(
    @SerializedName("samples")
    val samples: List<UptimeSampleDto>,
    @SerializedName("sleep_events")
    val sleepEvents: List<SleepEventDto>,
    @SerializedName("sample_count")
    val sampleCount: Int,
    @SerializedName("source")
    val source: String
)
```

- [ ] **Step 2: Add uptime endpoints to MonitoringApi.kt**

Add these two methods after the existing `getMemoryHistory` method (after line 18):

```kotlin

    @GET("monitoring/uptime/current")
    suspend fun getUptimeCurrent(): CurrentUptimeResponseDto

    @GET("monitoring/uptime/history")
    suspend fun getUptimeHistory(
        @Query("time_range") timeRange: String = "1h",
        @Query("source") source: String = "auto",
        @Query("limit") limit: Int = 1000
    ): UptimeHistoryResponseDto
```

Add the necessary imports at the top of `MonitoringApi.kt`:

```kotlin
import com.baluhost.android.data.remote.dto.CurrentUptimeResponseDto
import com.baluhost.android.data.remote.dto.UptimeHistoryResponseDto
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/remote/api/MonitoringApi.kt app/src/main/java/com/baluhost/android/data/remote/dto/MonitoringDto.kt
git commit -m "feat(uptime): add Retrofit endpoints and DTOs for uptime current/history"
```

---

### Task 2: Domain Models

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/domain/model/MonitoringHistory.kt`

- [ ] **Step 1: Add uptime domain models to MonitoringHistory.kt**

Append at the end of the file (after line 30):

```kotlin

data class UptimeSample(
    val timestamp: Long,
    val serverUptimeSeconds: Long,
    val systemUptimeSeconds: Long,
    val serverStartTime: Long,
    val systemBootTime: Long
)

data class SleepEvent(
    val timestamp: Long,
    val previousState: String,
    val newState: String,
    val durationSeconds: Double?
)

data class CurrentUptime(
    val timestamp: Long,
    val serverUptimeSeconds: Long,
    val systemUptimeSeconds: Long,
    val serverStartTime: Long,
    val systemBootTime: Long
)

data class UptimeHistory(
    val samples: List<UptimeSample>,
    val sleepEvents: List<SleepEvent>,
    val sampleCount: Int,
    val source: String
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/model/MonitoringHistory.kt
git commit -m "feat(uptime): add domain models for uptime and sleep events"
```

---

### Task 3: Repository Layer

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/domain/repository/MonitoringRepository.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/repository/MonitoringRepositoryImpl.kt`

- [ ] **Step 1: Add uptime methods to MonitoringRepository interface**

Add these two methods inside the `MonitoringRepository` interface (after line 11, before the closing `}`):

```kotlin
    suspend fun getCurrentUptime(): Result<CurrentUptime>
    suspend fun getUptimeHistory(timeRange: String = "1h"): Result<UptimeHistory>
```

Add imports at the top:

```kotlin
import com.baluhost.android.domain.model.CurrentUptime
import com.baluhost.android.domain.model.UptimeHistory
```

- [ ] **Step 2: Add mapping functions and implementation to MonitoringRepositoryImpl**

Add imports at the top of `MonitoringRepositoryImpl.kt`:

```kotlin
import com.baluhost.android.data.remote.dto.UptimeSampleDto
import com.baluhost.android.data.remote.dto.SleepEventDto
import com.baluhost.android.domain.model.CurrentUptime
import com.baluhost.android.domain.model.UptimeHistory
import com.baluhost.android.domain.model.UptimeSample
import com.baluhost.android.domain.model.SleepEvent
```

Add these two methods inside the `MonitoringRepositoryImpl` class (after `getEnergyDashboardFull`, before the closing `}`):

```kotlin

    override suspend fun getCurrentUptime(): Result<CurrentUptime> {
        return try {
            val dto = monitoringApi.getUptimeCurrent()
            Result.Success(
                CurrentUptime(
                    timestamp = parseIsoTimestamp(dto.timestamp),
                    serverUptimeSeconds = dto.serverUptimeSeconds,
                    systemUptimeSeconds = dto.systemUptimeSeconds,
                    serverStartTime = parseIsoTimestamp(dto.serverStartTime),
                    systemBootTime = parseIsoTimestamp(dto.systemBootTime)
                )
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun getUptimeHistory(timeRange: String): Result<UptimeHistory> {
        return try {
            val dto = monitoringApi.getUptimeHistory(timeRange)
            Result.Success(
                UptimeHistory(
                    samples = dto.samples.map { it.toUptimeDomain() },
                    sleepEvents = dto.sleepEvents.map { it.toDomain() },
                    sampleCount = dto.sampleCount,
                    source = dto.source
                )
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
```

Add these mapping extension functions at the bottom of the file (after the existing `parseIsoTimestamp` function):

```kotlin

private fun UptimeSampleDto.toUptimeDomain() = UptimeSample(
    timestamp = parseIsoTimestamp(timestamp),
    serverUptimeSeconds = serverUptimeSeconds,
    systemUptimeSeconds = systemUptimeSeconds,
    serverStartTime = parseIsoTimestamp(serverStartTime),
    systemBootTime = parseIsoTimestamp(systemBootTime)
)

private fun SleepEventDto.toDomain() = SleepEvent(
    timestamp = parseIsoTimestamp(timestamp),
    previousState = previousState,
    newState = newState,
    durationSeconds = durationSeconds
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/repository/MonitoringRepository.kt app/src/main/java/com/baluhost/android/data/repository/MonitoringRepositoryImpl.kt
git commit -m "feat(uptime): add repository methods for current uptime and history"
```

---

### Task 4: Use Cases

**Files:**
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/system/GetCurrentUptimeUseCase.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/system/GetUptimeHistoryUseCase.kt`

- [ ] **Step 1: Create GetCurrentUptimeUseCase**

```kotlin
package com.baluhost.android.domain.usecase.system

import com.baluhost.android.domain.model.CurrentUptime
import com.baluhost.android.domain.repository.MonitoringRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetCurrentUptimeUseCase @Inject constructor(
    private val monitoringRepository: MonitoringRepository
) {
    suspend operator fun invoke(): Result<CurrentUptime> {
        return monitoringRepository.getCurrentUptime()
    }
}
```

- [ ] **Step 2: Create GetUptimeHistoryUseCase**

```kotlin
package com.baluhost.android.domain.usecase.system

import com.baluhost.android.domain.model.UptimeHistory
import com.baluhost.android.domain.repository.MonitoringRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetUptimeHistoryUseCase @Inject constructor(
    private val monitoringRepository: MonitoringRepository
) {
    suspend operator fun invoke(timeRange: String = "1h"): Result<UptimeHistory> {
        return monitoringRepository.getUptimeHistory(timeRange)
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/usecase/system/GetCurrentUptimeUseCase.kt app/src/main/java/com/baluhost/android/domain/usecase/system/GetUptimeHistoryUseCase.kt
git commit -m "feat(uptime): add use cases for current uptime and history"
```

---

### Task 5: UptimeDetailViewModel

**Files:**
- Create: `app/src/main/java/com/baluhost/android/presentation/ui/screens/detail/UptimeDetailViewModel.kt`

- [ ] **Step 1: Create UptimeDetailViewModel with UiState**

```kotlin
package com.baluhost.android.presentation.ui.screens.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.domain.model.CurrentUptime
import com.baluhost.android.domain.model.UptimeHistory
import com.baluhost.android.domain.usecase.system.GetCurrentUptimeUseCase
import com.baluhost.android.domain.usecase.system.GetUptimeHistoryUseCase
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UptimeDetailViewModel @Inject constructor(
    private val getCurrentUptimeUseCase: GetCurrentUptimeUseCase,
    private val getUptimeHistoryUseCase: GetUptimeHistoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(UptimeDetailUiState())
    val uiState: StateFlow<UptimeDetailUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        loadData()
        startPolling()
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                loadData()
            }
        }
    }

    fun selectTimeRange(timeRange: String) {
        _uiState.value = _uiState.value.copy(selectedTimeRange = timeRange)
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.currentUptime == null)

            try {
                val currentResult = getCurrentUptimeUseCase()
                val historyResult = getUptimeHistoryUseCase(_uiState.value.selectedTimeRange)

                val current = when (currentResult) {
                    is Result.Success -> currentResult.data
                    is Result.Error -> {
                        Log.e("UptimeDetailVM", "Failed to load current uptime", currentResult.exception)
                        null
                    }
                    else -> null
                }

                val history = when (historyResult) {
                    is Result.Success -> historyResult.data
                    is Result.Error -> {
                        Log.e("UptimeDetailVM", "Failed to load uptime history", historyResult.exception)
                        null
                    }
                    else -> null
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentUptime = current ?: _uiState.value.currentUptime,
                    uptimeHistory = history ?: _uiState.value.uptimeHistory,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun refresh() {
        loadData()
    }
}

data class UptimeDetailUiState(
    val isLoading: Boolean = false,
    val currentUptime: CurrentUptime? = null,
    val uptimeHistory: UptimeHistory? = null,
    val selectedTimeRange: String = "1h",
    val error: String? = null
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/detail/UptimeDetailViewModel.kt
git commit -m "feat(uptime): add UptimeDetailViewModel with polling and time range selection"
```

---

### Task 6: UptimeDetailScreen Composable

**Files:**
- Create: `app/src/main/java/com/baluhost/android/presentation/ui/screens/detail/UptimeDetailScreen.kt`

- [ ] **Step 1: Create UptimeDetailScreen**

This follows the same visual pattern as `CpuDetailScreen.kt` — scaffold with top bar, gradient stat cards, and a `TelemetryChart` for history.

```kotlin
package com.baluhost.android.presentation.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Storage
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
                                    timestamps = history.samples.map { it.timestamp },
                                    values = history.samples.map { it.systemUptimeSeconds / 3600.0 },
                                    label = "System (hours)",
                                    color = Color(0xFF10B981),
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
                                    timestamps = history.samples.map { it.timestamp },
                                    values = history.samples.map { it.serverUptimeSeconds / 3600.0 },
                                    label = "Server (hours)",
                                    color = Color(0xFF14B8A6),
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/detail/UptimeDetailScreen.kt
git commit -m "feat(uptime): add UptimeDetailScreen composable with charts and sleep events"
```

---

### Task 7: Navigation Wiring

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/navigation/Screen.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/main/MainScreen.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt`

- [ ] **Step 1: Add UptimeDetail route to Screen.kt**

Add after line 34 (`object SharesDetail : Screen("shares_detail")`):

```kotlin
    object UptimeDetail : Screen("uptime_detail")
```

- [ ] **Step 2: Add UptimeDetail composable to NavGraph.kt**

Add import at top of `NavGraph.kt`:

```kotlin
import com.baluhost.android.presentation.ui.screens.detail.UptimeDetailScreen
```

Add this composable block after the `MemoryDetail` composable block (after line 200):

```kotlin

        composable(Screen.UptimeDetail.route) {
            UptimeDetailScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
```

- [ ] **Step 3: Add onNavigateToUptimeDetail callback to DashboardScreen**

In `DashboardScreen.kt`, add the callback parameter to the function signature. Find this block (around line 64-68):

```kotlin
    onNavigateToCpuDetail: () -> Unit = {},
    onNavigateToMemoryDetail: () -> Unit = {},
    onNavigateToPowerDetail: () -> Unit = {},
    onNavigateToStorageDetail: () -> Unit = {},
    onNavigateToSharesDetail: () -> Unit = {},
```

Add after `onNavigateToSharesDetail`:

```kotlin
    onNavigateToUptimeDetail: () -> Unit = {},
```

- [ ] **Step 4: Enable double-tap navigation on the UPTIME card**

In `DashboardScreen.kt`, find the UPTIME `SystemMetricCard` block (around lines 335-347):

```kotlin
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
```

Replace the `onClick` lambda to match the pattern used by CPU/Memory/Storage cards:

```kotlin
                                onClick = {
                                    if (activatedCard == "uptime") {
                                        activatedCard = null
                                        onNavigateToUptimeDetail()
                                    } else {
                                        activatedCard = "uptime"
                                    }
                                },
```

- [ ] **Step 5: Wire callback in MainScreen.kt**

In `MainScreen.kt`, find the `DashboardScreen(` call and the existing navigation callbacks (around lines 73-90). Add after the `onNavigateToSharesDetail` callback:

```kotlin
                        onNavigateToUptimeDetail = {
                            parentNavController.navigate(Screen.UptimeDetail.route)
                        },
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/navigation/Screen.kt app/src/main/java/com/baluhost/android/presentation/navigation/NavGraph.kt app/src/main/java/com/baluhost/android/presentation/ui/screens/main/MainScreen.kt app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt
git commit -m "feat(uptime): wire navigation from dashboard UPTIME card to detail screen"
```

---

### Task 8: Build Verification

- [ ] **Step 1: Run the build**

```bash
cd "D:/Programme (x86)/BaluApp" && ./gradlew assembleDebug 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`

If there are compile errors, fix them and re-run.

- [ ] **Step 2: Commit any fixes**

Only if fixes were needed:

```bash
git add -A && git commit -m "fix(uptime): resolve build errors"
```
