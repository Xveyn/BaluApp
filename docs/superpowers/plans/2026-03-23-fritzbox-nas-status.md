# Fritz!Box NAS Status Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect NAS online/sleeping status via Fritz!Box TR-064, enabling a dynamic WoL button in the Dashboard when the NAS sleeps.

**Architecture:** When the BaluHost telemetry API fails, fall back to querying the Fritz!Box via TR-064 `GetSpecificHostEntry` to check if the NAS MAC is active. This drives a three-state ServerStatusStrip (Online/Sleeping/Offline). Also fixes SharedFlow buffer bug in two ViewModels.

**Tech Stack:** Kotlin, OkHttp (Digest Auth), Jetpack Compose, Hilt DI

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `domain/model/NasStatus.kt` | NasStatus enum |
| Modify | `data/network/FritzBoxTR064Client.kt` | Add `checkHostActive()` method |
| Modify | `domain/repository/PowerRepository.kt` | Add `checkNasStatus()` to interface |
| Modify | `data/repository/PowerRepositoryImpl.kt` | Implement `checkNasStatus()` |
| Modify | `presentation/ui/screens/dashboard/DashboardViewModel.kt` | Fallback logic, nasStatus state, remove isFritzBoxConfigured, fix SharedFlow |
| Modify | `presentation/ui/screens/dashboard/DashboardScreen.kt` | Three-state ServerStatusStrip |
| Create | `domain/usecase/power/CheckNasStatusUseCase.kt` | NAS status check use case |
| Modify | `presentation/ui/screens/settings/FritzBoxSettingsViewModel.kt` | Fix SharedFlow buffer |

All paths relative to `app/src/main/java/com/baluhost/android/`.

---

### Task 1: NasStatus Enum + SharedFlow Bugfix

**Files:**
- Create: `app/src/main/java/com/baluhost/android/domain/model/NasStatus.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/FritzBoxSettingsViewModel.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt`

- [ ] **Step 1: Create NasStatus enum**

```kotlin
package com.baluhost.android.domain.model

enum class NasStatus {
    ONLINE,
    SLEEPING,
    OFFLINE,
    UNKNOWN
}
```

- [ ] **Step 2: Fix SharedFlow buffer in FritzBoxSettingsViewModel**

In `FritzBoxSettingsViewModel.kt`, line 28, change:

```kotlin
    private val _snackbarEvent = MutableSharedFlow<String>()
```

to:

```kotlin
    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
```

- [ ] **Step 3: Fix SharedFlow buffer in DashboardViewModel**

In `DashboardViewModel.kt`, line 94, change:

```kotlin
    private val _snackbarEvent = MutableSharedFlow<String>()
```

to:

```kotlin
    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): add NasStatus enum, fix SharedFlow buffer in both ViewModels"
```

---

### Task 2: checkHostActive in FritzBoxTR064Client

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/network/FritzBoxTR064Client.kt`

- [ ] **Step 1: Add checkHostActive method**

Add a new constant after `WOL_ACTION` (after line 23):

```kotlin
        private const val HOST_ENTRY_ACTION = "GetSpecificHostEntry"
```

Add this method after `testConnection()` (after line 180), before `parseSoapFault()`:

```kotlin
    private fun buildHostEntryEnvelope(macAddress: String): String {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"" +
            " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:$HOST_ENTRY_ACTION xmlns:u=\"$SERVICE_TYPE\">" +
            "<NewMACAddress>$macAddress</NewMACAddress>" +
            "</u:$HOST_ENTRY_ACTION>" +
            "</s:Body>" +
            "</s:Envelope>"
    }

    suspend fun checkHostActive(
        host: String,
        port: Int,
        username: String,
        password: String,
        macAddress: String
    ): WolResult = withContext(Dispatchers.IO) {
        try {
            val url = "http://$host:$port$CONTROL_URL"
            val body = buildHostEntryEnvelope(macAddress)
            val client = buildClient(username, password)

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(XML_MEDIA_TYPE))
                .header("SOAPAction", "\"$SERVICE_TYPE#$HOST_ENTRY_ACTION\"")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when {
                response.code == 401 -> WolResult.AuthError
                response.code != 200 -> WolResult.Error("HTTP ${response.code}")
                responseBody.contains("Fault", ignoreCase = true) -> {
                    val fault = parseSoapFault(responseBody)
                    WolResult.Error(fault ?: "SOAP Fault")
                }
                else -> {
                    val active = parseNewActive(responseBody)
                    if (active) WolResult.Success else WolResult.Error("inactive")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Host check failed", e)
            WolResult.Unreachable
        } catch (e: Exception) {
            Log.e(TAG, "Host check unexpected error", e)
            WolResult.Error(e.message ?: "Unbekannter Fehler")
        }
    }

    private fun parseNewActive(xml: String): Boolean {
        return try {
            val start = xml.indexOf("<NewActive>")
            val end = xml.indexOf("</NewActive>")
            if (start >= 0 && end > start) {
                xml.substring(start + "<NewActive>".length, end).trim() == "1"
            } else false
        } catch (e: Exception) {
            false
        }
    }
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): add checkHostActive TR-064 method for NAS status detection"
```

---

### Task 3: PowerRepository checkNasStatus

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/domain/repository/PowerRepository.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt`

- [ ] **Step 1: Add checkNasStatus to interface**

In `PowerRepository.kt`, add import and method after `sendSuspend()` (line 8):

```kotlin
import com.baluhost.android.domain.model.NasStatus
```

```kotlin
    suspend fun checkNasStatus(): NasStatus
```

- [ ] **Step 2: Implement checkNasStatus in PowerRepositoryImpl**

Add import at top:

```kotlin
import com.baluhost.android.domain.model.NasStatus
```

Add method after `sendSuspend()` (after line 70):

```kotlin
    override suspend fun checkNasStatus(): NasStatus {
        return try {
            val mac = preferencesManager.getFritzBoxMacAddress().first()
            if (mac.isEmpty()) return NasStatus.UNKNOWN

            val host = preferencesManager.getFritzBoxHost().first()
            val port = preferencesManager.getFritzBoxPort().first()
            val username = preferencesManager.getFritzBoxUsername().first()
            val password = preferencesManager.getFritzBoxPassword() ?: ""

            when (val result = fritzBoxClient.checkHostActive(host, port, username, password, mac)) {
                is WolResult.Success -> NasStatus.ONLINE
                is WolResult.Error -> {
                    if (result.message == "inactive") NasStatus.SLEEPING else NasStatus.OFFLINE
                }
                is WolResult.AuthError -> NasStatus.OFFLINE
                is WolResult.Unreachable -> NasStatus.OFFLINE
            }
        } catch (e: Exception) {
            NasStatus.OFFLINE
        }
    }
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): add checkNasStatus to PowerRepository with Fritz!Box fallback"
```

---

### Task 4: DashboardViewModel Fallback Logic

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt`

- [ ] **Step 1: Add NasStatus import and state**

Add import:

```kotlin
import com.baluhost.android.domain.model.NasStatus
```

Replace `_isFritzBoxConfigured` lines 88-89 with:

```kotlin
    private val _nasStatus = MutableStateFlow(NasStatus.UNKNOWN)
    val nasStatus: StateFlow<NasStatus> = _nasStatus.asStateFlow()
```

- [ ] **Step 2: Create CheckNasStatusUseCase and add to ViewModel constructor**

Create `app/src/main/java/com/baluhost/android/domain/usecase/power/CheckNasStatusUseCase.kt` for consistency with existing power use cases:

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.model.NasStatus
import com.baluhost.android.domain.repository.PowerRepository
import javax.inject.Inject

class CheckNasStatusUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): NasStatus = powerRepository.checkNasStatus()
}
```

Add import to DashboardViewModel:

```kotlin
import com.baluhost.android.domain.usecase.power.CheckNasStatusUseCase
```

Add to DashboardViewModel constructor (after `sendSuspendUseCase`, line 64):

```kotlin
    private val checkNasStatusUseCase: CheckNasStatusUseCase
```

- [ ] **Step 3: Replace loadUserRole — remove isFritzBoxConfigured collection**

Replace `loadUserRole()` method (lines 351-361) with:

```kotlin
    private fun loadUserRole() {
        viewModelScope.launch {
            val role = preferencesManager.getUserRole().first() ?: "user"
            _isAdmin.value = role == "admin"
        }
    }
```

- [ ] **Step 4: Add updateNasStatus helper**

Add after `loadUserRole()`:

```kotlin
    private suspend fun updateNasStatus(telemetrySuccess: Boolean) {
        if (telemetrySuccess) {
            _nasStatus.value = NasStatus.ONLINE
        } else {
            val status = checkNasStatusUseCase()
            _nasStatus.value = status
        }
    }
```

- [ ] **Step 5: Wire fallback into loadDashboardData**

In `loadDashboardData()`, after `_uiState.value = _uiState.value.copy(...)` (after line 235, before `loadSyncFolders()`), add:

```kotlin
                updateNasStatus(telemetry != null)
```

In the catch block (line 239), after the `_uiState.value` update, add:

```kotlin
                updateNasStatus(false)
```

- [ ] **Step 6: Wire fallback into loadTelemetryData**

In `loadTelemetryData()`, after `_uiState.value = _uiState.value.copy(...)` (after line 292), add:

```kotlin
                updateNasStatus(telemetry != null)
```

In the catch block (line 293), after the Log.e, add:

```kotlin
                updateNasStatus(false)
```

- [ ] **Step 7: Set UNKNOWN after WoL**

In `sendWol()`, after `_powerActionInProgress.value = false` (in the finally/after block), add:

```kotlin
            _nasStatus.value = NasStatus.UNKNOWN
```

Find the sendWol method and add this line right after `_powerActionInProgress.value = false`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): add NAS status fallback logic in DashboardViewModel"
```

---

### Task 5: Three-State ServerStatusStrip

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt`

- [ ] **Step 1: Update state collection in DashboardScreen**

Add import:

```kotlin
import com.baluhost.android.domain.model.NasStatus
```

Replace line 81:

```kotlin
    val isFritzBoxConfigured by viewModel.isFritzBoxConfigured.collectAsState()
```

with:

```kotlin
    val nasStatus by viewModel.nasStatus.collectAsState()
```

- [ ] **Step 2: Update ServerStatusStrip call site**

Replace the `ServerStatusStrip(` call (around lines 229-237):

```kotlin
                    ServerStatusStrip(
                        nasStatus = nasStatus,
                        uptimeSeconds = uiState.telemetry?.uptime?.toLong(),
                        isAdmin = isAdmin,
                        isActionInProgress = powerActionInProgress,
                        onSendWol = { viewModel.sendWol() },
                        onSendSoftSleep = { viewModel.sendSoftSleep() },
                        onSendSuspend = { viewModel.sendSuspend() }
                    )
```

- [ ] **Step 3: Rewrite ServerStatusStrip signature and status indicator**

Replace the function signature (lines 827-835):

```kotlin
@Composable
private fun ServerStatusStrip(
    nasStatus: NasStatus,
    uptimeSeconds: Long?,
    isAdmin: Boolean,
    isActionInProgress: Boolean,
    onSendWol: () -> Unit,
    onSendSoftSleep: () -> Unit,
    onSendSuspend: () -> Unit
) {
```

Replace the status dot color (line 861):

```kotlin
                            if (isOnline) Green500 else Red500,
```

with:

```kotlin
                            when (nasStatus) {
                                NasStatus.ONLINE -> Green500
                                NasStatus.SLEEPING -> Orange500
                                else -> Red500
                            },
```

Replace the status text (line 866):

```kotlin
                    text = if (isOnline) "Server Online" else "Server Offline",
```

with:

```kotlin
                    text = when (nasStatus) {
                        NasStatus.ONLINE -> "Server Online"
                        NasStatus.SLEEPING -> "NAS schläft"
                        else -> "Server Offline"
                    },
```

Replace the uptime conditional (line 871):

```kotlin
                if (isOnline && uptimeSeconds != null) {
```

with:

```kotlin
                if (nasStatus == NasStatus.ONLINE && uptimeSeconds != null) {
```

- [ ] **Step 4: Rewrite power dialog options**

Replace the entire power options block inside the dialog `text = { Column(...) { ... } }` (lines 921-962):

```kotlin
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (nasStatus) {
                        NasStatus.SLEEPING -> {
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
                        NasStatus.ONLINE -> {
                            PowerOptionButton(
                                icon = Icons.Default.Bedtime,
                                label = "Soft Sleep",
                                description = "Services pausieren, Disks herunterfahren",
                                color = Sky400,
                                onClick = {
                                    showPowerDialog = false
                                    confirmAction = PowerAction.SOFT_SLEEP
                                }
                            )
                            PowerOptionButton(
                                icon = Icons.Default.PowerSettingsNew,
                                label = "Suspend",
                                description = "System komplett schlafen legen",
                                color = Orange500,
                                onClick = {
                                    showPowerDialog = false
                                    confirmAction = PowerAction.SUSPEND
                                }
                            )
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

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(fritzbox): three-state ServerStatusStrip with NAS sleeping detection"
```

---

### Task 6: Build Verification

- [ ] **Step 1: Run build**

```bash
cd /d/Programme\ \(x86\)/BaluApp && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any compilation issues**

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat: Fritz!Box NAS status detection with dynamic WoL button"
```
