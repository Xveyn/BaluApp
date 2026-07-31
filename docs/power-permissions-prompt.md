# Claude Prompt: Implement Power Permissions in BaluApp

## Context

The BaluHost backend now supports **granular per-user power permissions**. Admins can grant normal users the ability to perform individual power actions (Soft Sleep, Wake, Suspend, Wake-on-LAN). The backend has been fully implemented — this task updates the Android app to use the new permissions instead of the blanket `isAdmin` check.

**Project root:** `D:\Programme (x86)\BaluApp`

## Backend API Reference

The backend exposes these endpoints (all under `/api/system/sleep/`):

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/system/sleep/my-permissions` | Any authenticated user | Returns user's power permissions |
| `POST` | `/api/system/sleep/soft` | Requires `can_soft_sleep` permission (or admin) | Enter soft sleep |
| `POST` | `/api/system/sleep/wake` | Requires `can_wake` permission (or admin) | Wake from soft sleep |
| `POST` | `/api/system/sleep/suspend` | Requires `can_suspend` permission (or admin) | Suspend system |
| `POST` | `/api/system/sleep/wol` | Requires `can_wol` permission (or admin) | Wake-on-LAN (server-side) |

**`GET /api/system/sleep/my-permissions` response:**
```json
{
  "can_soft_sleep": true,
  "can_wake": false,
  "can_suspend": false,
  "can_wol": false
}
```
Admins always get all `true`. Non-admins get their granted permissions. Non-admins with no permissions get all `false`.

**Important:** WoL in the app currently goes directly through the Fritz!Box TR-064 client (client-side), NOT through the server API. The `can_wol` permission should control whether the WoL button is shown, but the actual WoL execution stays client-side via `FritzBoxTR064Client`. Do NOT change the WoL execution path.

**Important:** The `POST /api/system/sleep/wake` endpoint is NEW. The app currently does NOT have a `sendWake()` call in `SleepApi.kt`. It needs to be added.

## What Needs to Change

### 1. Add DTO: `MyPowerPermissionsDto`

**File:** `app/src/main/java/com/baluhost/android/data/remote/dto/PowerDto.kt`

Add to the existing file:
```kotlin
data class MyPowerPermissionsDto(
    @SerializedName("can_soft_sleep")
    val canSoftSleep: Boolean = false,
    @SerializedName("can_wake")
    val canWake: Boolean = false,
    @SerializedName("can_suspend")
    val canSuspend: Boolean = false,
    @SerializedName("can_wol")
    val canWol: Boolean = false
)
```

### 2. Update `SleepApi.kt`

**File:** `app/src/main/java/com/baluhost/android/data/remote/api/SleepApi.kt`

Current contents (entire file):
```kotlin
interface SleepApi {
    @POST("system/sleep/soft")
    suspend fun sendSoftSleep(): PowerActionResponse

    @POST("system/sleep/suspend")
    suspend fun sendSuspend(): PowerActionResponse
}
```

Add two methods:
```kotlin
@GET("system/sleep/my-permissions")
suspend fun getMyPermissions(): MyPowerPermissionsDto

@POST("system/sleep/wake")
suspend fun sendWake(): PowerActionResponse
```

Add the required imports for `GET` and the DTO.

### 3. Add Domain Model: `PowerPermissions`

**File:** Create `app/src/main/java/com/baluhost/android/domain/model/PowerPermissions.kt`

```kotlin
package com.baluhost.android.domain.model

data class PowerPermissions(
    val canSoftSleep: Boolean = false,
    val canWake: Boolean = false,
    val canSuspend: Boolean = false,
    val canWol: Boolean = false
) {
    val hasAnyPermission: Boolean
        get() = canSoftSleep || canWake || canSuspend || canWol
}
```

### 4. Update `PowerRepository` Interface

**File:** `app/src/main/java/com/baluhost/android/domain/repository/PowerRepository.kt`

Current contents:
```kotlin
interface PowerRepository {
    suspend fun sendWol(): Result<String>
    suspend fun sendSoftSleep(): Result<String>
    suspend fun sendSuspend(): Result<String>
    suspend fun checkNasStatus(): NasStatusResult
}
```

Add two methods:
```kotlin
suspend fun getMyPermissions(): Result<PowerPermissions>
suspend fun sendWake(): Result<String>
```

### 5. Update `PowerRepositoryImpl`

**File:** `app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt`

Add implementations:

```kotlin
override suspend fun getMyPermissions(): Result<PowerPermissions> {
    return try {
        val dto = sleepApi.getMyPermissions()
        Result.Success(PowerPermissions(
            canSoftSleep = dto.canSoftSleep,
            canWake = dto.canWake,
            canSuspend = dto.canSuspend,
            canWol = dto.canWol
        ))
    } catch (e: HttpException) {
        Result.Error(Exception("Berechtigungen konnten nicht geladen werden: ${e.message()}", e))
    } catch (e: Exception) {
        Result.Error(Exception("Server nicht erreichbar", e))
    }
}

override suspend fun sendWake(): Result<String> {
    return try {
        val response = sleepApi.sendWake()
        if (response.success) {
            Result.Success(response.message)
        } else {
            Result.Error(Exception(response.message))
        }
    } catch (e: HttpException) {
        Result.Error(Exception("Wake fehlgeschlagen: ${e.message()}", e))
    } catch (e: Exception) {
        Result.Error(Exception("Server nicht erreichbar", e))
    }
}
```

### 6. Add Use Cases

**Create:** `app/src/main/java/com/baluhost/android/domain/usecase/power/GetMyPowerPermissionsUseCase.kt`

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.model.PowerPermissions
import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetMyPowerPermissionsUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): Result<PowerPermissions> {
        return powerRepository.getMyPermissions()
    }
}
```

**Create:** `app/src/main/java/com/baluhost/android/domain/usecase/power/SendWakeUseCase.kt`

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class SendWakeUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): Result<String> {
        return powerRepository.sendWake()
    }
}
```

### 7. Update `DashboardViewModel`

**File:** `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt`

**Changes:**

a) Add `GetMyPowerPermissionsUseCase` and `SendWakeUseCase` to constructor injection:
```kotlin
private val getMyPowerPermissionsUseCase: GetMyPowerPermissionsUseCase,
private val sendWakeUseCase: SendWakeUseCase,
```

b) Replace `_isAdmin` StateFlow with a `_powerPermissions` StateFlow:
```kotlin
private val _powerPermissions = MutableStateFlow(PowerPermissions())
val powerPermissions: StateFlow<PowerPermissions> = _powerPermissions.asStateFlow()
```
Keep `_isAdmin` as well (it's still used by `ServerStatusStrip` to decide whether to show the power button at all — but now it should check `isAdmin || hasAnyPermission`).

c) Replace `loadUserRole()` with `loadPowerPermissions()`:
```kotlin
private fun loadPowerPermissions() {
    viewModelScope.launch {
        // First, set admin state from stored role (instant, no network)
        val role = preferencesManager.getUserRole().first() ?: "user"
        _isAdmin.value = role == "admin"

        // Then fetch granular permissions from server
        when (val result = getMyPowerPermissionsUseCase()) {
            is Result.Success -> _powerPermissions.value = result.data
            is Result.Error -> {
                // If admin, default to all permissions; if user, default to none
                if (_isAdmin.value) {
                    _powerPermissions.value = PowerPermissions(
                        canSoftSleep = true, canWake = true,
                        canSuspend = true, canWol = true
                    )
                }
                Log.w("DashboardViewModel", "Failed to load power permissions", result.exception)
            }
            else -> {}
        }
    }
}
```

Call `loadPowerPermissions()` in `init {}` instead of `loadUserRole()`.

d) Add `sendWake()` function:
```kotlin
fun sendWake() {
    viewModelScope.launch {
        _powerActionInProgress.value = true
        when (val result = sendWakeUseCase()) {
            is Result.Success -> {
                _snackbarEvent.emit("Wake-Signal gesendet")
                // After wake, start polling to detect when server comes online
                _nasStatus.value = NasStatus.UNKNOWN
                startPolling()
            }
            is Result.Error -> _snackbarEvent.emit(result.exception.message ?: "Wake fehlgeschlagen")
            else -> {}
        }
        _powerActionInProgress.value = false
    }
}
```

### 8. Update `DashboardScreen.kt` — `ServerStatusStrip`

**File:** `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt`

**Key changes to `ServerStatusStrip`:**

a) Change the signature — replace `isAdmin: Boolean` with `powerPermissions: PowerPermissions` (keep `isAdmin` too, needed for combined check):
```kotlin
@Composable
private fun ServerStatusStrip(
    nasStatus: NasStatus,
    uptimeSeconds: Long?,
    isAdmin: Boolean,
    powerPermissions: PowerPermissions,
    isActionInProgress: Boolean,
    wolAvailability: WolAvailability,
    onSendWol: () -> Unit,
    onSendWake: () -> Unit,
    onSendSoftSleep: () -> Unit,
    onSendSuspend: () -> Unit,
    onNavigateToFritzBoxSettings: () -> Unit
)
```

b) Show the power button when **admin OR has any permission** (currently line 957: `if (isAdmin)`):
```kotlin
if (isAdmin || powerPermissions.hasAnyPermission) {
```

c) In the power dialog content, filter options by permission:

For `NasStatus.SLEEPING`:
- Show "NAS aufwecken" (WoL) button only if `isAdmin || powerPermissions.canWol`
- **Add** "Server Wake" button (calls `onSendWake`) only if `isAdmin || powerPermissions.canWake`

For `NasStatus.ONLINE`:
- Show "Soft Sleep" only if `isAdmin || powerPermissions.canSoftSleep`
- Show "Suspend" only if `isAdmin || powerPermissions.canSuspend`

For `NasStatus.OFFLINE` / `UNKNOWN`:
- Show WoL options only if `isAdmin || powerPermissions.canWol`

d) Update the call site in `DashboardScreen` (around line 252-262):
```kotlin
ServerStatusStrip(
    nasStatus = nasStatus,
    uptimeSeconds = uiState.telemetry?.uptime?.toLong(),
    isAdmin = isAdmin,
    powerPermissions = powerPermissions,
    isActionInProgress = powerActionInProgress,
    wolAvailability = wolAvailability,
    onSendWol = { viewModel.sendWol() },
    onSendWake = { viewModel.sendWake() },
    onSendSoftSleep = { viewModel.sendSoftSleep() },
    onSendSuspend = { viewModel.sendSuspend() },
    onNavigateToFritzBoxSettings = onNavigateToFritzBoxSettings
)
```

And collect the new state:
```kotlin
val powerPermissions by viewModel.powerPermissions.collectAsState()
```

e) Add `WAKE` to the `PowerAction` enum:
```kotlin
WAKE(
    title = "Server aufwecken",
    confirmMessage = "Server aus dem Soft-Sleep aufwecken?",
    color = Green500
),
```

And handle it in the confirmation dialog's `when` block.

## Architecture Notes

- The app uses **MVVM + Clean Architecture**: API → DTO → Repository → UseCase → ViewModel → Composable
- DI is via **Hilt** — use cases with `@Inject constructor` are auto-provided, no DI module changes needed
- `SleepApi` is already provided in `NetworkModule.kt` (line 195-197), no changes needed there
- `PowerRepository` → `PowerRepositoryImpl` binding is already in `RepositoryModule.kt` (line 100-102), no changes needed
- Follow existing patterns: use `Result<T>` sealed class for repository returns, `StateFlow` for ViewModel state
- Keep all German UI strings consistent with existing app language (e.g. "Berechtigungen", "Server nicht erreichbar")
- Project convention for DataStore: booleans stored as `"true"/"false"` strings via `stringPreferencesKey`

## Files Summary

| Action | File |
|--------|------|
| Modify | `data/remote/dto/PowerDto.kt` — add `MyPowerPermissionsDto` |
| Modify | `data/remote/api/SleepApi.kt` — add `getMyPermissions()` + `sendWake()` |
| Create | `domain/model/PowerPermissions.kt` |
| Modify | `domain/repository/PowerRepository.kt` — add 2 methods |
| Modify | `data/repository/PowerRepositoryImpl.kt` — implement 2 methods |
| Create | `domain/usecase/power/GetMyPowerPermissionsUseCase.kt` |
| Create | `domain/usecase/power/SendWakeUseCase.kt` |
| Modify | `presentation/ui/screens/dashboard/DashboardViewModel.kt` — permissions + wake |
| Modify | `presentation/ui/screens/dashboard/DashboardScreen.kt` — permission-based UI |

All paths are relative to `app/src/main/java/com/baluhost/android/`.

## Testing

After implementation, verify:
1. **Admin user**: All power buttons visible (same as before)
2. **Normal user with no permissions**: Power button not shown in `ServerStatusStrip`
3. **Normal user with `can_soft_sleep=true`**: Only Soft Sleep + Wake buttons visible (wake is implied by soft_sleep on backend)
4. **Normal user with `can_wol=true`**: Only WoL button visible when NAS is offline/sleeping
5. **API failure graceful fallback**: If `getMyPermissions()` fails, admin gets all-true, user gets all-false
6. **Wake action**: New "Wake" button works when NAS is in soft sleep state
