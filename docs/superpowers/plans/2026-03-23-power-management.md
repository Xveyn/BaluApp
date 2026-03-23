# Power Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the placeholder Power-Dialog in the Dashboard's ServerStatusStrip with functional WoL (via Fritz!Box) and Sleep controls (Soft Sleep / Suspend), visible only to admins.

**Architecture:** Clean Architecture layers — Retrofit API interfaces → Repository (interface + impl) → UseCases → DashboardViewModel → Compose UI. The existing `ServerStatusStrip` composable gets extended with admin-aware power actions. Non-admins see no power icon at all.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Retrofit 2, Hilt DI, StateFlow

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `data/remote/api/FritzBoxApi.kt` | Retrofit interface for `POST /api/fritzbox/wol` |
| Create | `data/remote/api/SleepApi.kt` | Retrofit interface for `POST .../sleep/soft`, `.../sleep/suspend` |
| Modify | `data/remote/dto/PowerDto.kt` | Append `PowerActionResponse` (file already contains PowerSummaryDto) |
| Create | `domain/repository/PowerRepository.kt` | Repository interface |
| Create | `data/repository/PowerRepositoryImpl.kt` | Repository implementation |
| Create | `domain/usecase/power/SendWolUseCase.kt` | WoL via Fritz!Box |
| Create | `domain/usecase/power/SendSoftSleepUseCase.kt` | Soft sleep |
| Create | `domain/usecase/power/SendSuspendUseCase.kt` | System suspend |
| Modify | `di/NetworkModule.kt` | Add `provideFritzBoxApi`, `provideSleepApi` |
| Modify | `di/RepositoryModule.kt` | Add `bindPowerRepository` |
| Modify | `presentation/ui/screens/dashboard/DashboardViewModel.kt` | Add `isAdmin`, power action methods, snackbar event |
| Modify | `presentation/ui/screens/dashboard/DashboardScreen.kt` | Rewrite `ServerStatusStrip` with power dialog |
| Modify | `res/values/strings.xml` | Add power management strings |

All paths relative to `app/src/main/java/com/baluhost/android/` unless noted.

---

### Task 1: API Interfaces

**Files:**
- Create: `app/src/main/java/com/baluhost/android/data/remote/api/FritzBoxApi.kt`
- Create: `app/src/main/java/com/baluhost/android/data/remote/api/SleepApi.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/remote/dto/PowerDto.kt` (append — file already contains PowerSummaryDto/TapoDevicePowerDto)

- [ ] **Step 1: Append PowerActionResponse to existing PowerDto.kt**

Add at the end of the file (after line 56):

```kotlin

data class PowerActionResponse(
    val success: Boolean,
    val message: String
)
```

- [ ] **Step 2: Create FritzBoxApi.kt**

```kotlin
package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.PowerActionResponse
import retrofit2.http.POST

interface FritzBoxApi {

    @POST("fritzbox/wol")
    suspend fun sendWol(): PowerActionResponse
}
```

- [ ] **Step 3: Create SleepApi.kt**

```kotlin
package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.PowerActionResponse
import retrofit2.http.POST

interface SleepApi {

    @POST("system/sleep/soft")
    suspend fun sendSoftSleep(): PowerActionResponse

    @POST("system/sleep/suspend")
    suspend fun sendSuspend(): PowerActionResponse
}
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(power): add FritzBoxApi, SleepApi and PowerActionResponse DTO"
```

---

### Task 2: DI — Register API providers

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/di/NetworkModule.kt` (add after line 173)

- [ ] **Step 1: Add imports to NetworkModule.kt**

Add at the top with the other API imports (after line 15):

```kotlin
import com.baluhost.android.data.remote.api.FritzBoxApi
import com.baluhost.android.data.remote.api.SleepApi
```

- [ ] **Step 2: Add provider functions**

Add after the `provideNotificationsApi` function (after line 173), before the `provideWebSocketOkHttpClient` function:

```kotlin
    @Provides
    @Singleton
    fun provideFritzBoxApi(retrofit: Retrofit): FritzBoxApi {
        return retrofit.create(FritzBoxApi::class.java)
    }

    @Provides
    @Singleton
    fun provideSleepApi(retrofit: Retrofit): SleepApi {
        return retrofit.create(SleepApi::class.java)
    }
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(power): register FritzBoxApi and SleepApi in NetworkModule"
```

---

### Task 3: Repository Layer

**Files:**
- Create: `app/src/main/java/com/baluhost/android/domain/repository/PowerRepository.kt`
- Create: `app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt`

- [ ] **Step 1: Create PowerRepository interface**

```kotlin
package com.baluhost.android.domain.repository

import com.baluhost.android.util.Result

interface PowerRepository {
    suspend fun sendWol(): Result<String>
    suspend fun sendSoftSleep(): Result<String>
    suspend fun sendSuspend(): Result<String>
}
```

- [ ] **Step 2: Create PowerRepositoryImpl**

```kotlin
package com.baluhost.android.data.repository

import com.baluhost.android.data.remote.api.FritzBoxApi
import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import retrofit2.HttpException
import javax.inject.Inject

class PowerRepositoryImpl @Inject constructor(
    private val fritzBoxApi: FritzBoxApi,
    private val sleepApi: SleepApi
) : PowerRepository {

    override suspend fun sendWol(): Result<String> {
        return try {
            val response = fritzBoxApi.sendWol()
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            val msg = when (e.code()) {
                400 -> "Fritz!Box nicht konfiguriert"
                503 -> "Fritz!Box nicht erreichbar"
                else -> "WoL fehlgeschlagen: ${e.message()}"
            }
            Result.Error(Exception(msg, e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun sendSoftSleep(): Result<String> {
        return try {
            val response = sleepApi.sendSoftSleep()
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.Error(Exception("Sleep fehlgeschlagen: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun sendSuspend(): Result<String> {
        return try {
            val response = sleepApi.sendSuspend()
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.Error(Exception("Suspend fehlgeschlagen: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(power): add PowerRepository interface and implementation"
```

---

### Task 4: DI — Bind Repository

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/di/RepositoryModule.kt` (add after line 94)

- [ ] **Step 1: Add imports**

Add at the top with other imports:

```kotlin
import com.baluhost.android.data.repository.PowerRepositoryImpl
import com.baluhost.android.domain.repository.PowerRepository
```

- [ ] **Step 2: Add binding**

Add after the `bindNotificationRepository` function (after line 94), before the closing `}`:

```kotlin
    @Binds
    @Singleton
    abstract fun bindPowerRepository(
        powerRepositoryImpl: PowerRepositoryImpl
    ): PowerRepository
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(power): bind PowerRepository in RepositoryModule"
```

---

### Task 5: UseCases

**Files:**
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/power/SendWolUseCase.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/power/SendSoftSleepUseCase.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/power/SendSuspendUseCase.kt`

- [ ] **Step 1: Create SendWolUseCase**

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class SendWolUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): Result<String> {
        return powerRepository.sendWol()
    }
}
```

- [ ] **Step 2: Create SendSoftSleepUseCase**

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class SendSoftSleepUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): Result<String> {
        return powerRepository.sendSoftSleep()
    }
}
```

- [ ] **Step 3: Create SendSuspendUseCase**

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class SendSuspendUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): Result<String> {
        return powerRepository.sendSuspend()
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(power): add SendWol, SendSoftSleep, SendSuspend use cases"
```

---

### Task 6: Strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (add before closing `</resources>`)

- [ ] **Step 1: Add power management strings**

Add before the closing `</resources>` tag:

```xml
    <!-- Power Management -->
    <string name="power_management">Power Management</string>
    <string name="power_wake_nas">NAS aufwecken</string>
    <string name="power_wake_nas_confirm">NAS über Fritz!Box aufwecken?</string>
    <string name="power_soft_sleep">Soft Sleep</string>
    <string name="power_soft_sleep_desc">Services pausieren, Disks herunterfahren</string>
    <string name="power_soft_sleep_confirm">NAS in den Soft-Sleep versetzen?\n\nServices werden pausiert und Disks heruntergefahren.</string>
    <string name="power_suspend">Suspend</string>
    <string name="power_suspend_desc">System komplett schlafen legen</string>
    <string name="power_suspend_confirm">NAS komplett suspendieren?\n\nDas System wird in den Schlafmodus versetzt.</string>
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "feat(power): add power management string resources"
```

---

### Task 7: DashboardViewModel — Admin State and Power Actions

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt`

- [ ] **Step 1: Add imports**

Add with the other imports at the top of the file:

```kotlin
import com.baluhost.android.domain.usecase.power.SendWolUseCase
import com.baluhost.android.domain.usecase.power.SendSoftSleepUseCase
import com.baluhost.android.domain.usecase.power.SendSuspendUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
```

- [ ] **Step 2: Add use cases to constructor**

Add these three parameters to the `DashboardViewModel` constructor (after `notificationWebSocketManager` parameter, line 55):

```kotlin
    private val sendWolUseCase: SendWolUseCase,
    private val sendSoftSleepUseCase: SendSoftSleepUseCase,
    private val sendSuspendUseCase: SendSuspendUseCase
```

- [ ] **Step 3: Add isAdmin state and snackbar event**

Add after the `_isVpnActive` / `isVpnActive` declarations (after line 74):

```kotlin
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _powerActionInProgress = MutableStateFlow(false)
    val powerActionInProgress: StateFlow<Boolean> = _powerActionInProgress.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()
```

- [ ] **Step 4: Add loadUserRole call in init block**

Add `loadUserRole()` to the `init` block (after `checkVpnConfig()` on line 82):

```kotlin
        loadUserRole()
```

- [ ] **Step 5: Add loadUserRole and power action methods**

Add before the `checkVpnConfig()` method (before line 332):

```kotlin
    private fun loadUserRole() {
        viewModelScope.launch {
            val role = preferencesManager.getUserRole().first() ?: "user"
            _isAdmin.value = role == "admin"
        }
    }

    fun sendWol() {
        viewModelScope.launch {
            _powerActionInProgress.value = true
            when (val result = sendWolUseCase()) {
                is Result.Success -> _snackbarEvent.emit("WoL-Signal gesendet")
                is Result.Error -> _snackbarEvent.emit(result.exception.message ?: "WoL fehlgeschlagen")
                else -> {}
            }
            _powerActionInProgress.value = false
        }
    }

    fun sendSoftSleep() {
        viewModelScope.launch {
            _powerActionInProgress.value = true
            when (val result = sendSoftSleepUseCase()) {
                is Result.Success -> _snackbarEvent.emit("Sleep-Modus aktiviert")
                is Result.Error -> _snackbarEvent.emit(result.exception.message ?: "Sleep fehlgeschlagen")
                else -> {}
            }
            _powerActionInProgress.value = false
        }
    }

    fun sendSuspend() {
        viewModelScope.launch {
            _powerActionInProgress.value = true
            when (val result = sendSuspendUseCase()) {
                is Result.Success -> _snackbarEvent.emit("System wird suspendiert")
                is Result.Error -> _snackbarEvent.emit(result.exception.message ?: "Suspend fehlgeschlagen")
                else -> {}
            }
            _powerActionInProgress.value = false
        }
    }
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(power): add admin state and power actions to DashboardViewModel"
```

---

### Task 8: DashboardScreen — Rewrite ServerStatusStrip with Power Dialog

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt`

- [ ] **Step 1: Add snackbar and admin state collection in DashboardScreen**

In the `DashboardScreen` composable (after line 78 where `isVpnActive` is collected), add:

```kotlin
    val isAdmin by viewModel.isAdmin.collectAsState()
    val powerActionInProgress by viewModel.powerActionInProgress.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
```

- [ ] **Step 2: Add SnackbarHost to Scaffold**

In the `Scaffold` call (around line 91), add the `snackbarHost` parameter. Keep existing `containerColor = Color.Transparent` intact:

```kotlin
    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Slate800,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        topBar = {
            // ... existing topBar unchanged ...
        },
        containerColor = Color.Transparent
```

- [ ] **Step 3: Update ServerStatusStrip call site**

Replace the `ServerStatusStrip` call (lines 208-211) with:

```kotlin
                    ServerStatusStrip(
                        isOnline = uiState.telemetry != null,
                        uptimeSeconds = uiState.telemetry?.uptime?.toLong(),
                        isAdmin = isAdmin,
                        isActionInProgress = powerActionInProgress,
                        onSendWol = { viewModel.sendWol() },
                        onSendSoftSleep = { viewModel.sendSoftSleep() },
                        onSendSuspend = { viewModel.sendSuspend() }
                    )
```

- [ ] **Step 4: Rewrite ServerStatusStrip composable**

Replace the entire `ServerStatusStrip` function (lines 800-884) with:

```kotlin
@Composable
private fun ServerStatusStrip(
    isOnline: Boolean,
    uptimeSeconds: Long?,
    isAdmin: Boolean,
    isActionInProgress: Boolean,
    onSendWol: () -> Unit,
    onSendSoftSleep: () -> Unit,
    onSendSuspend: () -> Unit
) {
    var showPowerDialog by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<PowerAction?>(null) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color(0xFF1E293B).copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (isOnline) Green500 else Red500,
                            CircleShape
                        )
                )
                Text(
                    text = if (isOnline) "Server Online" else "Server Offline",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                if (isOnline && uptimeSeconds != null) {
                    Text(
                        text = formatUptimeCompact(uptimeSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                }
            }
            if (isAdmin) {
                IconButton(
                    onClick = { showPowerDialog = true },
                    modifier = Modifier.size(32.dp),
                    enabled = !isActionInProgress
                ) {
                    if (isActionInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Sky400,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Power settings",
                            tint = Slate400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // Power options dialog
    if (showPowerDialog) {
        AlertDialog(
            onDismissRequest = { showPowerDialog = false },
            confirmButton = {
                TextButton(onClick = { showPowerDialog = false }) {
                    Text("Schließen", color = Slate400)
                }
            },
            title = {
                Text(
                    "Power Management",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isOnline) {
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
                    } else {
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
                }
            },
            containerColor = Slate900,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Confirmation dialog
    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            confirmButton = {
                TextButton(onClick = {
                    confirmAction = null
                    when (action) {
                        PowerAction.WOL -> onSendWol()
                        PowerAction.SOFT_SLEEP -> onSendSoftSleep()
                        PowerAction.SUSPEND -> onSendSuspend()
                    }
                }) {
                    Text("Bestätigen", color = action.color)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) {
                    Text("Abbrechen", color = Slate400)
                }
            },
            title = {
                Text(action.title, color = Color.White, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text(action.confirmMessage, color = Slate400)
            },
            containerColor = Slate900,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

private enum class PowerAction(
    val title: String,
    val confirmMessage: String,
    val color: Color
) {
    WOL(
        title = "NAS aufwecken",
        confirmMessage = "NAS über Fritz!Box aufwecken?",
        color = Green500
    ),
    SOFT_SLEEP(
        title = "Soft Sleep",
        confirmMessage = "NAS in den Soft-Sleep versetzen?\n\nServices werden pausiert und Disks heruntergefahren.",
        color = Sky400
    ),
    SUSPEND(
        title = "Suspend",
        confirmMessage = "NAS komplett suspendieren?\n\nDas System wird in den Schlafmodus versetzt.",
        color = Orange500
    )
}

@Composable
private fun PowerOptionButton(
    icon: ImageVector,
    label: String,
    description: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
            }
        }
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(power): rewrite ServerStatusStrip with admin-only power dialog"
```

---

### Task 9: Build Verification

- [ ] **Step 1: Run build**

```bash
cd /d/Programme\ \(x86\)/BaluApp && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any compilation issues**

Address any import or type errors that arise.

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat: add power management (WoL, Soft Sleep, Suspend) for admin users"
```
