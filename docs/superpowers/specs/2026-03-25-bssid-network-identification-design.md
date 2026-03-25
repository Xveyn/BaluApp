# BSSID-based Home Network Identification — Design Spec

**Date:** 2026-03-25
**Status:** Approved
**Goal:** Enable the BaluApp to instantly detect whether the phone is on the home network (LAN) or external, using the WiFi BSSID captured during QR pairing. When external, either auto-connect VPN or prompt the user (configurable).

## Context

The app already has `NetworkStateManager` which detects home network via IP subnet comparison. This works but requires network I/O and has edge cases (same subnet on different networks). BSSID comparison is instant (< 1ms), deterministic, and requires no network I/O.

### Existing Components (relevant)

| Component | Location | Role |
|---|---|---|
| `NetworkStateManager` | `util/NetworkStateManager.kt` | Home network detection via subnet comparison, VPN detection |
| `PreferencesManager` | `data/local/datastore/PreferencesManager.kt` | Central DataStore for all preferences |
| `ServerConnectivityChecker` | `data/network/ServerConnectivityChecker.kt` | Server reachability probe via OkHttp |
| `NetworkMonitor` (interface) | `util/NetworkMonitor.kt` | WiFi/connectivity state flows |
| `QrScannerViewModel` | `presentation/ui/screens/qrscanner/QrScannerViewModel.kt` | QR registration flow |
| `RegisterDeviceUseCase` | `domain/usecase/auth/RegisterDeviceUseCase.kt` | Device registration + credential storage |
| `SettingsViewModel` | `presentation/ui/screens/settings/SettingsViewModel.kt` | Settings state management |
| `SettingsScreen` | `presentation/ui/screens/settings/SettingsScreen.kt` | Settings Compose UI |
| `DashboardViewModel` | `presentation/ui/screens/dashboard/DashboardViewModel.kt` | Dashboard, already observes `NetworkStateManager` |
| `VpnViewModel` | `presentation/ui/screens/vpn/VpnViewModel.kt` | VPN connect/disconnect |

### Architecture Constraints

- **Hilt DI** throughout — all new classes must be `@Singleton` with `@Inject constructor`, no `object` singletons
- **Single DataStore** — all preferences go through `PreferencesManager`, no separate DataStore instances
- **Compose + ViewModel** — UI via Jetpack Compose, logic in `@HiltViewModel` classes
- **No ViewModel-to-ViewModel coupling** — use `SharedFlow` events consumed by Composables that have access to both ViewModels

## Design

### 1. BssidReader (new)

**File:** `app/src/main/java/com/baluhost/android/util/BssidReader.kt`

Hilt singleton. Single responsibility: read and normalize the current WiFi BSSID.

```kotlin
@Singleton
class BssidReader @Inject constructor(
    @ApplicationContext private val context: Context
)
```

**Dual-path API:**
- API 31+ (Android 12+): `ConnectivityManager.getNetworkCapabilities()` -> `transportInfo as WifiInfo` -> `bssid`
- API < 31: `WifiManager.connectionInfo.bssid` (deprecated but necessary)

**Normalization:**
- Uppercase: `aa:bb:cc:dd:ee:ff` -> `AA:BB:CC:DD:EE:FF`
- Placeholder filter: `02:00:00:00:00:00` -> `null` (Android returns this when permission is missing)
- `null`/blank -> `null`

**Public API:**
- `fun getCurrentBssid(): String?` — returns normalized BSSID or null
- `fun normalizeBssid(bssid: String?): String?` — static-like normalization (companion object)

### 2. Android Permissions (modify)

**File:** `app/src/main/AndroidManifest.xml`

Add before `<application>`:

```xml
<!-- BSSID-based home network detection -->
<uses-permission
    android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation"
    android:minSdkVersion="33" />
<uses-permission
    android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

| Permission | Target API | Purpose |
|---|---|---|
| `NEARBY_WIFI_DEVICES` (with `neverForLocation`) | API 33+ | Read BSSID without location |
| `ACCESS_FINE_LOCATION` (`maxSdkVersion="32"`) | API <= 32 | Read BSSID on older devices |
| `ACCESS_WIFI_STATE` | all | WiFi info access |

### 3. PreferencesManager Extension (modify)

**File:** `app/src/main/java/com/baluhost/android/data/local/datastore/PreferencesManager.kt`

New keys and methods, same style as existing `saveFritzBoxHost()`/`getFritzBoxHost()`:

| Key | Type | Default | Description |
|---|---|---|---|
| `home_bssid` | `String?` | `null` | Stored BSSID of home router (uppercase) |
| `auto_vpn_on_external` | `Boolean` | `false` | Auto-connect VPN when external detected |

New methods:
- `suspend fun saveHomeBssid(bssid: String)` — stores uppercase BSSID
- `fun getHomeBssid(): Flow<String?>` — reactive access
- `suspend fun clearHomeBssid()` — removes stored BSSID
- `suspend fun saveAutoVpnOnExternal(enabled: Boolean)`
- `fun isAutoVpnOnExternal(): Flow<Boolean>` — default `false`

Note: `server_url` is already stored during registration (`RegisterDeviceUseCase:87`) — no duplication needed.

### 4. NetworkStateManager Enhancement (modify)

**File:** `app/src/main/java/com/baluhost/android/util/NetworkStateManager.kt`

**New dependencies** (injected via constructor):
- `BssidReader`
- `PreferencesManager`

**Enhanced detection chain in `checkHomeNetworkStatus()`:**

```
1. VPN active?              -> return true (HOME) [existing]
2. Not on WiFi?             -> return false (EXTERNAL) [existing]
3. BSSID available? (BssidReader.getCurrentBssid())
   3a. Stored BSSID exists? (PreferencesManager.getHomeBssid())
       -> Match:    return true (HOME)
       -> Mismatch: return false (EXTERNAL)
   3b. No stored BSSID -> fall through to step 4
4. Subnet comparison        -> existing logic (fallback)
```

The public signature `checkHomeNetworkStatus(serverUrl): Boolean?` remains unchanged. All existing consumers (`DashboardViewModel`, `observeHomeNetworkStatus()`) work without modification.

`NetworkStateManager` constructor changes from:
```kotlin
class NetworkStateManager(context: Context, networkMonitor: NetworkMonitor)
```
to:
```kotlin
class NetworkStateManager(
    context: Context,
    networkMonitor: NetworkMonitor,
    bssidReader: BssidReader,
    preferencesManager: PreferencesManager
)
```

The provider in `NetworkModule.kt` is updated accordingly.

### 5. BSSID Capture at Registration (modify)

**Files:**
- `app/src/main/java/com/baluhost/android/presentation/ui/screens/qrscanner/QrScannerViewModel.kt`
- `app/src/main/java/com/baluhost/android/presentation/ui/screens/qrscanner/QrScannerScreen.kt`

**QrScannerViewModel:**
- Gets `BssidReader` and `PreferencesManager` injected (PreferencesManager already available via `RegisterDeviceUseCase`)
- New method: `fun captureHomeBssid()` — reads BSSID via `BssidReader`, saves via `PreferencesManager.saveHomeBssid()` if non-null
- Called after WiFi permission is granted

**QrScannerScreen (Composable):**
- Adds a `rememberLauncherForActivityResult(RequestPermission())` for WiFi permission (analogous to existing camera permission pattern)
- On `QrScannerState.Success`: launches WiFi permission request
- On grant: calls `viewModel.captureHomeBssid()`
- On deny: does nothing — app falls back to subnet check. No re-prompting.

**Permission selection logic:**
- API 33+: request `NEARBY_WIFI_DEVICES`
- API <= 32: request `ACCESS_FINE_LOCATION`

### 6. Settings UI (modify)

**Files:**
- `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModel.kt`
- `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsScreen.kt`

**SettingsViewModel:**
- Gets `BssidReader` injected
- New `SettingsUiState` fields: `homeBssidConfigured: Boolean`, `autoVpnOnExternal: Boolean`, `isOnWifi: Boolean`
- New methods:
  - `fun setHomeNetwork()` — reads current BSSID, saves to PreferencesManager, shows success/error via UiState
  - `fun toggleAutoVpn(enabled: Boolean)` — saves to PreferencesManager

**SettingsScreen:**
- New `GlassCard` titled **"NETZWERK"** — placed between the existing "BENACHRICHTIGUNGEN" and "FRITZ!BOX" cards
- Contents:
  - Status text: "Heimnetzwerk konfiguriert" / "Nicht konfiguriert"
  - "Heimnetzwerk setzen" button (GradientButton, enabled only when on WiFi)
  - "Auto-VPN wenn extern" switch (default: off)

### 7. VPN Trigger on EXTERNAL Detection (modify)

**Files:**
- `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt`
- `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt`

**DashboardViewModel:**
- New sealed class: `VpnAction { AutoConnect, ShowPrompt }`
- New `SharedFlow<VpnAction>`: `vpnActionEvent`
- New state field: `vpnPromptShown: Boolean` (not persisted — resets per app session)
- On `isInHomeNetwork == false` emission:
  1. Check if VPN already active (`networkStateManager.isVpnActive()`) -> skip
  2. Check if prompt already shown this session -> skip
  3. Read `isAutoVpnOnExternal` from PreferencesManager
     - `true`: emit `VpnAction.AutoConnect`
     - `false`: emit `VpnAction.ShowPrompt`, set `vpnPromptShown = true`

**DashboardScreen (Composable):**
- Collects `vpnActionEvent`
- On `AutoConnect`: calls `vpnViewModel.connect()`
- On `ShowPrompt`: shows AlertDialog:
  > "Du bist nicht im Heimnetzwerk. VPN verbinden?"
  > [Verbinden] [Nicht jetzt]
- "Verbinden" -> `vpnViewModel.connect()`
- "Nicht jetzt" -> dismiss, no retry this session

No ViewModel-to-ViewModel coupling — the Composable bridges both, following the existing pattern in `SettingsScreen` where both `SettingsViewModel` and `VpnViewModel` are parameters.

## Testing Strategy

| Component | Test Type | What to test |
|---|---|---|
| `BssidReader` | Unit test | Normalization: uppercase, placeholder filter, null/blank handling |
| `NetworkStateManager` | Unit test | Enhanced detection chain: BSSID match/mismatch/unavailable, fallback to subnet |
| `PreferencesManager` | Robolectric | New keys: save/get/clear roundtrip |
| `QrScannerViewModel` | Unit test | `captureHomeBssid()` saves BSSID when available, skips when null |
| `SettingsViewModel` | Unit test | `setHomeNetwork()` success/failure, `toggleAutoVpn()` persistence |
| `DashboardViewModel` | Unit test | VPN action events: auto-connect vs prompt, session-once guard |

## What This Design Does NOT Include

- **No separate DataStore** — uses existing `PreferencesManager`
- **No new enum/sealed class for network location** — reuses existing `Boolean?` (`true` = home, `false` = external, `null` = unknown)
- **No new reachability probe** — `ServerConnectivityChecker` already exists
- **No backend changes** — purely client-side
- **No multi-BSSID support** (mesh networks) — single BSSID stored. Can be extended later if needed.
