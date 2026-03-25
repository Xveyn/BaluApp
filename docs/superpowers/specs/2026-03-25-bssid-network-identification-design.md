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
- `fun getHomeBssid(): Flow<String?>` — reactive access (for UI observation)
- `suspend fun getHomeBssidOnce(): String?` — one-shot suspend read via `dataStore.data.map { ... }.first()`. Used by `SettingsViewModel` to load initial state and as a convenience for non-Flow consumers. Follows the existing pattern of `getSyncFolderUri()`. Note: `NetworkStateManager` uses the Flow-based `getHomeBssid()` with in-memory caching instead (see Section 4).
- `suspend fun clearHomeBssid()` — removes stored BSSID
- `suspend fun saveAutoVpnOnExternal(enabled: Boolean)`
- `fun isAutoVpnOnExternal(): Flow<Boolean>` — default `false`

**`clearAll()` behavior:** The existing `clearAll()` calls `dataStore.edit { prefs -> prefs.clear() }`, which will also clear `home_bssid` and `auto_vpn_on_external`. This is the desired behavior — a device reset should clear the home network config. After re-registration, BSSID is recaptured automatically.

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
3. BSSID check (instant, bypasses 30s cache):
   3a. currentBssid = BssidReader.getCurrentBssid()
   3b. storedBssid = cached in-memory (see below)
   3c. Both non-null?
       -> Match:    return true (HOME), update cache timestamp
       -> Mismatch: return false (EXTERNAL), update cache timestamp
   3d. currentBssid is null (permission denied) but storedBssid exists
       -> fall through to step 4 (cannot determine via BSSID)
   3e. storedBssid is null (not configured)
       -> fall through to step 4
4. Cache still valid? (30s) -> return cached result [existing]
5. Subnet comparison        -> existing logic (fallback)
```

**BSSID bypasses the 30s cache** because the read is instant (< 1ms, no network I/O). The cache only applies to the subnet fallback path (step 5) which involves network lookups.

**Sync/async handling:** `checkHomeNetworkStatus()` is synchronous (non-suspend). To avoid blocking on DataStore:
- `NetworkStateManager` caches `storedBssid` in a `@Volatile private var cachedHomeBssid: String?` field (`@Volatile` ensures cross-thread visibility since the field is written by the collection coroutine and read by `checkHomeNetworkStatus()` which can be called from any thread)
- On init: launches a coroutine that collects `preferencesManager.getHomeBssid()` and updates the cached value
- `checkHomeNetworkStatus()` reads from `cachedHomeBssid` (in-memory, instant)
- This follows the reactive pattern — when BSSID is saved (at registration or via Settings), the Flow emits and the cache updates automatically

**CoroutineScope:** `NetworkStateManager` is a `@Singleton` (lives for the entire app lifetime), so it creates its own scope: `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`. No explicit cancellation needed since the scope lives as long as the process. The init block launches `scope.launch { preferencesManager.getHomeBssid().collect { cachedHomeBssid = it } }`.

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

**NetworkModule.kt provider update** — the existing `provideNetworkStateManager()` changes from:
```kotlin
@Provides
@Singleton
fun provideNetworkStateManager(
    @ApplicationContext context: Context,
    networkMonitor: NetworkMonitor
): NetworkStateManager {
    return NetworkStateManager(context, networkMonitor)
}
```
to:
```kotlin
@Provides
@Singleton
fun provideNetworkStateManager(
    @ApplicationContext context: Context,
    networkMonitor: NetworkMonitor,
    bssidReader: BssidReader,
    preferencesManager: PreferencesManager
): NetworkStateManager {
    return NetworkStateManager(context, networkMonitor, bssidReader, preferencesManager)
}
```

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
- On `QrScannerState.Success`: launches WiFi permission request **before** navigating to files. The existing `LaunchedEffect` that calls `onNavigateToFiles()` must be gated on a new flag `bssidCaptureCompleted` in `QrScannerState.Success` (default `false`). The ViewModel sets this flag to `true` after `captureHomeBssid()` completes (whether BSSID was saved or not). This prevents the navigation from dismissing the permission dialog.
- On grant: calls `viewModel.captureHomeBssid()` which reads BSSID, saves it, and sets `bssidCaptureCompleted = true`
- On deny: ViewModel sets `bssidCaptureCompleted = true` immediately — app falls back to subnet check. No re-prompting. User can configure later via Settings > "Heimnetzwerk setzen".

**Permission selection logic:**
- API 33+: request `NEARBY_WIFI_DEVICES`
- API <= 32: request `ACCESS_FINE_LOCATION`

**Permission rationale (API <= 32):** On API 26-32 the system dialog says "Allow access to device's location?" which is confusing. Before launching the system dialog, show a brief rationale `AlertDialog`:
> "BaluApp möchte dein Heimnetzwerk (WLAN) erkennen, um automatisch die richtige Verbindung zu wählen. Dazu wird einmalig der WLAN-Zugangspunkt identifiziert. Dein Standort wird nicht gespeichert oder übertragen."
> [Weiter] [Überspringen]

"Weiter" launches the system permission dialog. "Überspringen" skips BSSID capture (subnet fallback).
On API 33+ this rationale is not needed since `NEARBY_WIFI_DEVICES` with `neverForLocation` does not mention location.

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
- New `GlassCard` titled **"NETZWERK"** — placed directly after "BENACHRICHTIGUNGEN" (before "FRITZ!BOX" for admins, before "ORDNER-SYNCHRONISATION" for non-admins)
- Contents:
  - Status text: "Heimnetzwerk konfiguriert" / "Nicht konfiguriert"
  - "Heimnetzwerk setzen" button (GradientButton, enabled only when on WiFi). Also handles permission request if not yet granted (same rationale dialog as in Section 5 for API <= 32).
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
- **Signature change required:** `DashboardScreen` must gain a `vpnViewModel: VpnViewModel = hiltViewModel()` parameter (currently only has `viewModel: DashboardViewModel`). The NavGraph call site must be updated accordingly. This follows the existing pattern in `SettingsScreen` which already receives both `SettingsViewModel` and `VpnViewModel`.
- Collects `vpnActionEvent`
- On `AutoConnect`: calls `vpnViewModel.connect()`
- On `ShowPrompt`: shows AlertDialog:
  > "Du bist nicht im Heimnetzwerk. VPN verbinden?"
  > [Verbinden] [Nicht jetzt]
- "Verbinden" -> `vpnViewModel.connect()`
- "Nicht jetzt" -> dismiss, no retry this session

**Session guard scope:** `vpnPromptShown` lives in `DashboardViewModel` which survives configuration changes but not process death. This means the prompt resets on process death, which is acceptable — if the app restarts, the user should be notified again.

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
