# Display-Toggle und Gaming-Modus in der Android-App — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Android-App bekommt die zwei Power-Aktionen, die bisher nur die Webapp kann — den Display-Toggle des Servers und den Gaming-Modus.

**Architecture:** Beide Aktionen werden in die bestehende Kette API → DTO → Repository → UseCase → ViewModel → Composable eingehängt. Der Display-Toggle nutzt die Core-REST-Routen unter `system/sleep/desktop` und läuft über `PowerRepository`. Der Gaming-Modus ist eine Plugin-Menü-Aktion des gebundelten `steam_gaming`-Plugins; er wird — wie schon bei `GetEnergyDashboardUseCase` — direkt aus einem UseCase über `PluginApi` aufgerufen, ohne Repository. Am Server ist nichts zu ändern.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Retrofit + Gson, Coroutines/Flow. Tests: JUnit4, MockK, Turbine, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-31-app-desktop-toggle-gaming-mode-design.md`

## Global Constraints

- **Alle UI-Strings auf Deutsch.** Die App hat kein i18n-Framework; Strings stehen als Literale im Code, so wie in `DashboardScreen.kt` bereits vorhanden („Soft Sleep", „Server nicht erreichbar", „Bestätigen").
- **Fehlerbehandlung nach bestehendem Muster** in `PowerRepositoryImpl`: `catch (e: HttpException)` → spezifische deutsche Meldung mit `e.message()`, `catch (e: Exception)` → `"Server nicht erreichbar"`.
- **Repository-Rückgaben** sind immer `com.baluhost.android.util.Result<T>` (`Success`/`Error`/`Loading`). `when`-Blöcke über `Result` brauchen einen `else -> {}`-Zweig, weil `Loading` nie auftritt, der Compiler ihn aber kennt.
- **UseCases** sind `class X @Inject constructor(...)` mit `suspend operator fun invoke()`. Hilt providet sie automatisch — **keine** Änderungen an `di/`-Modulen nötig. `SleepApi` und `PluginApi` sind in `NetworkModule.kt` (Zeilen 204 bzw. 210) bereits provided.
- **DTO-Felder** tragen `@SerializedName("snake_case")` und haben Defaultwerte, damit fehlende Felder in der Antwort nicht crashen.
- **Test-Kommando:** `.\gradlew.bat testDebugUnitTest` (CI nutzt `./gradlew testDebugUnitTest`). Einzelne Klasse: `.\gradlew.bat testDebugUnitTest --tests "voll.qualifizierter.Klassenname"`.
- **Es gibt keinen `androidTest`-Sourceset** — keine Compose-UI-Tests. UI-Änderungen werden per Kompilierung und manueller Prüfung verifiziert.
- **Plugin-Identifikatoren:** Plugin heißt `steam_gaming`, die Action `gaming_mode`. Beide sind serverseitig festgelegt.
- **Gaming-Modus ist serverseitig Admin-only** (`get_current_admin` auf der Menü-Action-Route). Die App darf ihn nur Admins zeigen.

---

### Task 1: Desktop-Berechtigungen durchreichen

Der Server liefert `can_toggle_desktop` und `can_unlock_session` in `GET system/sleep/my-permissions` bereits mit; die App verwirft beide Felder. Diese Aufgabe reicht sie bis ins ViewModel durch. Ohne sie hat keine der folgenden Aufgaben eine Sichtbarkeitsregel.

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/domain/model/PowerPermissions.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/remote/dto/PowerDto.kt:63-72`
- Modify: `app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt:75-89`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt:445-451`
- Test: `app/src/test/java/com/baluhost/android/domain/model/PowerPermissionsTest.kt`

**Interfaces:**
- Consumes: nichts
- Produces: `PowerPermissions` mit den zusätzlichen Feldern `canToggleDesktop: Boolean` und `canUnlockSession: Boolean` (beide Default `false`); `hasAnyPermission` zählt `canToggleDesktop` mit, `canUnlockSession` nicht.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/baluhost/android/domain/model/PowerPermissionsTest.kt`:

```kotlin
package com.baluhost.android.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPermissionsTest {

    @Test
    fun `hasAnyPermission is true when only canToggleDesktop is granted`() {
        assertTrue(PowerPermissions(canToggleDesktop = true).hasAnyPermission)
    }

    @Test
    fun `hasAnyPermission is false when only canUnlockSession is granted`() {
        // canUnlockSession is an add-on to canToggleDesktop, never a right of its
        // own — on its own it must not make the power button appear.
        assertFalse(PowerPermissions(canUnlockSession = true).hasAnyPermission)
    }

    @Test
    fun `hasAnyPermission is false when nothing is granted`() {
        assertFalse(PowerPermissions().hasAnyPermission)
    }

    @Test
    fun `hasAnyPermission still reacts to the pre-existing rights`() {
        assertTrue(PowerPermissions(canSoftSleep = true).hasAnyPermission)
        assertTrue(PowerPermissions(canWake = true).hasAnyPermission)
        assertTrue(PowerPermissions(canSuspend = true).hasAnyPermission)
        assertTrue(PowerPermissions(canWol = true).hasAnyPermission)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.baluhost.android.domain.model.PowerPermissionsTest"`

Expected: FAIL beim Kompilieren mit `Cannot find a parameter with this name: canToggleDesktop`.

- [ ] **Step 3: Extend the domain model**

Replace the whole of `app/src/main/java/com/baluhost/android/domain/model/PowerPermissions.kt`:

```kotlin
package com.baluhost.android.domain.model

/**
 * The power actions the logged-in user may trigger, as reported by
 * GET system/sleep/my-permissions. Admins get every flag set by the server.
 */
data class PowerPermissions(
    val canSoftSleep: Boolean = false,
    val canWake: Boolean = false,
    val canSuspend: Boolean = false,
    val canWol: Boolean = false,
    val canToggleDesktop: Boolean = false,
    val canUnlockSession: Boolean = false
) {
    /**
     * Whether the power button is worth showing at all.
     *
     * canUnlockSession is deliberately left out: it is an add-on to
     * canToggleDesktop that the server applies while turning the displays back
     * on, not something a user can trigger by itself.
     */
    val hasAnyPermission: Boolean
        get() = canSoftSleep || canWake || canSuspend || canWol || canToggleDesktop
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.baluhost.android.domain.model.PowerPermissionsTest"`

Expected: PASS (4 Tests).

- [ ] **Step 5: Extend the DTO**

In `app/src/main/java/com/baluhost/android/data/remote/dto/PowerDto.kt` replace `MyPowerPermissionsDto` (Zeilen 63-72) with:

```kotlin
data class MyPowerPermissionsDto(
    @SerializedName("can_soft_sleep")
    val canSoftSleep: Boolean = false,
    @SerializedName("can_wake")
    val canWake: Boolean = false,
    @SerializedName("can_suspend")
    val canSuspend: Boolean = false,
    @SerializedName("can_wol")
    val canWol: Boolean = false,
    @SerializedName("can_toggle_desktop")
    val canToggleDesktop: Boolean = false,
    @SerializedName("can_unlock_session")
    val canUnlockSession: Boolean = false
)
```

- [ ] **Step 6: Map the new fields in the repository**

In `app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt`, inside `getMyPermissions()`, replace the `PowerPermissions(...)` construction (Zeilen 78-83) with:

```kotlin
            Result.Success(PowerPermissions(
                canSoftSleep = dto.canSoftSleep,
                canWake = dto.canWake,
                canSuspend = dto.canSuspend,
                canWol = dto.canWol,
                canToggleDesktop = dto.canToggleDesktop,
                canUnlockSession = dto.canUnlockSession
            ))
```

- [ ] **Step 7: Extend the admin fallback in the ViewModel**

In `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt`, inside `loadPowerPermissions()`, replace the fallback construction (Zeilen 447-450) with:

```kotlin
                        _powerPermissions.value = PowerPermissions(
                            canSoftSleep = true, canWake = true,
                            canSuspend = true, canWol = true,
                            canToggleDesktop = true, canUnlockSession = true
                        )
```

Ohne die zwei neuen Flags verschwände der Display-Eintrag für Admins genau dann, wenn der Permissions-Abruf scheitert.

- [ ] **Step 8: Run the full test suite**

Run: `.\gradlew.bat testDebugUnitTest`

Expected: PASS, keine Regressionen.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/model/PowerPermissions.kt app/src/main/java/com/baluhost/android/data/remote/dto/PowerDto.kt app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt app/src/test/java/com/baluhost/android/domain/model/PowerPermissionsTest.kt
git commit -m "feat(power): carry desktop permissions through to the ViewModel"
```

---

### Task 2: Display-Status und -Toggle im Data- und Domain-Layer

**Files:**
- Create: `app/src/main/java/com/baluhost/android/domain/model/DesktopState.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/model/DesktopActionResult.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/remote/dto/PowerDto.kt` (anhängen)
- Modify: `app/src/main/java/com/baluhost/android/data/remote/api/SleepApi.kt`
- Modify: `app/src/main/java/com/baluhost/android/domain/repository/PowerRepository.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/power/GetDesktopStatusUseCase.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/power/EnableDesktopUseCase.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/power/DisableDesktopUseCase.kt`
- Test: `app/src/test/java/com/baluhost/android/data/repository/PowerRepositoryDesktopTest.kt`

**Interfaces:**
- Consumes: nichts aus Task 1
- Produces:
  - `enum class DesktopState { RUNNING, STOPPED, UNKNOWN }` mit `companion object { fun fromApi(state: String?): DesktopState }`
  - `data class DesktopActionResult(val message: String, val sessionUnlocked: Boolean?)`
  - `GetDesktopStatusUseCase.invoke(): Result<DesktopState>`
  - `EnableDesktopUseCase.invoke(): Result<DesktopActionResult>`
  - `DisableDesktopUseCase.invoke(): Result<String>`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/baluhost/android/data/repository/PowerRepositoryDesktopTest.kt`:

```kotlin
package com.baluhost.android.data.repository

import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.data.remote.dto.DesktopActionResponseDto
import com.baluhost.android.data.remote.dto.DesktopStatusDto
import com.baluhost.android.domain.model.DesktopState
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class PowerRepositoryDesktopTest {

    private lateinit var sleepApi: SleepApi
    private lateinit var repository: PowerRepositoryImpl

    @Before
    fun setup() {
        sleepApi = mockk()
        // The Fritz!Box client and the preferences are only used by the WoL and
        // status paths, which this test never touches.
        repository = PowerRepositoryImpl(
            sleepApi = sleepApi,
            fritzBoxClient = mockk(relaxed = true),
            preferencesManager = mockk(relaxed = true)
        )
    }

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaTypeOrNull()))
    )

    @Test
    fun `getDesktopStatus maps running to RUNNING`() = runTest {
        coEvery { sleepApi.getDesktopStatus() } returns
            DesktopStatusDto(state = "running", displayManager = "sddm", detail = null)

        val result = repository.getDesktopStatus()

        assertEquals(DesktopState.RUNNING, (result as Result.Success).data)
    }

    @Test
    fun `getDesktopStatus maps stopped to STOPPED`() = runTest {
        coEvery { sleepApi.getDesktopStatus() } returns
            DesktopStatusDto(state = "stopped", displayManager = "sddm", detail = null)

        val result = repository.getDesktopStatus()

        assertEquals(DesktopState.STOPPED, (result as Result.Success).data)
    }

    @Test
    fun `getDesktopStatus maps an unrecognised state to UNKNOWN`() = runTest {
        coEvery { sleepApi.getDesktopStatus() } returns
            DesktopStatusDto(state = "wobbling", displayManager = "sddm", detail = null)

        val result = repository.getDesktopStatus()

        assertEquals(DesktopState.UNKNOWN, (result as Result.Success).data)
    }

    @Test
    fun `enableDesktop carries a refused session unlock through`() = runTest {
        coEvery { sleepApi.enableDesktop() } returns DesktopActionResponseDto(
            success = true,
            message = "displays on",
            sessionUnlocked = false,
            unlockMessage = "not on LAN"
        )

        val result = repository.enableDesktop()

        assertEquals(false, (result as Result.Success).data.sessionUnlocked)
    }

    @Test
    fun `enableDesktop keeps a missing session unlock null`() = runTest {
        coEvery { sleepApi.enableDesktop() } returns DesktopActionResponseDto(
            success = true, message = "displays on", sessionUnlocked = null, unlockMessage = null
        )

        val result = repository.enableDesktop()

        assertNull((result as Result.Success).data.sessionUnlocked)
    }

    @Test
    fun `disableDesktop reports a 403 as an error`() = runTest {
        coEvery { sleepApi.disableDesktop() } throws httpError(403)

        assertTrue(repository.disableDesktop() is Result.Error)
    }

    @Test
    fun `disableDesktop reports a refused action with the server message`() = runTest {
        coEvery { sleepApi.disableDesktop() } returns DesktopActionResponseDto(
            success = false,
            message = "kscreen-doctor refused",
            sessionUnlocked = null,
            unlockMessage = null
        )

        val result = repository.disableDesktop()

        assertEquals("kscreen-doctor refused", (result as Result.Error).exception.message)
    }

    @Test
    fun `disableDesktop reports a dead connection as unreachable`() = runTest {
        coEvery { sleepApi.disableDesktop() } throws RuntimeException("no route to host")

        val result = repository.disableDesktop()

        assertEquals("Server nicht erreichbar", (result as Result.Error).exception.message)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.baluhost.android.data.repository.PowerRepositoryDesktopTest"`

Expected: FAIL beim Kompilieren mit `Unresolved reference: DesktopStatusDto`.

- [ ] **Step 3: Create the domain models**

Create `app/src/main/java/com/baluhost/android/domain/model/DesktopState.kt`:

```kotlin
package com.baluhost.android.domain.model

/**
 * Whether the server's displays are on. Reported by
 * GET system/sleep/desktop/status; UNKNOWN also stands in for a failed lookup,
 * in which case the UI shows no desktop entry at all.
 */
enum class DesktopState {
    RUNNING,
    STOPPED,
    UNKNOWN;

    companion object {
        /** Maps the server's state string; anything unrecognised becomes UNKNOWN. */
        fun fromApi(state: String?): DesktopState = when (state) {
            "running" -> RUNNING
            "stopped" -> STOPPED
            else -> UNKNOWN
        }
    }
}
```

Create `app/src/main/java/com/baluhost/android/domain/model/DesktopActionResult.kt`:

```kotlin
package com.baluhost.android.domain.model

/**
 * Outcome of turning the server's displays back on.
 *
 * [sessionUnlocked] is null when the server said nothing about the lock screen,
 * and false when it refused to unlock it. A refusal is not a failure — the
 * displays are on either way, the session just stays locked.
 */
data class DesktopActionResult(
    val message: String,
    val sessionUnlocked: Boolean?
)
```

- [ ] **Step 4: Add the DTOs**

Append to `app/src/main/java/com/baluhost/android/data/remote/dto/PowerDto.kt`:

```kotlin
data class DesktopStatusDto(
    @SerializedName("state")
    val state: String = "unknown",
    @SerializedName("display_manager")
    val displayManager: String = "",
    @SerializedName("detail")
    val detail: String? = null
)

/**
 * Covers both desktop routes. The disable route answers with success/message
 * only, so the two unlock fields stay null there.
 */
data class DesktopActionResponseDto(
    @SerializedName("success")
    val success: Boolean = false,
    @SerializedName("message")
    val message: String = "",
    @SerializedName("session_unlocked")
    val sessionUnlocked: Boolean? = null,
    @SerializedName("unlock_message")
    val unlockMessage: String? = null
)
```

- [ ] **Step 5: Add the API methods**

Replace the whole of `app/src/main/java/com/baluhost/android/data/remote/api/SleepApi.kt`:

```kotlin
package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.DesktopActionResponseDto
import com.baluhost.android.data.remote.dto.DesktopStatusDto
import com.baluhost.android.data.remote.dto.MyPowerPermissionsDto
import com.baluhost.android.data.remote.dto.PowerActionResponse
import retrofit2.http.GET
import retrofit2.http.POST

interface SleepApi {

    @POST("system/sleep/soft")
    suspend fun sendSoftSleep(): PowerActionResponse

    @POST("system/sleep/suspend")
    suspend fun sendSuspend(): PowerActionResponse

    @GET("system/sleep/my-permissions")
    suspend fun getMyPermissions(): MyPowerPermissionsDto

    @POST("system/sleep/wake")
    suspend fun sendWake(): PowerActionResponse

    @GET("system/sleep/desktop/status")
    suspend fun getDesktopStatus(): DesktopStatusDto

    @POST("system/sleep/desktop/disable")
    suspend fun disableDesktop(): DesktopActionResponseDto

    @POST("system/sleep/desktop/enable")
    suspend fun enableDesktop(): DesktopActionResponseDto
}
```

- [ ] **Step 6: Extend the repository interface**

Replace the whole of `app/src/main/java/com/baluhost/android/domain/repository/PowerRepository.kt`:

```kotlin
package com.baluhost.android.domain.repository

import com.baluhost.android.domain.model.DesktopActionResult
import com.baluhost.android.domain.model.DesktopState
import com.baluhost.android.domain.model.NasStatusResult
import com.baluhost.android.domain.model.PowerPermissions
import com.baluhost.android.util.Result

interface PowerRepository {
    suspend fun sendWol(): Result<String>
    suspend fun sendSoftSleep(): Result<String>
    suspend fun sendSuspend(): Result<String>
    suspend fun sendWake(): Result<String>
    suspend fun checkNasStatus(): NasStatusResult
    suspend fun getMyPermissions(): Result<PowerPermissions>
    suspend fun getDesktopStatus(): Result<DesktopState>
    suspend fun enableDesktop(): Result<DesktopActionResult>
    suspend fun disableDesktop(): Result<String>
}
```

- [ ] **Step 7: Implement them**

Add the imports `com.baluhost.android.domain.model.DesktopActionResult` and `com.baluhost.android.domain.model.DesktopState` to `app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt`, then add these three methods inside the class, right after `sendWake()`:

```kotlin
    override suspend fun getDesktopStatus(): Result<DesktopState> {
        return try {
            Result.Success(DesktopState.fromApi(sleepApi.getDesktopStatus().state))
        } catch (e: HttpException) {
            Result.Error(Exception("Desktop-Status nicht abrufbar: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun enableDesktop(): Result<DesktopActionResult> {
        return try {
            val response = sleepApi.enableDesktop()
            if (response.success) {
                Result.Success(DesktopActionResult(response.message, response.sessionUnlocked))
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.Error(Exception("Displays einschalten fehlgeschlagen: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    override suspend fun disableDesktop(): Result<String> {
        return try {
            val response = sleepApi.disableDesktop()
            if (response.success) {
                Result.Success(response.message)
            } else {
                Result.Error(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.Error(Exception("Displays ausschalten fehlgeschlagen: ${e.message()}", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }
```

- [ ] **Step 8: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.baluhost.android.data.repository.PowerRepositoryDesktopTest"`

Expected: PASS (8 Tests).

- [ ] **Step 9: Add the use cases**

Create `app/src/main/java/com/baluhost/android/domain/usecase/power/GetDesktopStatusUseCase.kt`:

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.model.DesktopState
import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetDesktopStatusUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): Result<DesktopState> {
        return powerRepository.getDesktopStatus()
    }
}
```

Create `app/src/main/java/com/baluhost/android/domain/usecase/power/EnableDesktopUseCase.kt`:

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.model.DesktopActionResult
import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class EnableDesktopUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): Result<DesktopActionResult> {
        return powerRepository.enableDesktop()
    }
}
```

Create `app/src/main/java/com/baluhost/android/domain/usecase/power/DisableDesktopUseCase.kt`:

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.repository.PowerRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class DisableDesktopUseCase @Inject constructor(
    private val powerRepository: PowerRepository
) {
    suspend operator fun invoke(): Result<String> {
        return powerRepository.disableDesktop()
    }
}
```

- [ ] **Step 10: Run the full test suite**

Run: `.\gradlew.bat testDebugUnitTest`

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/model/DesktopState.kt app/src/main/java/com/baluhost/android/domain/model/DesktopActionResult.kt app/src/main/java/com/baluhost/android/data/remote/dto/PowerDto.kt app/src/main/java/com/baluhost/android/data/remote/api/SleepApi.kt app/src/main/java/com/baluhost/android/domain/repository/PowerRepository.kt app/src/main/java/com/baluhost/android/data/repository/PowerRepositoryImpl.kt app/src/main/java/com/baluhost/android/domain/usecase/power/ app/src/test/java/com/baluhost/android/data/repository/PowerRepositoryDesktopTest.kt
git commit -m "feat(power): add desktop status and display toggle to the data layer"
```

---

### Task 3: Gaming-Modus im Data- und Domain-Layer

Der Gaming-Modus ist keine Core-Route, sondern eine Menü-Aktion des gebundelten `steam_gaming`-Plugins. Gebundelt heißt mitgeliefert, nicht unabschaltbar — `GET plugins/ui/manifest` listet nur aktive Plugins und ist damit der maßgebliche Verfügbarkeits-Check. Das Manifest liefert nebenbei die deutschen Übersetzungen des Plugins, die gebraucht werden, weil die Aktion mit einem `message_key` statt einem fertigen Satz antwortet.

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/remote/dto/PluginConfigDto.kt` (anhängen)
- Modify: `app/src/main/java/com/baluhost/android/data/remote/api/PluginApi.kt`
- Create: `app/src/main/java/com/baluhost/android/data/local/PluginTranslationCache.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/plugin/GamingMode.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/plugin/IsGamingModeAvailableUseCase.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/plugin/StartGamingModeUseCase.kt`
- Test: `app/src/test/java/com/baluhost/android/domain/usecase/plugin/GamingModeUseCaseTest.kt`

**Interfaces:**
- Consumes: nichts aus Task 1 oder 2
- Produces:
  - `IsGamingModeAvailableUseCase.invoke(): Boolean` — kein `Result`, weil ein Fehlschlag hier bedeutet „nicht anzeigen" und sonst nichts
  - `StartGamingModeUseCase.invoke(): Result<String>` — `Success` trägt die aufgelöste Erfolgsmeldung des Plugins, `Error` die aufgelöste Fehlermeldung

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/baluhost/android/domain/usecase/plugin/GamingModeUseCaseTest.kt`:

```kotlin
package com.baluhost.android.domain.usecase.plugin

import com.baluhost.android.data.local.PluginTranslationCache
import com.baluhost.android.data.remote.api.PluginApi
import com.baluhost.android.data.remote.dto.PluginMenuActionResultDto
import com.baluhost.android.data.remote.dto.PluginMenuItemDto
import com.baluhost.android.data.remote.dto.PluginUiInfoDto
import com.baluhost.android.data.remote.dto.PluginUiManifestDto
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class GamingModeUseCaseTest {

    private lateinit var pluginApi: PluginApi
    private lateinit var cache: PluginTranslationCache

    private val steamPlugin = PluginUiInfoDto(
        name = "steam_gaming",
        menuItems = listOf(PluginMenuItemDto(id = "gaming_mode")),
        translations = mapOf(
            "de" to mapOf(
                "menu_gaming_mode_started" to "Gaming-Modus gestartet",
                "menu_steam_failed" to "Displays sind an, aber Steam startete nicht"
            )
        )
    )

    @Before
    fun setup() {
        pluginApi = mockk()
        cache = PluginTranslationCache()
    }

    @Test
    fun `available when the plugin contributes the gaming_mode action`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(steamPlugin))

        assertTrue(IsGamingModeAvailableUseCase(pluginApi, cache)())
    }

    @Test
    fun `unavailable when the plugin is missing from the manifest`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(emptyList())

        assertFalse(IsGamingModeAvailableUseCase(pluginApi, cache)())
    }

    @Test
    fun `unavailable when the plugin no longer contributes the action`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(
            listOf(steamPlugin.copy(menuItems = emptyList()))
        )

        assertFalse(IsGamingModeAvailableUseCase(pluginApi, cache)())
    }

    @Test
    fun `unavailable when the manifest call fails`() = runTest {
        coEvery { pluginApi.getUiManifest() } throws RuntimeException("no network")

        assertFalse(IsGamingModeAvailableUseCase(pluginApi, cache)())
    }

    @Test
    fun `a refused action is reported with the German plugin string`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(steamPlugin))
        IsGamingModeAvailableUseCase(pluginApi, cache)()
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode") } returns
            PluginMenuActionResultDto(
                ok = false,
                messageKey = "menu_steam_failed",
                messageText = "Displays are on, but Steam did not start"
            )

        val result = StartGamingModeUseCase(pluginApi, cache)()

        assertEquals(
            "Displays sind an, aber Steam startete nicht",
            (result as Result.Error).exception.message
        )
    }

    @Test
    fun `a successful action returns the German plugin string`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(steamPlugin))
        IsGamingModeAvailableUseCase(pluginApi, cache)()
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode") } returns
            PluginMenuActionResultDto(
                ok = true,
                messageKey = "menu_gaming_mode_started",
                messageText = "Gaming mode started"
            )

        val result = StartGamingModeUseCase(pluginApi, cache)()

        assertEquals("Gaming-Modus gestartet", (result as Result.Success).data)
    }

    @Test
    fun `falls back to message_text when the key was never translated`() = runTest {
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode") } returns
            PluginMenuActionResultDto(
                ok = true,
                messageKey = "menu_unknown_key",
                messageText = "Gaming mode started"
            )

        val result = StartGamingModeUseCase(pluginApi, cache)()

        assertEquals("Gaming mode started", (result as Result.Success).data)
    }

    @Test
    fun `a 403 is reported as the action being unavailable`() = runTest {
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode") } throws HttpException(
            Response.error<Any>(403, "".toResponseBody("application/json".toMediaTypeOrNull()))
        )

        val result = StartGamingModeUseCase(pluginApi, cache)()

        assertEquals("Gaming-Modus nicht verfügbar", (result as Result.Error).exception.message)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.baluhost.android.domain.usecase.plugin.GamingModeUseCaseTest"`

Expected: FAIL beim Kompilieren mit `Unresolved reference: PluginTranslationCache`.

- [ ] **Step 3: Add the DTOs**

Append to `app/src/main/java/com/baluhost/android/data/remote/dto/PluginConfigDto.kt`:

```kotlin
/**
 * A menu action a plugin contributes to the power menu. Only the id is read —
 * label, icon and tone are fixed in the app, and Gson drops what it does not know.
 */
data class PluginMenuItemDto(
    @SerializedName("id")
    val id: String = ""
)

data class PluginUiInfoDto(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("menu_items")
    val menuItems: List<PluginMenuItemDto> = emptyList(),
    /** Language code to string key to translated text, as the plugin declares it. */
    @SerializedName("translations")
    val translations: Map<String, Map<String, String>>? = null
)

data class PluginUiManifestDto(
    @SerializedName("plugins")
    val plugins: List<PluginUiInfoDto> = emptyList()
)

/**
 * A plugin reports its own failures here as ok=false, not as an HTTP error, and
 * names the message by key rather than sending a finished sentence.
 */
data class PluginMenuActionResultDto(
    @SerializedName("ok")
    val ok: Boolean = false,
    @SerializedName("message_key")
    val messageKey: String? = null,
    @SerializedName("message_text")
    val messageText: String = ""
)
```

- [ ] **Step 4: Add the API methods**

Replace the whole of `app/src/main/java/com/baluhost/android/data/remote/api/PluginApi.kt`:

```kotlin
package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.PluginConfigResponseDto
import com.baluhost.android.data.remote.dto.PluginConfigUpdateRequestDto
import com.baluhost.android.data.remote.dto.PluginMenuActionResultDto
import com.baluhost.android.data.remote.dto.PluginUiManifestDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface PluginApi {

    @GET("plugins/tapo_smart_plug/config")
    suspend fun getTapoPluginConfig(): PluginConfigResponseDto

    @PUT("plugins/tapo_smart_plug/config")
    suspend fun updateTapoPluginConfig(
        @Body request: PluginConfigUpdateRequestDto
    ): PluginConfigResponseDto

    /** Lists the enabled plugins with the UI they contribute. */
    @GET("plugins/ui/manifest")
    suspend fun getUiManifest(): PluginUiManifestDto

    /** Runs a plugin-contributed menu action. Admin only, server-enforced. */
    @POST("plugins/{name}/menu-actions/{actionId}")
    suspend fun runMenuAction(
        @Path("name") name: String,
        @Path("actionId") actionId: String
    ): PluginMenuActionResultDto
}
```

- [ ] **Step 5: Add the translation cache**

Create `app/src/main/java/com/baluhost/android/data/local/PluginTranslationCache.kt`:

```kotlin
package com.baluhost.android.data.local

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory store for the German plugin strings read from the UI manifest.
 *
 * A plugin's menu action answers with a message key rather than a finished
 * sentence, so the key has to be resolved against the translations the same
 * manifest carries. Refilled every time the power dialog opens; nothing is
 * persisted, and a cold start simply falls back to the English message_text.
 */
@Singleton
class PluginTranslationCache @Inject constructor() {

    private val translations = ConcurrentHashMap<String, Map<String, String>>()

    fun put(pluginName: String, strings: Map<String, String>) {
        translations[pluginName] = strings
    }

    /** The translated string for [key], or [fallback] if the key is unknown. */
    fun resolve(pluginName: String, key: String?, fallback: String): String {
        if (key.isNullOrEmpty()) return fallback
        return translations[pluginName]?.get(key) ?: fallback
    }
}
```

- [ ] **Step 6: Add the shared identifiers**

Create `app/src/main/java/com/baluhost/android/domain/usecase/plugin/GamingMode.kt`:

```kotlin
package com.baluhost.android.domain.usecase.plugin

/** Identifiers of the bundled steam_gaming plugin's power-menu action. */
internal object GamingMode {
    const val PLUGIN_NAME = "steam_gaming"
    const val ACTION_ID = "gaming_mode"
    const val LANGUAGE = "de"
}
```

- [ ] **Step 7: Add the use cases**

Create `app/src/main/java/com/baluhost/android/domain/usecase/plugin/IsGamingModeAvailableUseCase.kt`:

```kotlin
package com.baluhost.android.domain.usecase.plugin

import com.baluhost.android.data.local.PluginTranslationCache
import com.baluhost.android.data.remote.api.PluginApi
import javax.inject.Inject

/**
 * Whether the steam_gaming plugin is enabled and still contributes its
 * gaming_mode action. The plugin ships with BaluHost but can be switched off,
 * and the UI manifest lists only enabled plugins — so this is the check that
 * counts.
 *
 * Fills [PluginTranslationCache] on the way through, so [StartGamingModeUseCase]
 * can resolve the message key it gets back.
 *
 * Failures are swallowed on purpose: this is a discovery call, and the only
 * sensible answer to "cannot tell" is to hide the entry.
 */
class IsGamingModeAvailableUseCase @Inject constructor(
    private val pluginApi: PluginApi,
    private val translationCache: PluginTranslationCache
) {
    suspend operator fun invoke(): Boolean {
        return try {
            val plugin = pluginApi.getUiManifest().plugins
                .firstOrNull { it.name == GamingMode.PLUGIN_NAME }
                ?: return false

            plugin.translations?.get(GamingMode.LANGUAGE)?.let {
                translationCache.put(GamingMode.PLUGIN_NAME, it)
            }

            plugin.menuItems.any { it.id == GamingMode.ACTION_ID }
        } catch (_: Exception) {
            false
        }
    }
}
```

Create `app/src/main/java/com/baluhost/android/domain/usecase/plugin/StartGamingModeUseCase.kt`:

```kotlin
package com.baluhost.android.domain.usecase.plugin

import com.baluhost.android.data.local.PluginTranslationCache
import com.baluhost.android.data.remote.api.PluginApi
import com.baluhost.android.util.Result
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Runs the steam_gaming plugin's gaming_mode action: displays on, session
 * unlocked if permitted, Steam Big Picture launched.
 *
 * The plugin reports its own failures as ok=false with a message key rather than
 * as an HTTP error, so both branches are mapped by hand. Either way the caller
 * gets a finished German sentence.
 */
class StartGamingModeUseCase @Inject constructor(
    private val pluginApi: PluginApi,
    private val translationCache: PluginTranslationCache
) {
    suspend operator fun invoke(): Result<String> {
        return try {
            val response = pluginApi.runMenuAction(GamingMode.PLUGIN_NAME, GamingMode.ACTION_ID)
            val message = translationCache.resolve(
                GamingMode.PLUGIN_NAME, response.messageKey, response.messageText
            )
            if (response.ok) Result.Success(message) else Result.Error(Exception(message))
        } catch (e: HttpException) {
            Result.Error(Exception("Gaming-Modus nicht verfügbar", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.baluhost.android.domain.usecase.plugin.GamingModeUseCaseTest"`

Expected: PASS (8 Tests).

- [ ] **Step 9: Run the full test suite**

Run: `.\gradlew.bat testDebugUnitTest`

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/remote/dto/PluginConfigDto.kt app/src/main/java/com/baluhost/android/data/remote/api/PluginApi.kt app/src/main/java/com/baluhost/android/data/local/PluginTranslationCache.kt app/src/main/java/com/baluhost/android/domain/usecase/plugin/ app/src/test/java/com/baluhost/android/domain/usecase/plugin/GamingModeUseCaseTest.kt
git commit -m "feat(plugin): add gaming mode discovery and menu action"
```

---

### Task 4: ViewModel-Zustand und -Aktionen

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt`
- Modify: `app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelVpnActionTest.kt:72-95`
- Test: `app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelDesktopActionTest.kt`

**Interfaces:**
- Consumes: `GetDesktopStatusUseCase`, `EnableDesktopUseCase`, `DisableDesktopUseCase` (Task 2); `IsGamingModeAvailableUseCase`, `StartGamingModeUseCase` (Task 3); `DesktopState`, `DesktopActionResult` (Task 2)
- Produces: auf `DashboardViewModel`
  - `val desktopState: StateFlow<DesktopState>`
  - `val gamingModeAvailable: StateFlow<Boolean>`
  - `fun onPowerDialogOpened()`
  - `fun enableDesktop()`
  - `fun disableDesktop()`
  - `fun startGamingMode()`

  Die fünf neuen Konstruktor-Parameter stehen **am Ende** der Parameterliste, hinter `sendWakeUseCase`, in dieser Reihenfolge: `getDesktopStatusUseCase`, `enableDesktopUseCase`, `disableDesktopUseCase`, `isGamingModeAvailableUseCase`, `startGamingModeUseCase`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelDesktopActionTest.kt`:

```kotlin
package com.baluhost.android.presentation.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import app.cash.turbine.test
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.notification.NotificationWebSocketManager
import com.baluhost.android.domain.model.DesktopActionResult
import com.baluhost.android.domain.model.DesktopState
import com.baluhost.android.domain.usecase.plugin.IsGamingModeAvailableUseCase
import com.baluhost.android.domain.usecase.plugin.StartGamingModeUseCase
import com.baluhost.android.domain.model.PowerPermissions
import com.baluhost.android.domain.usecase.power.DisableDesktopUseCase
import com.baluhost.android.domain.usecase.power.EnableDesktopUseCase
import com.baluhost.android.domain.usecase.power.GetDesktopStatusUseCase
import com.baluhost.android.domain.usecase.power.GetMyPowerPermissionsUseCase
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelDesktopActionTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var notificationWebSocketManager: NotificationWebSocketManager
    private lateinit var getDesktopStatusUseCase: GetDesktopStatusUseCase
    private lateinit var enableDesktopUseCase: EnableDesktopUseCase
    private lateinit var disableDesktopUseCase: DisableDesktopUseCase
    private lateinit var isGamingModeAvailableUseCase: IsGamingModeAvailableUseCase
    private lateinit var startGamingModeUseCase: StartGamingModeUseCase
    private lateinit var getMyPowerPermissionsUseCase: GetMyPowerPermissionsUseCase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        preferencesManager = mockk(relaxed = true)
        notificationWebSocketManager = mockk(relaxed = true)
        getDesktopStatusUseCase = mockk()
        enableDesktopUseCase = mockk()
        disableDesktopUseCase = mockk()
        isGamingModeAvailableUseCase = mockk()
        startGamingModeUseCase = mockk()
        getMyPowerPermissionsUseCase = mockk()

        every { preferencesManager.getServerUrl() } returns flowOf("http://192.168.1.100:3000")
        every { preferencesManager.getUsername() } returns flowOf("testuser")
        every { preferencesManager.getUserRole() } returns flowOf("admin")
        every { preferencesManager.getDeviceId() } returns flowOf("device1")
        every { preferencesManager.getVpnConfig() } returns flowOf(null)
        every { preferencesManager.isAutoVpnOnExternal() } returns flowOf(false)
        every { notificationWebSocketManager.unreadCount } returns MutableStateFlow(0)

        coEvery { getDesktopStatusUseCase() } returns Result.Success(DesktopState.UNKNOWN)
        coEvery { isGamingModeAvailableUseCase() } returns false
        // loadPowerPermissions() runs in the ViewModel's init block, so every
        // stub it depends on has to be in place before createViewModel().
        coEvery { getMyPowerPermissionsUseCase() } returns Result.Success(PowerPermissions())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): DashboardViewModel {
        val networkStateManager: com.baluhost.android.util.NetworkStateManager = mockk(relaxed = true)
        every { networkStateManager.observeHomeNetworkStatus(any()) } returns MutableSharedFlow()
        every { networkStateManager.isVpnActive() } returns false

        return DashboardViewModel(
            getFilesUseCase = mockk(relaxed = true),
            getRecentFilesUseCase = mockk(relaxed = true),
            getCacheStatsUseCase = mockk(relaxed = true),
            getSystemTelemetryUseCase = mockk(relaxed = true),
            getCurrentUptimeUseCase = mockk(relaxed = true),
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
            checkNasStatusUseCase = mockk(relaxed = true),
            getMyPowerPermissionsUseCase = getMyPowerPermissionsUseCase,
            sendWakeUseCase = mockk(relaxed = true),
            getDesktopStatusUseCase = getDesktopStatusUseCase,
            enableDesktopUseCase = enableDesktopUseCase,
            disableDesktopUseCase = disableDesktopUseCase,
            isGamingModeAvailableUseCase = isGamingModeAvailableUseCase,
            startGamingModeUseCase = startGamingModeUseCase
        )
    }

    /** Cancels viewModelScope, including the endless polling loop, so runTest can finish. */
    private fun clearViewModel(vm: DashboardViewModel) {
        try {
            val clearMethod = ViewModel::class.java.getDeclaredMethod("clear")
            clearMethod.isAccessible = true
            clearMethod.invoke(vm)
        } catch (_: NoSuchMethodException) {
            val onClearedMethod = ViewModel::class.java.getDeclaredMethod("onCleared")
            onClearedMethod.isAccessible = true
            onClearedMethod.invoke(vm)
        }
    }

    @Test
    fun `onPowerDialogOpened takes the desktop state from the server`() = runTest {
        coEvery { getDesktopStatusUseCase() } returns Result.Success(DesktopState.STOPPED)
        val vm = createViewModel()

        vm.onPowerDialogOpened()

        assertEquals(DesktopState.STOPPED, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `a failing desktop status resets the state to UNKNOWN`() = runTest {
        coEvery { getDesktopStatusUseCase() } returns Result.Success(DesktopState.STOPPED)
        val vm = createViewModel()
        vm.onPowerDialogOpened()
        assertEquals(DesktopState.STOPPED, vm.desktopState.value)

        // The server went away between two openings of the dialog. A stale
        // STOPPED would offer "enable" against a machine nobody can reach.
        coEvery { getDesktopStatusUseCase() } returns Result.Error(Exception("Server nicht erreichbar"))

        vm.snackbarEvent.test {
            vm.onPowerDialogOpened()

            assertEquals(DesktopState.UNKNOWN, vm.desktopState.value)
            // A discovery call must stay silent — the entry just does not appear.
            expectNoEvents()
        }
        clearViewModel(vm)
    }

    @Test
    fun `the admin fallback grants the desktop right when the permission call fails`() = runTest {
        coEvery { getMyPowerPermissionsUseCase() } returns Result.Error(Exception("Server nicht erreichbar"))

        val vm = createViewModel()

        assertTrue(vm.powerPermissions.value.canToggleDesktop)
        assertTrue(vm.powerPermissions.value.canUnlockSession)
        clearViewModel(vm)
    }

    @Test
    fun `a non-admin gets no permissions when the permission call fails`() = runTest {
        every { preferencesManager.getUserRole() } returns flowOf("user")
        coEvery { getMyPowerPermissionsUseCase() } returns Result.Error(Exception("Server nicht erreichbar"))

        val vm = createViewModel()

        assertFalse(vm.powerPermissions.value.hasAnyPermission)
        clearViewModel(vm)
    }

    @Test
    fun `onPowerDialogOpened does not ask about gaming mode for a non-admin`() = runTest {
        every { preferencesManager.getUserRole() } returns flowOf("user")
        val vm = createViewModel()

        vm.onPowerDialogOpened()

        coVerify(exactly = 0) { isGamingModeAvailableUseCase() }
        assertFalse(vm.gamingModeAvailable.value)
        clearViewModel(vm)
    }

    @Test
    fun `onPowerDialogOpened asks about gaming mode for an admin`() = runTest {
        coEvery { isGamingModeAvailableUseCase() } returns true
        val vm = createViewModel()

        vm.onPowerDialogOpened()

        assertTrue(vm.gamingModeAvailable.value)
        clearViewModel(vm)
    }

    @Test
    fun `enableDesktop with a refused unlock emits exactly one snackbar`() = runTest {
        coEvery { enableDesktopUseCase() } returns
            Result.Success(DesktopActionResult("displays on", sessionUnlocked = false))
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.enableDesktop()

            assertEquals("Displays an – Session ist noch gesperrt", awaitItem())
            expectNoEvents()
        }
        assertEquals(DesktopState.RUNNING, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `enableDesktop with a granted unlock reports plain success`() = runTest {
        coEvery { enableDesktopUseCase() } returns
            Result.Success(DesktopActionResult("displays on", sessionUnlocked = true))
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.enableDesktop()

            assertEquals("Displays aktiviert", awaitItem())
        }
        clearViewModel(vm)
    }

    @Test
    fun `enableDesktop reports a failure and leaves the state alone`() = runTest {
        coEvery { enableDesktopUseCase() } returns Result.Error(Exception("Displays einschalten fehlgeschlagen: 403"))
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.enableDesktop()

            assertEquals("Displays einschalten fehlgeschlagen: 403", awaitItem())
        }
        assertEquals(DesktopState.UNKNOWN, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `disableDesktop reports success and flips the state to STOPPED`() = runTest {
        coEvery { disableDesktopUseCase() } returns Result.Success("displays off")
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.disableDesktop()

            assertEquals("Displays deaktiviert", awaitItem())
        }
        assertEquals(DesktopState.STOPPED, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `startGamingMode passes the plugin message on and turns the displays on`() = runTest {
        coEvery { startGamingModeUseCase() } returns Result.Success("Gaming-Modus gestartet")
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.startGamingMode()

            assertEquals("Gaming-Modus gestartet", awaitItem())
        }
        // The action turns the displays on before it launches Big Picture.
        assertEquals(DesktopState.RUNNING, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `startGamingMode reports the plugin's own failure`() = runTest {
        coEvery { startGamingModeUseCase() } returns
            Result.Error(Exception("Displays sind an, aber Steam startete nicht"))
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.startGamingMode()

            assertEquals("Displays sind an, aber Steam startete nicht", awaitItem())
        }
        clearViewModel(vm)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.baluhost.android.presentation.ui.screens.dashboard.DashboardViewModelDesktopActionTest"`

Expected: FAIL beim Kompilieren mit `Cannot find a parameter with this name: getDesktopStatusUseCase`.

- [ ] **Step 3: Extend the ViewModel constructor and imports**

In `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt` add these imports next to the existing `domain.model` and `domain.usecase.power` imports:

```kotlin
import com.baluhost.android.domain.model.DesktopState
import com.baluhost.android.domain.usecase.plugin.IsGamingModeAvailableUseCase
import com.baluhost.android.domain.usecase.plugin.StartGamingModeUseCase
import com.baluhost.android.domain.usecase.power.DisableDesktopUseCase
import com.baluhost.android.domain.usecase.power.EnableDesktopUseCase
import com.baluhost.android.domain.usecase.power.GetDesktopStatusUseCase
```

Then replace the last constructor line (`private val sendWakeUseCase: SendWakeUseCase`, Zeile 77) with:

```kotlin
    private val sendWakeUseCase: SendWakeUseCase,
    private val getDesktopStatusUseCase: GetDesktopStatusUseCase,
    private val enableDesktopUseCase: EnableDesktopUseCase,
    private val disableDesktopUseCase: DisableDesktopUseCase,
    private val isGamingModeAvailableUseCase: IsGamingModeAvailableUseCase,
    private val startGamingModeUseCase: StartGamingModeUseCase
```

- [ ] **Step 4: Add the state flows**

In the same file, right after the `_wolAvailability` block (Zeilen 110-111), insert:

```kotlin
    private val _desktopState = MutableStateFlow(DesktopState.UNKNOWN)
    val desktopState: StateFlow<DesktopState> = _desktopState.asStateFlow()

    private val _gamingModeAvailable = MutableStateFlow(false)
    val gamingModeAvailable: StateFlow<Boolean> = _gamingModeAvailable.asStateFlow()
```

- [ ] **Step 5: Add the actions**

In the same file, insert these four functions right after `sendWake()` (which ends at Zeile 552), before `handlePostPowerAction()`:

```kotlin
    /**
     * Refreshes what the power dialog needs to decide which desktop entries to
     * show. Called when the dialog opens, the way the webapp refetches on every
     * dropdown open — the previous values stay put while this runs, so the
     * entries do not flicker.
     */
    fun onPowerDialogOpened() {
        viewModelScope.launch {
            when (val result = getDesktopStatusUseCase()) {
                is Result.Success -> _desktopState.value = result.data
                is Result.Error -> {
                    // No snackbar: this is a discovery call, and UNKNOWN simply
                    // means the desktop entry is not offered.
                    _desktopState.value = DesktopState.UNKNOWN
                    Log.w("DashboardViewModel", "Failed to load desktop status", result.exception)
                }
                else -> {}
            }
            // The plugin menu-action route is admin-only server-side, so asking
            // on anyone else's behalf could only ever produce a 403.
            if (_isAdmin.value) {
                _gamingModeAvailable.value = isGamingModeAvailableUseCase()
            }
        }
    }

    fun enableDesktop() {
        viewModelScope.launch {
            _powerActionInProgress.value = true
            when (val result = enableDesktopUseCase()) {
                is Result.Success -> {
                    _desktopState.value = DesktopState.RUNNING
                    // One emission only: _snackbarEvent buffers a single item, so
                    // a separate lock-screen notice would swallow this message.
                    _snackbarEvent.emit(
                        if (result.data.sessionUnlocked == false) {
                            "Displays an – Session ist noch gesperrt"
                        } else {
                            "Displays aktiviert"
                        }
                    )
                }
                is Result.Error -> _snackbarEvent.emit(
                    result.exception.message ?: "Displays einschalten fehlgeschlagen"
                )
                else -> {}
            }
            _powerActionInProgress.value = false
        }
    }

    fun disableDesktop() {
        viewModelScope.launch {
            _powerActionInProgress.value = true
            when (val result = disableDesktopUseCase()) {
                is Result.Success -> {
                    _desktopState.value = DesktopState.STOPPED
                    _snackbarEvent.emit("Displays deaktiviert")
                }
                is Result.Error -> _snackbarEvent.emit(
                    result.exception.message ?: "Displays ausschalten fehlgeschlagen"
                )
                else -> {}
            }
            _powerActionInProgress.value = false
        }
    }

    fun startGamingMode() {
        viewModelScope.launch {
            _powerActionInProgress.value = true
            when (val result = startGamingModeUseCase()) {
                is Result.Success -> {
                    // The action turns the displays on before it launches Big
                    // Picture, so the toggle below it must not offer "enable".
                    _desktopState.value = DesktopState.RUNNING
                    // The plugin words its own outcome; the use case has already
                    // resolved it to German.
                    _snackbarEvent.emit(result.data)
                }
                is Result.Error -> _snackbarEvent.emit(
                    result.exception.message ?: "Gaming-Modus fehlgeschlagen"
                )
                else -> {}
            }
            _powerActionInProgress.value = false
        }
    }
```

- [ ] **Step 6: Fix the existing VPN test's ViewModel factory**

The constructor grew, so `DashboardViewModelVpnActionTest` no longer compiles. In `app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelVpnActionTest.kt` replace the last argument of `createViewModel()` (`sendWakeUseCase = mockk(relaxed = true)`, Zeile 93) with:

```kotlin
            sendWakeUseCase = mockk(relaxed = true),
            getDesktopStatusUseCase = mockk(relaxed = true),
            enableDesktopUseCase = mockk(relaxed = true),
            disableDesktopUseCase = mockk(relaxed = true),
            isGamingModeAvailableUseCase = mockk(relaxed = true),
            startGamingModeUseCase = mockk(relaxed = true)
```

- [ ] **Step 7: Run the full test suite**

Run: `.\gradlew.bat testDebugUnitTest`

Expected: PASS, inklusive der 12 neuen Tests und der unveränderten VPN-Tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/
git commit -m "feat(dashboard): add desktop toggle and gaming mode to the ViewModel"
```

---

### Task 5: Einträge im Power-Dialog

Es gibt keinen `androidTest`-Sourceset und damit keine Compose-UI-Tests im Projekt. Diese Aufgabe wird über Kompilierung und eine manuelle Prüfung verifiziert.

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt` — Import-Block (~Zeile 47), Aufrufstelle (Zeilen 254-266), `ServerStatusStrip`-Signatur (Zeilen 899-914), Dialog-Öffner (Zeile 965), `NasStatus.ONLINE`-Zweig (Zeilen 1033-1058)

**Interfaces:**
- Consumes: `DesktopState` (Task 2); `desktopState`, `gamingModeAvailable`, `onPowerDialogOpened()`, `enableDesktop()`, `disableDesktop()`, `startGamingMode()` (Task 4); `PowerPermissions.canToggleDesktop` (Task 1)
- Produces: nichts

Icon- und Farb-Importe sind bereits Wildcards (`androidx.compose.material.icons.filled.*` in Zeile 18, `presentation.ui.theme.*` in Zeile 51) — `DesktopAccessDisabled`, `DesktopWindows`, `SportsEsports` und `Violet500` brauchen keinen neuen Import.

- [ ] **Step 1: Add the DesktopState import**

In `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt`, next to the existing `import com.baluhost.android.domain.model.NasStatus` (Zeile 47), add:

```kotlin
import com.baluhost.android.domain.model.DesktopState
```

- [ ] **Step 2: Collect the new state at the call site**

In the same file, after `val wolAvailability by viewModel.wolAvailability.collectAsState()` (Zeile 90), add:

```kotlin
    val desktopState by viewModel.desktopState.collectAsState()
    val gamingModeAvailable by viewModel.gamingModeAvailable.collectAsState()
```

- [ ] **Step 3: Pass them into ServerStatusStrip**

Replace the `ServerStatusStrip(...)` call (Zeilen 254-266) with:

```kotlin
                    ServerStatusStrip(
                        nasStatus = nasStatus,
                        uptimeSeconds = uiState.telemetry?.uptime?.toLong(),
                        isAdmin = isAdmin,
                        powerPermissions = powerPermissions,
                        isActionInProgress = powerActionInProgress,
                        wolAvailability = wolAvailability,
                        desktopState = desktopState,
                        gamingModeAvailable = gamingModeAvailable,
                        onPowerDialogOpened = { viewModel.onPowerDialogOpened() },
                        onSendWol = { viewModel.sendWol() },
                        onSendWake = { viewModel.sendWake() },
                        onSendSoftSleep = { viewModel.sendSoftSleep() },
                        onSendSuspend = { viewModel.sendSuspend() },
                        onEnableDesktop = { viewModel.enableDesktop() },
                        onDisableDesktop = { viewModel.disableDesktop() },
                        onStartGamingMode = { viewModel.startGamingMode() },
                        onNavigateToFritzBoxSettings = onNavigateToFritzBoxSettings
                    )
```

- [ ] **Step 4: Extend the ServerStatusStrip signature**

Replace the signature (Zeilen 899-912) with:

```kotlin
@Composable
private fun ServerStatusStrip(
    nasStatus: NasStatus,
    uptimeSeconds: Long?,
    isAdmin: Boolean,
    powerPermissions: PowerPermissions,
    isActionInProgress: Boolean,
    wolAvailability: WolAvailability,
    desktopState: DesktopState,
    gamingModeAvailable: Boolean,
    onPowerDialogOpened: () -> Unit,
    onSendWol: () -> Unit,
    onSendWake: () -> Unit,
    onSendSoftSleep: () -> Unit,
    onSendSuspend: () -> Unit,
    onEnableDesktop: () -> Unit,
    onDisableDesktop: () -> Unit,
    onStartGamingMode: () -> Unit,
    onNavigateToFritzBoxSettings: () -> Unit
) {
```

- [ ] **Step 5: Refresh the state when the dialog opens**

Replace the `IconButton`'s `onClick` (Zeile 965) with:

```kotlin
                    onClick = {
                        showPowerDialog = true
                        onPowerDialogOpened()
                    },
```

- [ ] **Step 6: Add the two entries to the ONLINE branch**

Replace the whole `NasStatus.ONLINE -> { … }` branch (Zeilen 1033-1058) with:

```kotlin
                        NasStatus.ONLINE -> {
                            if (isAdmin || powerPermissions.canSoftSleep) {
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
                            }
                            if (isAdmin || powerPermissions.canSuspend) {
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
                            // One slot, two faces — mirrors the webapp, which shows
                            // exactly one of the two depending on the status. On
                            // UNKNOWN nothing is offered: guessing wrong would send
                            // the opposite of what the user wanted. No confirmation
                            // dialog either; both are harmless and instantly undone.
                            if (isAdmin || powerPermissions.canToggleDesktop) {
                                when (desktopState) {
                                    DesktopState.RUNNING -> PowerOptionButton(
                                        icon = Icons.Default.DesktopAccessDisabled,
                                        label = "Display deaktivieren",
                                        description = "Displays ausschalten, spart GPU-Strom",
                                        color = Sky400,
                                        onClick = {
                                            showPowerDialog = false
                                            onDisableDesktop()
                                        }
                                    )
                                    DesktopState.STOPPED -> PowerOptionButton(
                                        icon = Icons.Default.DesktopWindows,
                                        label = "Display aktivieren",
                                        description = "Displays wieder einschalten",
                                        color = Green500,
                                        onClick = {
                                            showPowerDialog = false
                                            onEnableDesktop()
                                        }
                                    )
                                    DesktopState.UNKNOWN -> {}
                                }
                            }
                            // Admin-only, because the plugin menu-action route is.
                            if (isAdmin && gamingModeAvailable) {
                                PowerOptionButton(
                                    icon = Icons.Default.SportsEsports,
                                    label = "Gaming-Modus",
                                    description = "Displays an + Big Picture",
                                    color = Violet500,
                                    onClick = {
                                        showPowerDialog = false
                                        onStartGamingMode()
                                    }
                                )
                            }
                        }
```

Die Zweige `NasStatus.SLEEPING` und `else` bleiben unverändert — im Soft-Sleep sind die Services pausiert, ein Display-Toggle wäre dort wirkungslos.

- [ ] **Step 7: Compile**

Run: `.\gradlew.bat assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Run the full test suite**

Run: `.\gradlew.bat testDebugUnitTest`

Expected: PASS.

- [ ] **Step 9: Manual check on a device or emulator**

Mit Admin-Account gegen einen laufenden BaluHost anmelden, das Dashboard öffnen und im Server-Status-Streifen auf das Power-Symbol tippen. Zu prüfen:

1. Bei laufendem Desktop erscheinen „Display deaktivieren" und „Gaming-Modus".
2. „Display deaktivieren" antippen → Snackbar „Displays deaktiviert", Bildschirme am Server gehen aus.
3. Dialog erneut öffnen → der Eintrag heißt jetzt „Display aktivieren".
4. „Display aktivieren" antippen → Snackbar „Displays aktiviert" bzw. „Displays an – Session ist noch gesperrt", je nachdem ob der Sperrbildschirm entsperrt wurde.
5. „Gaming-Modus" antippen → Steam Big Picture startet am Server, Snackbar „Gaming-Modus gestartet".
6. Bei schlafendem NAS (`NasStatus.SLEEPING`) erscheint keiner der beiden neuen Einträge.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt
git commit -m "feat(dashboard): show display toggle and gaming mode in the power dialog"
```

---

## Nach Abschluss

Alle fünf Aufgaben sind erledigt, wenn `.\gradlew.bat testDebugUnitTest` und `.\gradlew.bat assembleDebug` durchlaufen und die manuelle Prüfung aus Task 5 Schritt 9 bestanden ist. Der Branch ist dann bereit für `superpowers:finishing-a-development-branch`.
