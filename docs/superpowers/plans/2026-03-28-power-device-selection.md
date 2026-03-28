# Power Device Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to switch between multiple Tapo power monitoring devices in the PowerDetailScreen and configure which devices appear (aggregated) on the Dashboard panel, using the server-side plugin config as source of truth.

**Architecture:** New `PluginApi` for reading/writing plugin config. `GetEnergyDashboardUseCase` loads `panel_devices` from server and aggregates multiple devices. `GetEnergyDashboardFullUseCase` accepts a `deviceId` parameter. `PowerDetailViewModel` manages device list, selection, and panel config. UI shows FilterChips for device switching and Checkboxes for panel selection.

**Tech Stack:** Kotlin, Jetpack Compose, Retrofit, Hilt, Coroutines/Flow

---

### Task 1: Create PluginApi and DTOs

**Files:**
- Create: `app/src/main/java/com/baluhost/android/data/remote/api/PluginApi.kt`
- Create: `app/src/main/java/com/baluhost/android/data/remote/dto/PluginConfigDto.kt`

- [ ] **Step 1: Create PluginConfigDto.kt**

```kotlin
package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TapoPluginConfigDto(
    @SerializedName("panel_devices")
    val panelDevices: List<Int>
)

data class PluginConfigResponseDto(
    @SerializedName("name")
    val name: String,
    @SerializedName("config")
    val config: TapoPluginConfigDto
)

data class PluginConfigUpdateRequestDto(
    @SerializedName("config")
    val config: TapoPluginConfigDto
)
```

- [ ] **Step 2: Create PluginApi.kt**

```kotlin
package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.PluginConfigResponseDto
import com.baluhost.android.data.remote.dto.PluginConfigUpdateRequestDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

interface PluginApi {

    @GET("plugins/tapo_smart_plug/config")
    suspend fun getTapoPluginConfig(): PluginConfigResponseDto

    @PUT("plugins/tapo_smart_plug/config")
    suspend fun updateTapoPluginConfig(
        @Body request: PluginConfigUpdateRequestDto
    ): PluginConfigResponseDto
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/remote/api/PluginApi.kt app/src/main/java/com/baluhost/android/data/remote/dto/PluginConfigDto.kt
git commit -m "feat(energy): add PluginApi and DTOs for tapo plugin config"
```

---

### Task 2: Register PluginApi in NetworkModule

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/di/NetworkModule.kt:196-197`

- [ ] **Step 1: Add import and provider**

Add import at the top of the file (after the existing API imports around line 10):

```kotlin
import com.baluhost.android.data.remote.api.PluginApi
```

Add provider after the `provideSleepApi` function (after line 197):

```kotlin
    @Provides
    @Singleton
    fun providePluginApi(retrofit: Retrofit): PluginApi {
        return retrofit.create(PluginApi::class.java)
    }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/di/NetworkModule.kt
git commit -m "feat(di): register PluginApi in NetworkModule"
```

---

### Task 3: Add deviceCount to EnergyDashboard domain model

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/domain/model/EnergyDashboard.kt`

- [ ] **Step 1: Add deviceCount field**

Add `deviceCount` field with default value `1` so existing call sites don't break:

```kotlin
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/model/EnergyDashboard.kt
git commit -m "feat(energy): add deviceCount to EnergyDashboard model"
```

---

### Task 4: Update GetEnergyDashboardUseCase for multi-device aggregation

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/domain/usecase/system/GetEnergyDashboardUseCase.kt`

- [ ] **Step 1: Rewrite the UseCase**

Replace the entire file content:

```kotlin
package com.baluhost.android.domain.usecase.system

import com.baluhost.android.data.remote.api.EnergyApi
import com.baluhost.android.data.remote.api.PluginApi
import com.baluhost.android.domain.model.EnergyDashboard
import com.baluhost.android.util.Result
import javax.inject.Inject

/**
 * UseCase to get energy dashboard data for the Dashboard panel.
 * Reads panel_devices from server plugin config. Aggregates if multiple devices selected.
 */
class GetEnergyDashboardUseCase @Inject constructor(
    private val energyApi: EnergyApi,
    private val pluginApi: PluginApi
) {
    suspend operator fun invoke(): Result<EnergyDashboard> {
        return try {
            // Load panel_devices from server config
            val panelDeviceIds = try {
                pluginApi.getTapoPluginConfig().config.panelDevices
            } catch (_: Exception) {
                emptyList()
            }

            // Load device list for name lookup and fallback
            val allDevices = energyApi.getSmartDevices().devices

            // Determine which device IDs to use
            val deviceIds = if (panelDeviceIds.isNotEmpty()) {
                // Filter to only active devices that still exist
                val activeIds = allDevices.filter { it.isActive }.map { it.id }.toSet()
                panelDeviceIds.filter { it in activeIds }
            } else {
                // Fallback: first active power_monitor device
                val device = allDevices.firstOrNull {
                    it.isActive && "power_monitor" in it.capabilities
                }
                if (device != null) listOf(device.id) else emptyList()
            }

            if (deviceIds.isEmpty()) {
                return Result.Error(Exception("No active power monitoring device found"))
            }

            // Load dashboards for all selected devices
            val dashboards = deviceIds.mapNotNull { id ->
                try {
                    energyApi.getEnergyDashboard(id)
                } catch (_: Exception) {
                    null
                }
            }

            if (dashboards.isEmpty()) {
                return Result.Error(Exception("Failed to load energy data"))
            }

            // Single device - return directly
            if (dashboards.size == 1) {
                val d = dashboards.first()
                return Result.Success(
                    EnergyDashboard(
                        deviceName = d.deviceName,
                        currentWatts = d.currentWatts,
                        isOnline = d.isOnline,
                        todayKwh = d.today?.totalEnergyKwh ?: 0.0,
                        todayAvgWatts = d.today?.avgWatts ?: 0.0,
                        todayMinWatts = d.today?.minWatts ?: 0.0,
                        todayMaxWatts = d.today?.maxWatts ?: 0.0,
                        monthKwh = d.month?.totalEnergyKwh ?: 0.0,
                        deviceCount = 1
                    )
                )
            }

            // Multiple devices - aggregate
            Result.Success(
                EnergyDashboard(
                    deviceName = "${dashboards.size} Geräte",
                    currentWatts = dashboards.sumOf { it.currentWatts },
                    isOnline = dashboards.any { it.isOnline },
                    todayKwh = dashboards.sumOf { it.today?.totalEnergyKwh ?: 0.0 },
                    todayAvgWatts = dashboards.sumOf { it.today?.avgWatts ?: 0.0 },
                    todayMinWatts = dashboards.mapNotNull { it.today?.minWatts }.minOrNull() ?: 0.0,
                    todayMaxWatts = dashboards.mapNotNull { it.today?.maxWatts }.maxOrNull() ?: 0.0,
                    monthKwh = dashboards.sumOf { it.month?.totalEnergyKwh ?: 0.0 },
                    deviceCount = dashboards.size
                )
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/usecase/system/GetEnergyDashboardUseCase.kt
git commit -m "feat(energy): aggregate panel_devices in GetEnergyDashboardUseCase"
```

---

### Task 5: Update GetEnergyDashboardFullUseCase to accept deviceId

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/domain/usecase/system/GetEnergyDashboardFullUseCase.kt`

- [ ] **Step 1: Add deviceId parameter**

Replace the entire file content:

```kotlin
package com.baluhost.android.domain.usecase.system

import com.baluhost.android.data.remote.api.EnergyApi
import com.baluhost.android.domain.model.EnergyDashboardFull
import com.baluhost.android.domain.repository.MonitoringRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetEnergyDashboardFullUseCase @Inject constructor(
    private val energyApi: EnergyApi,
    private val monitoringRepository: MonitoringRepository
) {
    suspend operator fun invoke(deviceId: Int? = null): Result<EnergyDashboardFull> {
        return try {
            val resolvedId = deviceId ?: run {
                val response = energyApi.getSmartDevices()
                response.devices.firstOrNull {
                    it.isActive && "power_monitor" in it.capabilities
                }?.id ?: return Result.Error(Exception("No active power monitoring device found"))
            }

            monitoringRepository.getEnergyDashboardFull(resolvedId)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/usecase/system/GetEnergyDashboardFullUseCase.kt
git commit -m "feat(energy): accept optional deviceId in GetEnergyDashboardFullUseCase"
```

---

### Task 6: Rewrite PowerDetailViewModel with device management

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/detail/PowerDetailViewModel.kt`

- [ ] **Step 1: Rewrite ViewModel with device list, selection, and panel config**

Replace the entire file content:

```kotlin
package com.baluhost.android.presentation.ui.screens.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.data.remote.api.EnergyApi
import com.baluhost.android.data.remote.api.PluginApi
import com.baluhost.android.data.remote.dto.PluginConfigUpdateRequestDto
import com.baluhost.android.data.remote.dto.TapoPluginConfigDto
import com.baluhost.android.domain.model.EnergyDashboardFull
import com.baluhost.android.domain.usecase.system.GetEnergyDashboardFullUseCase
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PowerDetailViewModel @Inject constructor(
    private val getEnergyDashboardFullUseCase: GetEnergyDashboardFullUseCase,
    private val energyApi: EnergyApi,
    private val pluginApi: PluginApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(PowerDetailUiState())
    val uiState: StateFlow<PowerDetailUiState> = _uiState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private var pollingJob: Job? = null

    init {
        loadDevicesAndConfig()
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }

    private fun loadDevicesAndConfig() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // Load device list
                val response = energyApi.getSmartDevices()
                val powerDevices = response.devices
                    .filter { it.isActive && "power_monitor" in it.capabilities }
                    .map { PowerDevice(id = it.id, name = it.name, isOnline = it.isOnline) }

                // Load panel config
                val panelDeviceIds = try {
                    pluginApi.getTapoPluginConfig().config.panelDevices.toSet()
                } catch (_: Exception) {
                    emptySet()
                }

                // Default selected device: first device
                val selectedId = powerDevices.firstOrNull()?.id

                _uiState.value = _uiState.value.copy(
                    devices = powerDevices,
                    selectedDeviceId = selectedId,
                    panelDeviceIds = panelDeviceIds
                )

                // Load energy data for selected device
                if (selectedId != null) {
                    loadEnergyData(selectedId)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }

                startPolling()
            } catch (e: Exception) {
                Log.e("PowerDetailVM", "Failed to load devices", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    private fun loadEnergyData(deviceId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = _uiState.value.energyDashboard == null
            )

            try {
                when (val result = getEnergyDashboardFullUseCase(deviceId)) {
                    is Result.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            energyDashboard = result.data,
                            error = null
                        )
                    }
                    is Result.Error -> {
                        Log.e("PowerDetailVM", "Failed to load energy dashboard", result.exception)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = result.exception.message
                        )
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                val deviceId = _uiState.value.selectedDeviceId ?: continue
                loadEnergyData(deviceId)
            }
        }
    }

    fun selectDevice(deviceId: Int) {
        if (deviceId == _uiState.value.selectedDeviceId) return
        _uiState.value = _uiState.value.copy(
            selectedDeviceId = deviceId,
            energyDashboard = null
        )
        loadEnergyData(deviceId)
    }

    fun togglePanelDevice(deviceId: Int) {
        val current = _uiState.value.panelDeviceIds
        val updated = if (deviceId in current) current - deviceId else current + deviceId
        _uiState.value = _uiState.value.copy(
            panelDeviceIds = updated,
            isSavingPanel = true
        )

        viewModelScope.launch {
            try {
                pluginApi.updateTapoPluginConfig(
                    PluginConfigUpdateRequestDto(
                        config = TapoPluginConfigDto(panelDevices = updated.toList())
                    )
                )
                _snackbarEvent.emit("Dashboard aktualisiert")
            } catch (e: Exception) {
                Log.e("PowerDetailVM", "Failed to save panel config", e)
                // Revert on failure
                _uiState.value = _uiState.value.copy(panelDeviceIds = current)
                _snackbarEvent.emit("Speichern fehlgeschlagen")
            } finally {
                _uiState.value = _uiState.value.copy(isSavingPanel = false)
            }
        }
    }

    fun refresh() {
        val deviceId = _uiState.value.selectedDeviceId
        if (deviceId != null) {
            loadEnergyData(deviceId)
        } else {
            loadDevicesAndConfig()
        }
    }
}

data class PowerDevice(
    val id: Int,
    val name: String,
    val isOnline: Boolean
)

data class PowerDetailUiState(
    val isLoading: Boolean = false,
    val energyDashboard: EnergyDashboardFull? = null,
    val error: String? = null,
    val devices: List<PowerDevice> = emptyList(),
    val selectedDeviceId: Int? = null,
    val panelDeviceIds: Set<Int> = emptySet(),
    val isSavingPanel: Boolean = false
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/detail/PowerDetailViewModel.kt
git commit -m "feat(energy): add device selection and panel config to PowerDetailViewModel"
```

---

### Task 7: Update PowerDetailScreen with device selector and panel checkboxes

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/detail/PowerDetailScreen.kt`

- [ ] **Step 1: Add device selector and panel checkboxes to the screen**

Add these imports at the top of the file (after existing imports):

```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
```

Replace the `PowerDetailScreen` composable function (lines 27-263) with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: PowerDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            } else if (uiState.energyDashboard == null && uiState.devices.isEmpty()) {
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Device selector (only when multiple devices)
                    if (uiState.devices.size > 1) {
                        DeviceSelector(
                            devices = uiState.devices,
                            selectedDeviceId = uiState.selectedDeviceId,
                            panelDeviceIds = uiState.panelDeviceIds,
                            onSelectDevice = viewModel::selectDevice,
                            onTogglePanelDevice = viewModel::togglePanelDevice
                        )
                    }

                    val dashboard = uiState.energyDashboard
                    if (dashboard != null) {
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
private fun DeviceSelector(
    devices: List<PowerDevice>,
    selectedDeviceId: Int?,
    panelDeviceIds: Set<Int>,
    onSelectDevice: (Int) -> Unit,
    onTogglePanelDevice: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Device filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                devices.forEach { device ->
                    FilterChip(
                        selected = device.id == selectedDeviceId,
                        onClick = { onSelectDevice(device.id) },
                        label = {
                            Text(
                                text = device.name,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFF59E0B).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFFF59E0B),
                            containerColor = Color.Transparent,
                            labelColor = Color(0xFF94A3B8)
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = Color(0xFF334155),
                            selectedBorderColor = Color(0xFFF59E0B).copy(alpha = 0.5f),
                            enabled = true,
                            selected = device.id == selectedDeviceId
                        )
                    )
                }
            }

            // Panel checkboxes
            Text(
                text = "Dashboard Panel",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                devices.forEach { device ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Checkbox(
                            checked = device.id in panelDeviceIds,
                            onCheckedChange = { onTogglePanelDevice(device.id) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFFF59E0B),
                                uncheckedColor = Color(0xFF64748B),
                                checkmarkColor = Color.Black
                            ),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/detail/PowerDetailScreen.kt
git commit -m "feat(energy): add device selector and panel checkboxes to PowerDetailScreen"
```

---

### Task 8: Build and verify

- [ ] **Step 1: Build the project**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any compilation errors**

If there are import issues or missing references, fix them. Common issues:
- Missing `rememberScrollState` import for horizontal scroll (already in existing imports via `foundation.rememberScrollState`)
- `FilterChipDefaults.filterChipBorder` may need `enabled` and `selected` parameters depending on Compose version

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix(energy): resolve compilation issues in device selection"
```
