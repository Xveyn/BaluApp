# WoL Home Network Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a WoL button in the power dialog when the server is offline and the user is on the home network (BSSID match), with Fritz!Box status verification.

**Architecture:** Add `NasStatusResult` sealed class to expose Fritz!Box error details from the repository layer. Add BSSID-only check to `NetworkStateManager`. Introduce `WolAvailability` state in `DashboardViewModel` that combines NAS status + BSSID check. Update the power dialog UI to show WoL or Fritz!Box error depending on availability.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, StateFlow, MockK (tests)

---

### Task 1: Add `NasStatusResult` sealed class

**Files:**
- Create: `app/src/main/java/com/baluhost/android/domain/model/NasStatusResult.kt`

- [ ] **Step 1: Create NasStatusResult model**

```kotlin
package com.baluhost.android.domain.model

sealed class NasStatusResult {
    data class Resolved(val status: NasStatus) : NasStatusResult()
    object FritzBoxUnreachable : NasStatusResult()
    object FritzBoxAuthError : NasStatusResult()
    object FritzBoxNotConfigured : NasStatusResult()
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/model/NasStatusResult.kt
git commit -m "feat(power): add NasStatusResult sealed class for detailed Fritz!Box status"
```

---

### Task 2: Add `WolAvailability` enum

**Files:**
- Create: `app/src/main/java/com/baluhost/android/domain/model/WolAvailability.kt`

- [ ] **Step 1: Create WolAvailability enum**

```kotlin
package com.baluhost.android.domain.model

enum class WolAvailability {
    AVAILABLE,
    NOT_ON_HOME_NETWORK,
    FRITZ_BOX_ERROR,
    CHECKING,
    NOT_NEEDED
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/model/WolAvailability.kt
git commit -m "feat(power): add WolAvailability enum for UI state"
```

---

### Task 3: Add `isOnHomeNetworkByBssid()` to NetworkStateManager

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/util/NetworkStateManager.kt`
- Modify: `app/src/test/java/com/baluhost/android/util/NetworkStateManagerBssidTest.kt`

- [ ] **Step 1: Write failing tests**

Add these tests to `NetworkStateManagerBssidTest.kt` after the existing tests:

```kotlin
@Test
fun `isOnHomeNetworkByBssid returns true when BSSID matches`() {
    every { bssidReader.getCurrentBssid() } returns "AA:BB:CC:DD:EE:FF"
    val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

    assertTrue(manager.isOnHomeNetworkByBssid())
}

@Test
fun `isOnHomeNetworkByBssid returns false when BSSID does not match`() {
    every { bssidReader.getCurrentBssid() } returns "11:22:33:44:55:66"
    val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

    assertFalse(manager.isOnHomeNetworkByBssid())
}

@Test
fun `isOnHomeNetworkByBssid returns false when current BSSID is null`() {
    every { bssidReader.getCurrentBssid() } returns null
    val manager = createManager(storedBssid = "AA:BB:CC:DD:EE:FF")

    assertFalse(manager.isOnHomeNetworkByBssid())
}

@Test
fun `isOnHomeNetworkByBssid returns false when stored BSSID is null`() {
    every { bssidReader.getCurrentBssid() } returns "AA:BB:CC:DD:EE:FF"
    val manager = createManager(storedBssid = null)

    assertFalse(manager.isOnHomeNetworkByBssid())
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.baluhost.android.util.NetworkStateManagerBssidTest"`
Expected: 4 new tests FAIL with "Unresolved reference: isOnHomeNetworkByBssid"

- [ ] **Step 3: Implement isOnHomeNetworkByBssid**

In `NetworkStateManager.kt`, add this method after `checkHomeNetworkStatus()` (after line 101):

```kotlin
/**
 * Check if device is on home network using BSSID only.
 * Does NOT check VPN or subnet. Returns false if either BSSID is unavailable.
 */
fun isOnHomeNetworkByBssid(): Boolean {
    val current = bssidReader.getCurrentBssid() ?: return false
    val stored = cachedHomeBssid ?: return false
    return current == stored
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.baluhost.android.util.NetworkStateManagerBssidTest"`
Expected: All 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baluhost/android/util/NetworkStateManager.kt app/src/test/java/com/baluhost/android/util/NetworkStateManagerBssidTest.kt
git commit -m "feat(network): add BSSID-only home network check for WoL gate"
```

---

### Task 4: Update PowerRepository to return NasStatusResult

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/domain/repository/PowerRepository.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt`
- Modify: `app/src/main/java/com/baluhost/android/domain/usecase/power/CheckNasStatusUseCase.kt`

- [ ] **Step 1: Update PowerRepository interface**

Replace the `checkNasStatus()` signature in `PowerRepository.kt`:

```kotlin
package com.baluhost.android.domain.repository

import com.baluhost.android.domain.model.NasStatusResult
import com.baluhost.android.util.Result

interface PowerRepository {
    suspend fun sendWol(): Result<String>
    suspend fun sendSoftSleep(): Result<String>
    suspend fun sendSuspend(): Result<String>
    suspend fun checkNasStatus(): NasStatusResult
}
```

- [ ] **Step 2: Update PowerRepositoryImpl**

Replace `checkNasStatus()` in `PowerRepositoryImpl.kt`:

```kotlin
override suspend fun checkNasStatus(): NasStatusResult {
    return try {
        val mac = preferencesManager.getFritzBoxMacAddress().first()
        if (mac.isEmpty()) return NasStatusResult.FritzBoxNotConfigured

        val host = preferencesManager.getFritzBoxHost().first()
        val port = preferencesManager.getFritzBoxPort().first()
        val username = preferencesManager.getFritzBoxUsername().first()
        val password = preferencesManager.getFritzBoxPassword() ?: ""

        when (val result = fritzBoxClient.checkHostActive(host, port, username, password, mac)) {
            is WolResult.Success -> NasStatusResult.Resolved(NasStatus.ONLINE)
            is WolResult.Error -> {
                if (result.message == "inactive") {
                    NasStatusResult.Resolved(NasStatus.SLEEPING)
                } else {
                    NasStatusResult.Resolved(NasStatus.OFFLINE)
                }
            }
            is WolResult.AuthError -> NasStatusResult.FritzBoxAuthError
            is WolResult.Unreachable -> NasStatusResult.FritzBoxUnreachable
        }
    } catch (e: Exception) {
        NasStatusResult.FritzBoxUnreachable
    }
}
```

Also update the import at the top of `PowerRepositoryImpl.kt` — replace `import com.baluhost.android.domain.model.NasStatus` with:

```kotlin
import com.baluhost.android.domain.model.NasStatus
import com.baluhost.android.domain.model.NasStatusResult
```

- [ ] **Step 3: Update CheckNasStatusUseCase**

Replace the full file content of `CheckNasStatusUseCase.kt`:

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.model.NasStatusResult
import com.baluhost.android.domain.repository.PowerRepository
import javax.inject.Inject

class CheckNasStatusUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): NasStatusResult = powerRepository.checkNasStatus()
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (the DashboardViewModel will still compile because it consumes the use case result — we update its usage in the next task)

Note: This step may fail because `DashboardViewModel.updateNasStatus()` currently assigns the result directly to `_nasStatus.value` which expects `NasStatus`, not `NasStatusResult`. That's expected — we fix it in Task 5.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/repository/PowerRepository.kt app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt app/src/main/java/com/baluhost/android/domain/usecase/power/CheckNasStatusUseCase.kt
git commit -m "feat(power): return NasStatusResult from checkNasStatus for detailed Fritz!Box feedback"
```

---

### Task 5: Update DashboardViewModel with WoL availability logic

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt`

- [ ] **Step 1: Add imports and state**

Add these imports at the top of `DashboardViewModel.kt` (after the existing imports):

```kotlin
import com.baluhost.android.domain.model.NasStatusResult
import com.baluhost.android.domain.model.WolAvailability
```

Add the new state field after `_powerActionInProgress` (after line 95):

```kotlin
private val _wolAvailability = MutableStateFlow(WolAvailability.NOT_NEEDED)
val wolAvailability: StateFlow<WolAvailability> = _wolAvailability.asStateFlow()
```

- [ ] **Step 2: Update updateNasStatus()**

Replace the existing `updateNasStatus()` method (lines 405-412) with:

```kotlin
private suspend fun updateNasStatus(telemetrySuccess: Boolean) {
    if (telemetrySuccess) {
        _nasStatus.value = NasStatus.ONLINE
        _wolAvailability.value = WolAvailability.NOT_NEEDED
    } else {
        _wolAvailability.value = WolAvailability.CHECKING
        when (val result = checkNasStatusUseCase()) {
            is NasStatusResult.Resolved -> {
                _nasStatus.value = result.status
                _wolAvailability.value = when (result.status) {
                    NasStatus.ONLINE, NasStatus.SLEEPING -> WolAvailability.NOT_NEEDED
                    NasStatus.OFFLINE, NasStatus.UNKNOWN -> {
                        if (networkStateManager.isOnHomeNetworkByBssid()) {
                            WolAvailability.AVAILABLE
                        } else {
                            WolAvailability.NOT_ON_HOME_NETWORK
                        }
                    }
                }
            }
            is NasStatusResult.FritzBoxUnreachable,
            is NasStatusResult.FritzBoxAuthError,
            is NasStatusResult.FritzBoxNotConfigured -> {
                _nasStatus.value = NasStatus.OFFLINE
                _wolAvailability.value = if (networkStateManager.isOnHomeNetworkByBssid()) {
                    WolAvailability.FRITZ_BOX_ERROR
                } else {
                    WolAvailability.NOT_ON_HOME_NETWORK
                }
            }
        }
    }
}
```

- [ ] **Step 3: Reset wolAvailability after WoL action**

In the `sendWol()` method, add a reset after the action completes. Replace `_nasStatus.value = NasStatus.UNKNOWN` (line 423) with:

```kotlin
_nasStatus.value = NasStatus.UNKNOWN
_wolAvailability.value = WolAvailability.NOT_NEEDED
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt
git commit -m "feat(power): add WoL availability logic to DashboardViewModel"
```

---

### Task 6: Update DashboardScreen and MainScreen for navigation

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/main/MainScreen.kt`

- [ ] **Step 1: Add onNavigateToFritzBoxSettings to DashboardScreen**

In `DashboardScreen.kt`, add the new parameter to the `DashboardScreen` composable function signature (after `onNavigateToNotifications`):

```kotlin
onNavigateToFritzBoxSettings: () -> Unit = {},
```

- [ ] **Step 2: Collect wolAvailability state**

In `DashboardScreen.kt`, find the line where `nasStatus` is collected (near the other `collectAsState` calls at the top of the composable body). Add after it:

```kotlin
val wolAvailability by viewModel.wolAvailability.collectAsState()
```

- [ ] **Step 3: Update ServerStatusStrip call site**

Update the `ServerStatusStrip(...)` call (around line 249) to pass the new parameters:

```kotlin
ServerStatusStrip(
    nasStatus = nasStatus,
    uptimeSeconds = uiState.telemetry?.uptime?.toLong(),
    isAdmin = isAdmin,
    isActionInProgress = powerActionInProgress,
    wolAvailability = wolAvailability,
    onSendWol = { viewModel.sendWol() },
    onSendSoftSleep = { viewModel.sendSoftSleep() },
    onSendSuspend = { viewModel.sendSuspend() },
    onNavigateToFritzBoxSettings = onNavigateToFritzBoxSettings
)
```

- [ ] **Step 4: Update ServerStatusStrip signature and imports**

Add the import at the top of `DashboardScreen.kt`:

```kotlin
import com.baluhost.android.domain.model.WolAvailability
```

Update the `ServerStatusStrip` function signature (around line 885) to:

```kotlin
@Composable
private fun ServerStatusStrip(
    nasStatus: NasStatus,
    uptimeSeconds: Long?,
    isAdmin: Boolean,
    isActionInProgress: Boolean,
    wolAvailability: WolAvailability,
    onSendWol: () -> Unit,
    onSendSoftSleep: () -> Unit,
    onSendSuspend: () -> Unit,
    onNavigateToFritzBoxSettings: () -> Unit
) {
```

- [ ] **Step 5: Update the power dialog else-branch**

Replace the `else` branch in the power dialog `when (nasStatus)` block (lines 1022-1028) with:

```kotlin
else -> {
    when (wolAvailability) {
        WolAvailability.AVAILABLE -> {
            PowerOptionButton(
                icon = Icons.Default.WbSunny,
                label = "NAS aufwecken",
                description = "Wake-on-LAN über Fritz!Box",
                color = Green500,
                onClick = {
                    showPowerDialog = false
                    confirmAction = PowerAction.WOL
                }
            )
        }
        WolAvailability.FRITZ_BOX_ERROR -> {
            Text(
                text = "Fritz!Box nicht erreichbar",
                style = MaterialTheme.typography.bodyMedium,
                color = Orange500
            )
            TextButton(
                onClick = {
                    showPowerDialog = false
                    onNavigateToFritzBoxSettings()
                }
            ) {
                Text(
                    "Konfiguration prüfen",
                    color = Sky400
                )
            }
        }
        WolAvailability.CHECKING -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Sky400,
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Status wird geprüft...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate400
                )
            }
        }
        else -> {
            Text(
                text = "Server nicht erreichbar",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate400
            )
        }
    }
}
```

- [ ] **Step 6: Wire navigation in MainScreen**

In `MainScreen.kt`, add the `onNavigateToFritzBoxSettings` parameter to the `DashboardScreen(...)` call (after `onNavigateToNotifications`):

```kotlin
onNavigateToFritzBoxSettings = {
    parentNavController.navigate(Screen.FritzBoxSettings.route)
},
```

- [ ] **Step 7: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt app/src/main/java/com/baluhost/android/presentation/ui/screens/main/MainScreen.kt
git commit -m "feat(power): show WoL button when home network + server offline, Fritz!Box error with settings link"
```

---

### Task 7: Run all tests and verify

**Files:** None (verification only)

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 2: Build the full APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL
