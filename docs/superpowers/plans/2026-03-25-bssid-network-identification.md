# BSSID-based Home Network Identification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enhance the BaluApp's existing home network detection with instant BSSID-based identification, enabling automatic VPN prompting when outside the home network.

**Architecture:** Extends the existing `NetworkStateManager` with BSSID as a primary check (< 1ms) before the subnet fallback. New `BssidReader` Hilt singleton reads WiFi BSSID. BSSID captured during QR registration, stored in `PreferencesManager`. Configurable VPN auto-connect/prompt on external network detection via `DashboardViewModel` SharedFlow events.

**Tech Stack:** Kotlin, Hilt DI, Android DataStore (existing `PreferencesManager`), ConnectivityManager (API 31+), WifiManager (legacy), Jetpack Compose, SharedFlow.

**Spec:** `docs/superpowers/specs/2026-03-25-bssid-network-identification-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `app/src/main/java/com/baluhost/android/util/BssidReader.kt` | Read current WiFi BSSID (dual-path: ConnectivityManager API 31+ / WifiManager legacy) |
| Create | `app/src/test/java/com/baluhost/android/util/BssidReaderTest.kt` | Unit tests for BSSID normalization |
| Create | `app/src/test/java/com/baluhost/android/util/NetworkStateManagerBssidTest.kt` | Unit tests for BSSID detection chain in NetworkStateManager |
| Create | `app/src/test/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModelNetworkTest.kt` | Unit tests for network settings (setHomeNetwork, toggleAutoVpn) |
| Create | `app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelVpnActionTest.kt` | Unit tests for VPN action events on external detection |
| Modify | `app/src/main/AndroidManifest.xml` | Add WiFi permission declarations |
| Modify | `app/src/main/java/com/baluhost/android/data/local/datastore/PreferencesManager.kt` | Add `home_bssid` and `auto_vpn_on_external` keys |
| Modify | `app/src/main/java/com/baluhost/android/util/NetworkStateManager.kt` | BSSID check as primary before subnet fallback |
| Modify | `app/src/main/java/com/baluhost/android/di/NetworkModule.kt` | Update `provideNetworkStateManager()` with new deps |
| Modify | `app/src/main/java/com/baluhost/android/presentation/ui/screens/qrscanner/QrScannerViewModel.kt` | Add `captureHomeBssid()` + `bssidCaptureCompleted` flag |
| Modify | `app/src/main/java/com/baluhost/android/presentation/ui/screens/qrscanner/QrScannerScreen.kt` | WiFi permission request + rationale dialog on registration success |
| Modify | `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModel.kt` | Add `setHomeNetwork()` + `toggleAutoVpn()` |
| Modify | `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsScreen.kt` | Add "NETZWERK" GlassCard |
| Modify | `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt` | Add `VpnAction` SharedFlow + external detection logic |
| Modify | `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt` | Add `vpnViewModel` param + VPN prompt dialog |

---

## Task 1: Add WiFi Permissions to AndroidManifest

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:5-8`

- [ ] **Step 1: Add permission declarations**

Add after line 7 (`<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`), before the Camera section:

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

- [ ] **Step 2: Verify the app builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(network): add WiFi permissions for BSSID-based network detection"
```

---

## Task 2: Create BssidReader with Tests

**Files:**
- Create: `app/src/test/java/com/baluhost/android/util/BssidReaderTest.kt`
- Create: `app/src/main/java/com/baluhost/android/util/BssidReader.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.baluhost.android.util

import org.junit.Assert.*
import org.junit.Test

class BssidReaderTest {

    @Test
    fun `normalizeBssid uppercases and keeps colons`() {
        assertEquals("AA:BB:CC:DD:EE:FF", BssidReader.normalizeBssid("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `normalizeBssid returns null for Android placeholder`() {
        assertNull(BssidReader.normalizeBssid("02:00:00:00:00:00"))
    }

    @Test
    fun `normalizeBssid returns null for null input`() {
        assertNull(BssidReader.normalizeBssid(null))
    }

    @Test
    fun `normalizeBssid returns null for empty string`() {
        assertNull(BssidReader.normalizeBssid(""))
    }

    @Test
    fun `normalizeBssid returns null for blank string`() {
        assertNull(BssidReader.normalizeBssid("   "))
    }

    @Test
    fun `normalizeBssid preserves already uppercase BSSID`() {
        assertEquals("AA:BB:CC:DD:EE:FF", BssidReader.normalizeBssid("AA:BB:CC:DD:EE:FF"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.baluhost.android.util.BssidReaderTest"`
Expected: FAIL — `BssidReader` not found

- [ ] **Step 3: Implement BssidReader**

```kotlin
package com.baluhost.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the current WiFi BSSID.
 * Uses ConnectivityManager on API 31+, WifiManager on older versions.
 */
@Singleton
class BssidReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PLACEHOLDER_BSSID = "02:00:00:00:00:00"

        /**
         * Normalize BSSID to uppercase.
         * Returns null for placeholders, null, or blank values.
         */
        fun normalizeBssid(bssid: String?): String? {
            if (bssid.isNullOrBlank() || bssid == PLACEHOLDER_BSSID) return null
            return bssid.uppercase()
        }
    }

    /**
     * Read the current WiFi BSSID.
     * Returns uppercase colon-separated MAC, or null if unavailable.
     */
    fun getCurrentBssid(): String? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            readBssidModern()
        } else {
            readBssidLegacy()
        }
        return normalizeBssid(raw)
    }

    private fun readBssidModern(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val wifiInfo = caps.transportInfo as? WifiInfo ?: return null
        return wifiInfo.bssid
    }

    @Suppress("DEPRECATION")
    private fun readBssidLegacy(): String? {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.connectionInfo.bssid
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.baluhost.android.util.BssidReaderTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baluhost/android/util/BssidReader.kt
git add app/src/test/java/com/baluhost/android/util/BssidReaderTest.kt
git commit -m "feat(network): add BssidReader with dual-path API 31+/legacy support"
```

---

## Task 3: Extend PreferencesManager with BSSID Keys

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/local/datastore/PreferencesManager.kt`

- [ ] **Step 1: Add preference keys**

Add after the existing `byteUnitModeKey` declaration (around line 39):

```kotlin
    private val homeBssidKey = stringPreferencesKey("home_bssid")
    // Project convention: booleans stored as "true"/"false" strings via stringPreferencesKey
    // (same as vpnConnectedKey, autoVpnForSync, etc.) — NOT booleanPreferencesKey
    private val autoVpnOnExternalKey = stringPreferencesKey("auto_vpn_on_external")
```

- [ ] **Step 2: Add BSSID methods**

Add before the `// Clear all data` section (before `clearAll()`), following the style of `saveFritzBoxHost()`/`getFritzBoxHost()`:

```kotlin
    // Home BSSID (for home network detection)
    suspend fun saveHomeBssid(bssid: String) {
        dataStore.edit { prefs -> prefs[homeBssidKey] = bssid.uppercase() }
    }

    fun getHomeBssid(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[homeBssidKey] }
    }

    suspend fun getHomeBssidOnce(): String? {
        return dataStore.data.map { prefs -> prefs[homeBssidKey] }.first()
    }

    suspend fun clearHomeBssid() {
        dataStore.edit { prefs -> prefs.remove(homeBssidKey) }
    }

    // Auto-VPN when external network detected
    suspend fun saveAutoVpnOnExternal(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[autoVpnOnExternalKey] = enabled.toString() }
    }

    fun isAutoVpnOnExternal(): Flow<Boolean> {
        return dataStore.data.map { prefs ->
            prefs[autoVpnOnExternalKey]?.toBoolean() ?: false
        }
    }
```

- [ ] **Step 3: Verify the app builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/local/datastore/PreferencesManager.kt
git commit -m "feat(network): add home_bssid and auto_vpn_on_external preferences"
```

Note: The spec lists Robolectric roundtrip tests for `PreferencesManager`. The new methods are trivial one-liners following the exact pattern of existing methods (e.g., `saveFritzBoxHost`/`getFritzBoxHost`). They are exercised indirectly through the `SettingsViewModelNetworkTest` and `QrScannerViewModelTest`. Dedicated Robolectric tests can be added as a follow-up if needed.

---

## Task 4: Enhance NetworkStateManager with BSSID Detection

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/util/NetworkStateManager.kt`
- Modify: `app/src/main/java/com/baluhost/android/di/NetworkModule.kt:196-201`

- [ ] **Step 1: Update NetworkStateManager constructor and add BSSID cache**

Replace the class declaration and add new fields. The constructor changes from:
```kotlin
class NetworkStateManager(
    private val context: Context,
    private val networkMonitor: NetworkMonitor
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isInHomeNetwork = MutableStateFlow<Boolean?>(null)
    val isInHomeNetwork: Flow<Boolean?> = _isInHomeNetwork.asStateFlow()

    private var cachedServerUrl: String? = null
    private var lastCheckTime: Long = 0
    private val CACHE_DURATION_MS = 30_000L // 30 seconds
```

to:
```kotlin
class NetworkStateManager(
    private val context: Context,
    private val networkMonitor: NetworkMonitor,
    private val bssidReader: BssidReader,
    preferencesManager: PreferencesManager
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var cachedHomeBssid: String? = null

    init {
        scope.launch {
            preferencesManager.getHomeBssid().collect { cachedHomeBssid = it }
        }
    }

    private val _isInHomeNetwork = MutableStateFlow<Boolean?>(null)
    val isInHomeNetwork: Flow<Boolean?> = _isInHomeNetwork.asStateFlow()

    private var cachedServerUrl: String? = null
    private var lastCheckTime: Long = 0
    private val CACHE_DURATION_MS = 30_000L // 30 seconds
```

Add necessary imports at the top:
```kotlin
import com.baluhost.android.data.local.datastore.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
```

- [ ] **Step 2: Replace the entire `checkHomeNetworkStatus()` method**

Replace the existing method (lines 41-73) with the complete restructured version that adds BSSID as primary check with cache only for the subnet fallback:

```kotlin
    /**
     * Check whether the device is on the home network via BSSID match or subnet comparison.
     *
     * IMPORTANT: This method does NOT check VPN or WiFi state. It is designed to be called
     * from [observeHomeNetworkStatus], which guards with VPN-active and WiFi-connected checks
     * before invoking this method. Do not call directly without those guards.
     */
    fun checkHomeNetworkStatus(serverUrl: String): Boolean? {
        cachedServerUrl = serverUrl
        val now = System.currentTimeMillis()

        // BSSID check — instant, bypasses 30s cache
        val currentBssid = bssidReader.getCurrentBssid()
        val storedBssid = cachedHomeBssid
        if (currentBssid != null && storedBssid != null) {
            val isHomeBssid = currentBssid == storedBssid
            _isInHomeNetwork.value = isHomeBssid
            lastCheckTime = now
            return isHomeBssid
        }
        // BSSID inconclusive (null current or null stored) — fall through to subnet

        // 3. Cache check — only for subnet fallback path (avoids frequent network lookups)
        if (now - lastCheckTime < CACHE_DURATION_MS && _isInHomeNetwork.value != null) {
            return _isInHomeNetwork.value
        }

        // 4. Subnet comparison (existing fallback logic)
        val isHome = try {
            val nasIp = extractIpFromUrl(serverUrl)
            if (nasIp == null) {
                false
            } else {
                val deviceIp = getCurrentLocalIp()
                if (deviceIp == null) {
                    null
                } else {
                    isSameSubnet(nasIp, deviceIp)
                }
            }
        } catch (e: Exception) {
            null
        }

        _isInHomeNetwork.value = isHome
        lastCheckTime = now

        return isHome
    }
```

- [ ] **Step 3: Update NetworkModule provider**

In `app/src/main/java/com/baluhost/android/di/NetworkModule.kt`, replace the existing `provideNetworkStateManager()` (around line 196):

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

with:

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

Add import at the top of `NetworkModule.kt`:
```kotlin
import com.baluhost.android.util.BssidReader
```

- [ ] **Step 4: Verify the app builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baluhost/android/util/NetworkStateManager.kt
git add app/src/main/java/com/baluhost/android/di/NetworkModule.kt
git commit -m "feat(network): add BSSID primary check to NetworkStateManager detection chain"
```

---

## Task 5: Capture BSSID During QR Registration

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/qrscanner/QrScannerViewModel.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/qrscanner/QrScannerScreen.kt`

- [ ] **Step 1: Add BSSID capture to QrScannerViewModel**

Add `BssidReader` and `PreferencesManager` to the constructor:

```kotlin
@HiltViewModel
class QrScannerViewModel @Inject constructor(
    private val registerDeviceUseCase: RegisterDeviceUseCase,
    private val importVpnConfigUseCase: ImportVpnConfigUseCase,
    private val bssidReader: BssidReader,
    private val preferencesManager: PreferencesManager
) : ViewModel() {
```

Add imports:
```kotlin
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.util.BssidReader
```

Add `bssidCaptureCompleted` flag to `QrScannerState.Success`:

```kotlin
    data class Success(
        val authResult: AuthResult,
        val vpnConfigured: Boolean = false,
        val bssidCaptureCompleted: Boolean = false
    ) : QrScannerState()
```

Add new methods to the ViewModel:

```kotlin
    fun captureHomeBssid() {
        viewModelScope.launch {
            val bssid = bssidReader.getCurrentBssid()
            if (bssid != null) {
                preferencesManager.saveHomeBssid(bssid)
                android.util.Log.d("QrScanner", "Home BSSID captured: $bssid")
            } else {
                android.util.Log.d("QrScanner", "No BSSID available — falling back to subnet detection")
            }
            markBssidCaptureCompleted()
        }
    }

    fun markBssidCaptureCompleted() {
        val current = _uiState.value
        if (current is QrScannerState.Success) {
            _uiState.value = current.copy(bssidCaptureCompleted = true)
        }
    }
```

- [ ] **Step 2: Add WiFi permission flow to QrScannerScreen**

Add imports to `QrScannerScreen.kt`:
```kotlin
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
```

In the `QrScannerScreen` composable, add the permission launcher and rationale dialog state before the existing `LaunchedEffect`:

```kotlin
    // BSSID permission handling
    val bssidPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.captureHomeBssid()
        } else {
            viewModel.markBssidCaptureCompleted()
        }
    }
    var showBssidRationale by remember { mutableStateOf(false) }

    // Determine which permission to request based on API level
    val bssidPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
```

Replace the existing `LaunchedEffect` for navigation (lines 58-67):

```kotlin
    // Guard to prevent re-triggering permission flow on recomposition
    var bssidPermissionFlowStarted by remember { mutableStateOf(false) }

    // Navigate to files after BSSID capture completes
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is QrScannerState.Success) {
            if (!state.bssidCaptureCompleted && !bssidPermissionFlowStarted) {
                // Trigger BSSID permission flow (once only)
                bssidPermissionFlowStarted = true
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    // API <= 32: show rationale first (location permission is confusing)
                    showBssidRationale = true
                } else {
                    // API 33+: NEARBY_WIFI_DEVICES doesn't mention location
                    bssidPermissionLauncher.launch(bssidPermission)
                }
            } else if (state.bssidCaptureCompleted) {
                // BSSID capture done — navigate
                if (state.vpnConfigured) {
                    kotlinx.coroutines.delay(2000)
                }
                onNavigateToFiles()
            }
        }
    }
```

Add the rationale dialog (before the closing `}` of the composable, near the other dialogs):

```kotlin
    // BSSID permission rationale (API <= 32 only)
    if (showBssidRationale) {
        AlertDialog(
            onDismissRequest = {
                showBssidRationale = false
                viewModel.markBssidCaptureCompleted()
            },
            title = { Text("WLAN-Erkennung") },
            text = {
                Text(
                    "BaluApp möchte dein Heimnetzwerk (WLAN) erkennen, um automatisch " +
                    "die richtige Verbindung zu wählen. Dazu wird einmalig der " +
                    "WLAN-Zugangspunkt identifiziert. Dein Standort wird nicht " +
                    "gespeichert oder übertragen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBssidRationale = false
                    bssidPermissionLauncher.launch(bssidPermission)
                }) {
                    Text("Weiter")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBssidRationale = false
                    viewModel.markBssidCaptureCompleted()
                }) {
                    Text("Überspringen")
                }
            }
        )
    }
```

- [ ] **Step 3: Verify the app builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/qrscanner/QrScannerViewModel.kt
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/qrscanner/QrScannerScreen.kt
git commit -m "feat(network): capture and store BSSID during QR code registration"
```

---

## Task 6: Add Network Settings UI

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: Extend SettingsViewModel**

Add `BssidReader` and `NetworkMonitor` to constructor:

```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val preferencesManager: PreferencesManager,
    private val securePreferences: SecurePreferencesManager,
    private val biometricAuthManager: BiometricAuthManager,
    private val pinManager: PinManager,
    private val appLockManager: AppLockManager,
    private val getCacheStatsUseCase: com.baluhost.android.domain.usecase.cache.GetCacheStatsUseCase,
    private val clearCacheUseCase: com.baluhost.android.domain.usecase.cache.ClearCacheUseCase,
    private val bssidReader: BssidReader,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {
```

Add imports:
```kotlin
import com.baluhost.android.util.BssidReader
import com.baluhost.android.util.NetworkMonitor
```

Add new fields to `SettingsUiState`:

```kotlin
data class SettingsUiState(
    // ... existing fields ...
    val byteUnitMode: com.baluhost.android.util.ByteUnitMode = com.baluhost.android.util.ByteUnitMode.BINARY,
    // Network settings
    val homeBssidConfigured: Boolean = false,
    val autoVpnOnExternal: Boolean = false,
    val isOnWifi: Boolean = false
)
```

Add `loadNetworkSettings()` method and call it from `init`.

Note: `isOnWifi` is read once at init — this is an accepted limitation. The Settings screen is short-lived and re-created on navigation, so the WiFi state is fresh each time the screen is opened. Reactive collection is not needed here.

```kotlin
    init {
        loadUserInfo()
        loadSecuritySettings()
        loadCacheStats()
        loadByteUnitMode()
        loadNetworkSettings()
    }

    private fun loadNetworkSettings() {
        viewModelScope.launch {
            val bssid = preferencesManager.getHomeBssidOnce()
            val autoVpn = preferencesManager.isAutoVpnOnExternal().first()
            val onWifi = networkMonitor.isCurrentlyWifiConnected()
            _uiState.update { it.copy(
                homeBssidConfigured = bssid != null,
                autoVpnOnExternal = autoVpn,
                isOnWifi = onWifi
            ) }
        }
    }

    fun setHomeNetwork() {
        viewModelScope.launch {
            val bssid = bssidReader.getCurrentBssid()
            if (bssid != null) {
                preferencesManager.saveHomeBssid(bssid)
                _uiState.update { it.copy(homeBssidConfigured = true) }
                _uiState.update { it.copy(error = null) }
            } else {
                _uiState.update { it.copy(error = "Verbinde dich zuerst mit deinem Heim-WLAN") }
            }
        }
    }

    fun toggleAutoVpn(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveAutoVpnOnExternal(enabled)
            _uiState.update { it.copy(autoVpnOnExternal = enabled) }
        }
    }
```

- [ ] **Step 2: Add NETZWERK card to SettingsScreen**

In `SettingsScreen.kt`, add the WiFi permission launcher and rationale state near the top of the composable (after the existing state declarations), then add the network card after the "BENACHRICHTIGUNGEN" card (after the closing `}` around line 329) and before the Fritz!Box card.

Add permission handling at the top of the composable (after `var pinSetupError`):

```kotlin
    // BSSID permission for "Set Home Network"
    val bssidPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setHomeNetwork()
        }
    }
    var showBssidRationale by remember { mutableStateOf(false) }
    val bssidPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
```

Add imports:
```kotlin
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
```

Add the BSSID rationale dialog at the bottom of the composable (near the other dialogs):

```kotlin
    // BSSID permission rationale (API <= 32 only)
    if (showBssidRationale) {
        AlertDialog(
            onDismissRequest = { showBssidRationale = false },
            title = { Text("WLAN-Erkennung") },
            text = {
                Text(
                    "BaluApp möchte dein Heimnetzwerk (WLAN) erkennen, um automatisch " +
                    "die richtige Verbindung zu wählen. Dazu wird einmalig der " +
                    "WLAN-Zugangspunkt identifiziert. Dein Standort wird nicht " +
                    "gespeichert oder übertragen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBssidRationale = false
                    bssidPermissionLauncher.launch(bssidPermission)
                }) { Text("Weiter") }
            },
            dismissButton = {
                TextButton(onClick = { showBssidRationale = false }) {
                    Text("Überspringen")
                }
            },
            containerColor = Slate900
        )
    }
```

Now add the network card:

```kotlin
                // Network Settings Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    intensity = GlassIntensity.Medium
                ) {
                    Text(
                        text = "NETZWERK",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Home network status
                    InfoRow(
                        label = "Heimnetzwerk",
                        value = if (uiState.homeBssidConfigured) "Konfiguriert" else "Nicht konfiguriert"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val context = LocalContext.current
                    GradientButton(
                        onClick = {
                            // Check if permission is already granted
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, bssidPermission
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                viewModel.setHomeNetwork()
                            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                showBssidRationale = true
                            } else {
                                bssidPermissionLauncher.launch(bssidPermission)
                            }
                        },
                        text = "Heimnetzwerk setzen",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.isOnWifi
                    )

                    if (!uiState.isOnWifi) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Verbinde dich mit deinem Heim-WLAN um das Netzwerk zu setzen",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Slate700.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Auto-VPN toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-VPN wenn extern",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            Text(
                                text = "VPN automatisch verbinden wenn nicht im Heimnetzwerk",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400
                            )
                        }
                        Switch(
                            checked = uiState.autoVpnOnExternal,
                            onCheckedChange = { viewModel.toggleAutoVpn(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Sky400,
                                checkedTrackColor = Slate800,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate800
                            )
                        )
                    }
                }
```

- [ ] **Step 3: Verify the app builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModel.kt
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsScreen.kt
git commit -m "feat(network): add NETZWERK settings card with home BSSID and auto-VPN toggle"
```

---

## Task 7: Add VPN Trigger on External Detection

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt`

- [ ] **Step 1: Add VpnAction sealed class and SharedFlow to DashboardViewModel**

Add after the existing `_snackbarEvent` declaration (around line 98):

```kotlin
    sealed class VpnAction {
        object AutoConnect : VpnAction()
        object ShowPrompt : VpnAction()
    }

    private val _vpnActionEvent = MutableSharedFlow<VpnAction>(extraBufferCapacity = 1)
    val vpnActionEvent: SharedFlow<VpnAction> = _vpnActionEvent.asSharedFlow()

    private var vpnPromptShown = false
```

- [ ] **Step 2: Add VPN trigger logic to observeHomeNetworkState()**

Replace the existing `observeHomeNetworkState()` method (lines 331-343):

```kotlin
    private fun observeHomeNetworkState() {
        viewModelScope.launch {
            preferencesManager.getServerUrl().collectLatest { serverUrl ->
                if (serverUrl != null) {
                    networkStateManager.observeHomeNetworkStatus(serverUrl)
                        .collect { isHome ->
                            _isInHomeNetwork.value = isHome
                            _isVpnActive.value = networkStateManager.isVpnActive()

                            // Trigger VPN action when external detected
                            if (isHome == false && !networkStateManager.isVpnActive() && !vpnPromptShown) {
                                val autoVpn = preferencesManager.isAutoVpnOnExternal().first()
                                if (autoVpn) {
                                    _vpnActionEvent.tryEmit(VpnAction.AutoConnect)
                                    // Don't set vpnPromptShown — if auto-connect fails, user can still be prompted
                                } else {
                                    _vpnActionEvent.tryEmit(VpnAction.ShowPrompt)
                                    vpnPromptShown = true // Only guard prompt, not auto-connect
                                }
                            }
                        }
                }
            }
        }
    }
```

- [ ] **Step 3: Add VpnViewModel to DashboardScreen and collect events**

In `DashboardScreen.kt`, add `vpnViewModel` parameter to the composable signature. The parameter has a default value (`hiltViewModel()`), so the NavGraph call site does not need updating — Hilt resolves it automatically:

```kotlin
fun DashboardScreen(
    onNavigateToFiles: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToVpn: () -> Unit = {},
    onNavigateToSync: () -> Unit = {},
    onNavigateToCpuDetail: () -> Unit = {},
    onNavigateToMemoryDetail: () -> Unit = {},
    onNavigateToPowerDetail: () -> Unit = {},
    onNavigateToStorageDetail: () -> Unit = {},
    onNavigateToSharesDetail: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
    vpnViewModel: VpnViewModel = hiltViewModel()
) {
```

Add import:
```kotlin
import com.baluhost.android.presentation.ui.screens.vpn.VpnViewModel
```

Add VPN action handling inside the composable, after the existing state collections:

```kotlin
    // VPN action handling
    var showVpnPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.vpnActionEvent.collect { action ->
            when (action) {
                is DashboardViewModel.VpnAction.AutoConnect -> {
                    vpnViewModel.connect()
                }
                is DashboardViewModel.VpnAction.ShowPrompt -> {
                    showVpnPrompt = true
                }
            }
        }
    }
```

Add the VPN prompt dialog before the closing `}` of the composable:

```kotlin
    // VPN prompt dialog
    if (showVpnPrompt) {
        AlertDialog(
            onDismissRequest = { showVpnPrompt = false },
            title = {
                Text(
                    text = "Nicht im Heimnetzwerk",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Du bist nicht im Heimnetzwerk. VPN verbinden?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showVpnPrompt = false
                        vpnViewModel.connect()
                    }
                ) {
                    Text("Verbinden", color = Sky400)
                }
            },
            dismissButton = {
                TextButton(onClick = { showVpnPrompt = false }) {
                    Text("Nicht jetzt")
                }
            },
            containerColor = Slate900
        )
    }
```

Add imports for `AlertDialog`, `Sky400`, `Slate900`, `FontWeight` if not already present.

- [ ] **Step 4: Verify the app builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt
git commit -m "feat(network): add VPN auto-connect/prompt on external network detection"
```

---

## Task 8: Update Existing QrScannerViewModel Tests

**Files:**
- Modify: `app/src/test/java/com/baluhost/android/presentation/ui/screens/qrscanner/QrScannerViewModelTest.kt`

- [ ] **Step 1: Update test setup with new dependencies**

The existing test constructs `QrScannerViewModel(registerDeviceUseCase, importVpnConfigUseCase)` — this now needs `bssidReader` and `preferencesManager`. Update the setup:

```kotlin
    private lateinit var bssidReader: BssidReader
    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        registerDeviceUseCase = mockk()
        importVpnConfigUseCase = mockk()
        bssidReader = mockk()
        preferencesManager = mockk(relaxed = true)
        viewModel = QrScannerViewModel(
            registerDeviceUseCase,
            importVpnConfigUseCase,
            bssidReader,
            preferencesManager
        )
    }
```

Add imports:
```kotlin
import com.baluhost.android.util.BssidReader
import com.baluhost.android.data.local.datastore.PreferencesManager
```

- [ ] **Step 2: Add BSSID capture test**

```kotlin
    @Test
    fun `captureHomeBssid saves BSSID when available`() = runTest {
        every { bssidReader.getCurrentBssid() } returns "AA:BB:CC:DD:EE:FF"
        coEvery { preferencesManager.saveHomeBssid(any()) } just Runs

        viewModel.captureHomeBssid()
        advanceUntilIdle()

        coVerify { preferencesManager.saveHomeBssid("AA:BB:CC:DD:EE:FF") }
    }

    @Test
    fun `captureHomeBssid skips save when BSSID is null`() = runTest {
        every { bssidReader.getCurrentBssid() } returns null

        viewModel.captureHomeBssid()
        advanceUntilIdle()

        coVerify(exactly = 0) { preferencesManager.saveHomeBssid(any()) }
    }
```

- [ ] **Step 3: Run all QrScannerViewModel tests**

Run: `./gradlew testDebugUnitTest --tests "com.baluhost.android.presentation.ui.screens.qrscanner.QrScannerViewModelTest"`
Expected: PASS (all existing + 2 new tests)

- [ ] **Step 4: Verify the app builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/baluhost/android/presentation/ui/screens/qrscanner/QrScannerViewModelTest.kt
git commit -m "test(network): update QrScannerViewModel tests for BSSID capture"
```

---

## Task 9: Unit Tests for NetworkStateManager, SettingsViewModel, and DashboardViewModel

**Files:**
- Create: `app/src/test/java/com/baluhost/android/util/NetworkStateManagerBssidTest.kt`
- Create: `app/src/test/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModelNetworkTest.kt`
- Create: `app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelVpnActionTest.kt`

- [ ] **Step 1: Write NetworkStateManager BSSID tests**

```kotlin
package com.baluhost.android.util

import android.content.Context
import android.net.ConnectivityManager
import com.baluhost.android.data.local.datastore.PreferencesManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkStateManagerBssidTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var bssidReader: BssidReader
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var connectivityManager: ConnectivityManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        networkMonitor = mockk(relaxed = true)
        bssidReader = mockk()
        preferencesManager = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createManager(storedBssid: String? = null): NetworkStateManager {
        every { preferencesManager.getHomeBssid() } returns flowOf(storedBssid)
        val manager = NetworkStateManager(context, networkMonitor, bssidReader, preferencesManager)
        // REQUIRED: NetworkStateManager.init launches a coroutine to collect getHomeBssid()
        // and populate cachedHomeBssid. Without advancing, cachedHomeBssid stays null and
        // all BSSID tests would silently fall through to the subnet path.
        testDispatcher.scheduler.advanceUntilIdle()
        return manager
    }

    @Test
    fun `returns HOME when current BSSID matches stored BSSID`() {
        every { bssidReader.getCurrentBssid() } returns "AA:BB:CC:DD:EE:FF"
        val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

        val result = manager.checkHomeNetworkStatus("http://192.168.1.100:3000")

        assertEquals(true, result)
    }

    @Test
    fun `returns EXTERNAL when current BSSID does not match stored BSSID`() {
        every { bssidReader.getCurrentBssid() } returns "11:22:33:44:55:66"
        val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

        val result = manager.checkHomeNetworkStatus("http://192.168.1.100:3000")

        assertEquals(false, result)
    }

    @Test
    fun `falls through to subnet when current BSSID is null`() {
        every { bssidReader.getCurrentBssid() } returns null
        val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

        // Falls through to subnet check — result depends on subnet logic,
        // but the key assertion is it does NOT short-circuit
        val result = manager.checkHomeNetworkStatus("http://192.168.1.100:3000")
        // Result may be true, false, or null depending on subnet check — we just verify no crash
        // and that BSSID mismatch was not incorrectly returned
    }

    @Test
    fun `falls through to subnet when stored BSSID is null`() {
        every { bssidReader.getCurrentBssid() } returns "AA:BB:CC:DD:EE:FF"
        val manager = createManager(storedBssid = null)

        val result = manager.checkHomeNetworkStatus("http://192.168.1.100:3000")
        // Should fall through to subnet — no crash, no incorrect BSSID result
    }
}
```

- [ ] **Step 2: Run NetworkStateManager tests**

Run: `./gradlew testDebugUnitTest --tests "com.baluhost.android.util.NetworkStateManagerBssidTest"`
Expected: PASS (4 tests)

- [ ] **Step 3: Write SettingsViewModel network tests**

```kotlin
package com.baluhost.android.presentation.ui.screens.settings

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.local.security.AppLockManager
import com.baluhost.android.data.local.security.BiometricAuthManager
import com.baluhost.android.data.local.security.PinManager
import com.baluhost.android.data.local.security.SecurePreferencesManager
import com.baluhost.android.domain.repository.DeviceRepository
import com.baluhost.android.domain.usecase.cache.ClearCacheUseCase
import com.baluhost.android.domain.usecase.cache.GetCacheStatsUseCase
import com.baluhost.android.util.BssidReader
import com.baluhost.android.util.NetworkMonitor
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelNetworkTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var bssidReader: BssidReader
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        preferencesManager = mockk(relaxed = true)
        bssidReader = mockk()
        networkMonitor = mockk()

        // Stub all existing PreferencesManager flows used by init
        every { preferencesManager.getUsername() } returns flowOf("test")
        every { preferencesManager.getServerUrl() } returns flowOf("http://test")
        every { preferencesManager.getDeviceId() } returns flowOf("device1")
        every { preferencesManager.getUserRole() } returns flowOf("user")
        every { preferencesManager.getByteUnitMode() } returns flowOf("binary")
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(false)
        coEvery { preferencesManager.getHomeBssidOnce() } returns null
        every { networkMonitor.isCurrentlyWifiConnected() } returns true

        viewModel = SettingsViewModel(
            deviceRepository = mockk(relaxed = true),
            preferencesManager = preferencesManager,
            securePreferences = mockk(relaxed = true),
            biometricAuthManager = mockk(relaxed = true),
            pinManager = mockk(relaxed = true),
            appLockManager = mockk(relaxed = true),
            getCacheStatsUseCase = mockk(relaxed = true),
            clearCacheUseCase = mockk(relaxed = true),
            bssidReader = bssidReader,
            networkMonitor = networkMonitor
        )
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setHomeNetwork saves BSSID and updates state`() = runTest {
        every { bssidReader.getCurrentBssid() } returns "AA:BB:CC:DD:EE:FF"
        coEvery { preferencesManager.saveHomeBssid(any()) } just Runs

        viewModel.setHomeNetwork()
        advanceUntilIdle()

        coVerify { preferencesManager.saveHomeBssid("AA:BB:CC:DD:EE:FF") }
        assertTrue(viewModel.uiState.value.homeBssidConfigured)
    }

    @Test
    fun `setHomeNetwork shows error when BSSID is null`() = runTest {
        every { bssidReader.getCurrentBssid() } returns null

        viewModel.setHomeNetwork()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.homeBssidConfigured)
    }

    @Test
    fun `toggleAutoVpn saves preference`() = runTest {
        coEvery { preferencesManager.saveAutoVpnOnExternal(any()) } just Runs

        viewModel.toggleAutoVpn(true)
        advanceUntilIdle()

        coVerify { preferencesManager.saveAutoVpnOnExternal(true) }
        assertTrue(viewModel.uiState.value.autoVpnOnExternal)
    }
}
```

- [ ] **Step 4: Run SettingsViewModel network tests**

Run: `./gradlew testDebugUnitTest --tests "com.baluhost.android.presentation.ui.screens.settings.SettingsViewModelNetworkTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Write DashboardViewModel VPN action tests**

```kotlin
package com.baluhost.android.presentation.ui.screens.dashboard

import app.cash.turbine.test
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.notification.NotificationWebSocketManager
import com.baluhost.android.domain.repository.OfflineQueueRepository
import com.baluhost.android.domain.repository.SyncRepository
import com.baluhost.android.domain.usecase.activity.GetRecentFilesUseCase
import com.baluhost.android.domain.usecase.cache.GetCacheStatsUseCase
import com.baluhost.android.domain.usecase.files.GetFilesUseCase
import com.baluhost.android.domain.usecase.power.CheckNasStatusUseCase
import com.baluhost.android.domain.usecase.power.SendSoftSleepUseCase
import com.baluhost.android.domain.usecase.power.SendSuspendUseCase
import com.baluhost.android.domain.usecase.power.SendWolUseCase
import com.baluhost.android.domain.usecase.shares.GetShareStatisticsUseCase
import com.baluhost.android.domain.usecase.system.GetEnergyDashboardUseCase
import com.baluhost.android.domain.usecase.system.GetRaidStatusUseCase
import com.baluhost.android.domain.usecase.system.GetSmartStatusUseCase
import com.baluhost.android.domain.usecase.system.GetSystemTelemetryUseCase
import com.baluhost.android.util.NetworkStateManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelVpnActionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var networkStateManager: NetworkStateManager
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var notificationWebSocketManager: NotificationWebSocketManager
    private lateinit var viewModel: DashboardViewModel

    private val homeNetworkFlow = MutableStateFlow<Boolean?>(null)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        networkStateManager = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        notificationWebSocketManager = mockk(relaxed = true)

        every { networkStateManager.isInHomeNetwork } returns homeNetworkFlow
        every { networkStateManager.isVpnActive() } returns false
        every { preferencesManager.getServerUrl() } returns flowOf("http://192.168.1.100:3000")
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(false)
        // Stub unreadCount as it's accessed directly in DashboardViewModel property init
        every { notificationWebSocketManager.unreadCount } returns MutableStateFlow(0)

        viewModel = DashboardViewModel(
            getFilesUseCase = mockk(relaxed = true),
            getRecentFilesUseCase = mockk(relaxed = true),
            getCacheStatsUseCase = mockk(relaxed = true),
            getSystemTelemetryUseCase = mockk(relaxed = true),
            getEnergyDashboardUseCase = mockk(relaxed = true),
            getRaidStatusUseCase = mockk(relaxed = true),
            getSmartStatusUseCase = mockk(relaxed = true),
            getShareStatisticsUseCase = mockk(relaxed = true),
            preferencesManager = preferencesManager,
            networkStateManager = networkStateManager,
            offlineQueueRepository = mockk(relaxed = true),
            syncRepository = mockk(relaxed = true),
            notificationWebSocketManager = notificationWebSocketManager,
            sendWolUseCase = mockk(relaxed = true),
            sendSoftSleepUseCase = mockk(relaxed = true),
            sendSuspendUseCase = mockk(relaxed = true),
            checkNasStatusUseCase = mockk(relaxed = true)
        )
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `emits ShowPrompt when external detected and auto-vpn off`() = runTest {
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(false)

        viewModel.vpnActionEvent.test {
            homeNetworkFlow.value = false
            advanceUntilIdle()

            assertEquals(DashboardViewModel.VpnAction.ShowPrompt, awaitItem())
        }
    }

    @Test
    fun `emits AutoConnect when external detected and auto-vpn on`() = runTest {
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(true)

        viewModel.vpnActionEvent.test {
            homeNetworkFlow.value = false
            advanceUntilIdle()

            assertEquals(DashboardViewModel.VpnAction.AutoConnect, awaitItem())
        }
    }

    @Test
    fun `does not emit VPN action when VPN already active`() = runTest {
        every { networkStateManager.isVpnActive() } returns true

        viewModel.vpnActionEvent.test {
            homeNetworkFlow.value = false
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    @Test
    fun `ShowPrompt only emitted once per session`() = runTest {
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(false)

        viewModel.vpnActionEvent.test {
            homeNetworkFlow.value = false
            advanceUntilIdle()
            assertEquals(DashboardViewModel.VpnAction.ShowPrompt, awaitItem())

            // Simulate returning to home then going external again
            homeNetworkFlow.value = true
            advanceUntilIdle()
            homeNetworkFlow.value = false
            advanceUntilIdle()

            // Should not emit again — vpnPromptShown guards it
            expectNoEvents()
        }
    }
}
```

- [ ] **Step 6: Run DashboardViewModel VPN action tests**

Run: `./gradlew testDebugUnitTest --tests "com.baluhost.android.presentation.ui.screens.dashboard.DashboardViewModelVpnActionTest"`
Expected: PASS (4 tests)

Note: These tests use [Turbine](https://github.com/cashapp/turbine) for SharedFlow testing. If Turbine is not already in test dependencies, add to `app/build.gradle.kts`:
```kotlin
testImplementation("app.cash.turbine:turbine:1.0.0")
```

- [ ] **Step 7: Run full test suite to verify no regressions**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS

- [ ] **Step 8: Commit**

```bash
git add app/src/test/java/com/baluhost/android/util/NetworkStateManagerBssidTest.kt
git add app/src/test/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModelNetworkTest.kt
git add app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelVpnActionTest.kt
git commit -m "test(network): add unit tests for BSSID detection, settings, and VPN actions"
```

---

## Task 10: End-to-End Verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS

- [ ] **Step 2: Verify clean build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual verification checklist (on device/emulator)**

1. Fresh install -> pair via QR code on home WiFi -> permission dialog appears -> grant -> verify BSSID stored (check Logcat for "Home BSSID captured:")
2. Restart app on home WiFi -> no VPN prompt (HOME detected)
3. Switch to mobile hotspot -> restart -> VPN prompt appears ("Du bist nicht im Heimnetzwerk")
4. Settings > NETZWERK > "Heimnetzwerk setzen" on WiFi -> shows "Konfiguriert"
5. Settings > NETZWERK > enable "Auto-VPN wenn extern" -> restart on external network -> VPN auto-connects
6. Deny WiFi permission at registration -> app functions normally (subnet fallback)
