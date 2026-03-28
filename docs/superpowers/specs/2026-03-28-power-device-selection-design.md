# Power Device Selection & Multi-Device Dashboard

## Problem

The Baluhost server supports multiple Tapo power monitoring devices (each with its own `device_id`), but the Android app always auto-selects the first active device via `.firstOrNull()`. There is no UI to switch between devices or configure which devices appear on the Dashboard panel.

## Solution

Use the existing server-side plugin config (`TapoPluginConfig.panel_devices`) as the single source of truth. Add device selection UI to the PowerDetailScreen and aggregate data for the Dashboard card.

## Approach

**Server-Config driven (Ansatz B):** Read and write `panel_devices` via the existing plugin config endpoints. App and Web dashboard stay synchronized.

## API Integration

### Existing Endpoints (already used)
- `GET /smart-devices/` — list all devices with id, name, capabilities, isActive, isOnline
- `GET /energy/dashboard/{device_id}` — full energy dashboard for one device

### New Endpoints (to add in app)
- `GET /plugins/tapo_smart_plug/config` — returns `{ config: { panel_devices: [1, 3] }, schema: ... }`
- `PUT /plugins/tapo_smart_plug/config` — sends `{ config: { panel_devices: [1, 3] } }`

Both are admin-only endpoints that already exist on the server.

## New Files

### `PluginApi.kt`
Retrofit interface:
- `GET("plugins/tapo_smart_plug/config")` → `PluginConfigResponseDto`
- `PUT("plugins/tapo_smart_plug/config")` with `PluginConfigUpdateRequestDto` → `PluginConfigResponseDto`

### `PluginConfigDto.kt`
DTOs:
- `PluginConfigResponseDto(name: String, config: TapoPluginConfigDto, schema: JsonObject?)`
- `TapoPluginConfigDto(panelDevices: List<Int>)`
- `PluginConfigUpdateRequestDto(config: TapoPluginConfigDto)`

## Modified Files

### `NetworkModule.kt`
- Provide `PluginApi` via Retrofit (same pattern as existing APIs)

### `EnergyDashboard.kt` (domain model)
- Add `deviceCount: Int` field (number of aggregated devices)

### `GetEnergyDashboardUseCase.kt`
Current: fetches first device, returns single dashboard.
New behavior:
1. Load `panel_devices` from `GET /plugins/tapo_smart_plug/config`
2. Load device list from `GET /smart-devices/`
3. If `panel_devices` is empty → fallback to `firstOrNull()` (backwards compat)
4. If 1 device → load dashboard for that device
5. If multiple → load dashboard for each, aggregate:
   - `currentWatts` = sum
   - `todayKwh` = sum
   - `monthKwh` = sum
   - `todayAvgWatts` = sum of averages
   - `todayMinWatts` = min of mins
   - `todayMaxWatts` = max of maxs
   - `isOnline` = true if any device online
   - `deviceName` = "N Geräte" or comma-separated names
   - `deviceCount` = number of devices

### `GetEnergyDashboardFullUseCase.kt`
- Accept optional `deviceId: Int?` parameter
- If provided → load dashboard for that specific device
- If null → same auto-discovery as before (first active power_monitor device)

### `PowerDetailViewModel.kt`
New state management:
- Load device list + plugin config on init
- Expose `devices: List<IoTDeviceDto>` (filtered to active power_monitor)
- Expose `selectedDeviceId: Int` (currently viewed device)
- Expose `panelDeviceIds: Set<Int>` (devices shown on Dashboard)
- `selectDevice(deviceId)` → reload energy dashboard for that device
- `togglePanelDevice(deviceId)` → update `panelDeviceIds`, PUT to server

### `PowerDetailUiState`
Extend with:
```kotlin
data class PowerDetailUiState(
    val isLoading: Boolean = false,
    val energyDashboard: EnergyDashboardFull? = null,
    val error: String? = null,
    val devices: List<PowerDevice> = emptyList(),       // all power_monitor devices
    val selectedDeviceId: Int? = null,                   // currently viewed
    val panelDeviceIds: Set<Int> = emptySet(),           // dashboard panel selection
    val isSavingPanel: Boolean = false                   // saving indicator
)

data class PowerDevice(
    val id: Int,
    val name: String,
    val isOnline: Boolean
)
```

### `PowerDetailScreen.kt`
UI additions (only visible when `devices.size > 1`):

**Device selector:** Horizontally scrollable `FilterChip` row below TopAppBar. Selected device = filled style. Tap switches the energy dashboard view.

**Panel checkboxes:** Small row with checkboxes per device. Checked = device in `panelDeviceIds`. Change triggers immediate `PUT` to server with snackbar confirmation.

Rest of screen (current watts, chart, min/max, stats) remains unchanged — always shows data for `selectedDeviceId`.

## Dashboard Card Behavior

**PowerConsumptionCard composable:** No changes needed. It receives `EnergyDashboard?` and displays it.

**Data changes via UseCase:**
- 0 panel_devices → no energy data shown (null)
- 1 panel_device → single device data (like today)
- N panel_devices → aggregated sum with `deviceCount > 1`

The `deviceName` field naturally communicates what's being shown ("NAS Monitor" vs "2 Geräte").

## Error Handling

- Plugin config not loadable → fallback to `firstOrNull()` behavior
- No active devices → "Keine Energiedaten" message (unchanged)
- PUT fails → snackbar "Speichern fehlgeschlagen", revert checkbox state
- Individual device dashboard fails during aggregation → skip that device, aggregate remaining
