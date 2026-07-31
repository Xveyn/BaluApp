# Always-Awake in der Android-App — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admins können den Always-Awake-Übersteuerungsschalter des Servers aus der App setzen — befristet über Presets, über einen frei gewählten Zeitpunkt, oder dauerhaft.

**Architecture:** Drei Tasks entlang der bestehenden Schichtung API → DTO → Repository → UseCase → ViewModel → Composable. Task 1 baut den Datenzugriff samt handgebautem JSON-Body, Task 2 die Präsentationslogik mit injizierter Uhr, Task 3 den Bildschirm und seine Einbindung in Navigation und Einstellungen. Am BaluHost-Server ist nichts zu ändern.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 1.3.0 via BOM 2024.09.03), Hilt, Retrofit + Gson + OkHttp, Coroutines/Flow, `java.time` (minSdk 26, kein Desugaring nötig). Tests: JUnit4, MockK, Turbine, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-31-always-awake-app-design.md`

## Global Constraints

- **Testkommando — Vorsicht mit dem Cache.** Dieses Repo setzt `org.gradle.caching=true`. Ein blosses `.\gradlew.bat testDebugUnitTest` meldet `BUILD SUCCESSFUL in 1s` mit `FROM-CACHE` oder `UP-TO-DATE`, **ohne einen einzigen Test auszuführen**. Verifiziert wird ausschliesslich mit:

  ```
  .\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --console=plain --no-build-cache
  ```

  Gradle druckt bei Erfolg **keine** Testzahl. Zählen über die XML-Ergebnisse:

  ```powershell
  $x = Get-ChildItem "app\build\test-results\testDebugUnitTest\*.xml" | ForEach-Object { [xml]$c = Get-Content $_.FullName; $c.testsuite }
  "tests=$(($x | Measure-Object -Property tests -Sum).Sum) failures=$(($x | Measure-Object -Property failures -Sum).Sum) errors=$(($x | Measure-Object -Property errors -Sum).Sum)"
  ```

- **Ausgangsbasis: 146 Tests, 0 Fehler, 0 Errors.** Die Suite ist vollständig grün — jeder Fehlschlag gehört dir.
- **Kein `@Ignore`, keine gelöschten oder stillgelegten Tests.** Ein Test, der nicht grün zu bekommen ist, ist ein BLOCKED-Report.
- **Am Server wird nichts geändert.** Alle Routen und Felder existieren bereits.
- Nutzertexte sind deutsche Literale im Code; Code und Kommentare sind Englisch, wie im Umfeld.
- Repository-Rückgaben sind `com.baluhost.android.util.Result<T>`; ein `when` darüber braucht einen `else -> {}`-Zweig.
- DTO-Felder tragen `@SerializedName("snake_case")` und haben Defaultwerte.
- **`app/src/main/java/com/baluhost/android/data/worker/FolderSyncWorker.kt` hat uncommittete Änderungen aus fremder Arbeit** — niemals anfassen, niemals mitcommitten. `git add` immer dateigenau, nie `git add -A`, nie `git commit -a`.
- Das Feature ist **Admin-only**. Der Server erzwingt das über `get_current_admin`; die App zeigt den Einstiegspunkt nur bei `isAdmin`.

---

### Task 1: Datenzugriff mit handgebautem JSON-Body

Der Kern dieser Task ist eine Eigenheit des Servers: `update_config` arbeitet auf `model_dump(exclude_unset=True)` und behandelt `always_awake_until` gesondert —

```python
if "always_awake_until" in update_data:
    config.always_awake_until = update_data.pop("always_awake_until")
```

Der Schlüssel muss also **im JSON vorhanden und `null`** sein, damit „permanent" ankommt. Fehlt er, bleibt die alte Ablaufzeit stehen, ohne Fehlermeldung. Gson lässt Null-Felder bei reflektiver Serialisierung weg, und auch ein `JsonNull` in einem `JsonObject` wird von dem JsonWriter übersprungen, den `GsonConverterFactory` erzeugt. Deshalb wird der Body als String gebaut: `JsonElement.toString()` schreibt Nulls mit.

**Files:**
- Create: `app/src/main/java/com/baluhost/android/data/remote/dto/SleepConfigDto.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/model/AlwaysAwake.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/repository/SleepConfigRepository.kt`
- Create: `app/src/main/java/com/baluhost/android/data/repository/SleepConfigRepositoryImpl.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/remote/api/SleepApi.kt`
- Modify: `app/src/main/java/com/baluhost/android/di/RepositoryModule.kt`
- Test: `app/src/test/java/com/baluhost/android/data/repository/SleepConfigRepositoryTest.kt`

**Interfaces:**
- Consumes: nichts
- Produces:
  - `data class AlwaysAwake(val enabled: Boolean, val until: Instant?)` in `com.baluhost.android.domain.model`
  - `interface SleepConfigRepository` mit `suspend fun getAlwaysAwake(): Result<AlwaysAwake>` und `suspend fun setAlwaysAwake(enabled: Boolean, until: Instant?): Result<AlwaysAwake>`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/baluhost/android/data/repository/SleepConfigRepositoryTest.kt`:

```kotlin
package com.baluhost.android.data.repository

import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.data.remote.dto.SleepConfigDto
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.Instant

/**
 * The tests that matter here inspect the JSON actually put on the wire. The
 * server distinguishes "always_awake_until sent as null" from "not sent at all",
 * and Gson drops nulls by default — so a repository that looks correct against a
 * mocked API can still fail against the real one, silently leaving the old
 * expiry in place.
 */
class SleepConfigRepositoryTest {

    private lateinit var sleepApi: SleepApi
    private lateinit var repository: SleepConfigRepositoryImpl

    @Before
    fun setup() {
        sleepApi = mockk()
        repository = SleepConfigRepositoryImpl(sleepApi)
    }

    /** Reads back what Retrofit would have transmitted. */
    private fun RequestBody.asString(): String {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaTypeOrNull()))
    )

    private fun captureBody(): CapturingSlot<RequestBody> {
        val slot = slot<RequestBody>()
        coEvery { sleepApi.updateSleepConfig(capture(slot)) } returns SleepConfigDto()
        return slot
    }

    // ---- the JSON on the wire ----------------------------------------------

    @Test
    fun `permanent sends an explicit null expiry`() = runTest {
        val slot = captureBody()

        repository.setAlwaysAwake(enabled = true, until = null)

        val json = slot.captured.asString()
        assertTrue("expected an explicit null, got: $json", json.contains("\"always_awake_until\":null"))
        assertTrue(json.contains("\"always_awake_enabled\":true"))
    }

    @Test
    fun `a timed override sends an ISO-8601 UTC expiry`() = runTest {
        val slot = captureBody()

        repository.setAlwaysAwake(enabled = true, until = Instant.parse("2026-08-01T21:30:00Z"))

        val json = slot.captured.asString()
        assertTrue(json, json.contains("\"always_awake_until\":\"2026-08-01T21:30:00Z\""))
    }

    @Test
    fun `switching off sends no expiry at all`() = runTest {
        // The server clears the expiry itself when always_awake_enabled is false,
        // so sending one would be redundant — and sending a stale one harmful.
        val slot = captureBody()

        repository.setAlwaysAwake(enabled = false, until = Instant.parse("2026-08-01T21:30:00Z"))

        val json = slot.captured.asString()
        assertTrue(json, json.contains("\"always_awake_enabled\":false"))
        assertTrue("expiry must not be sent when disabling, got: $json", !json.contains("always_awake_until"))
    }

    // ---- reading the response ----------------------------------------------

    @Test
    fun `an expiry with a zone offset is read as that instant`() = runTest {
        coEvery { sleepApi.getSleepConfig() } returns
            SleepConfigDto(alwaysAwakeEnabled = true, alwaysAwakeUntil = "2026-08-01T21:30:00Z")

        val result = repository.getAlwaysAwake()

        assertEquals(Instant.parse("2026-08-01T21:30:00Z"), (result as Result.Success).data.until)
    }

    @Test
    fun `an expiry without a zone is read as UTC`() = runTest {
        // The server's own validator normalises naive timestamps to UTC, so
        // responses are not guaranteed to carry an offset.
        coEvery { sleepApi.getSleepConfig() } returns
            SleepConfigDto(alwaysAwakeEnabled = true, alwaysAwakeUntil = "2026-08-01T21:30:00")

        val result = repository.getAlwaysAwake()

        assertEquals(Instant.parse("2026-08-01T21:30:00Z"), (result as Result.Success).data.until)
    }

    @Test
    fun `a null expiry means permanent`() = runTest {
        coEvery { sleepApi.getSleepConfig() } returns
            SleepConfigDto(alwaysAwakeEnabled = true, alwaysAwakeUntil = null)

        val result = repository.getAlwaysAwake()

        assertTrue((result as Result.Success).data.enabled)
        assertNull(result.data.until)
    }

    @Test
    fun `an unparseable expiry is reported rather than swallowed`() = runTest {
        coEvery { sleepApi.getSleepConfig() } returns
            SleepConfigDto(alwaysAwakeEnabled = true, alwaysAwakeUntil = "irgendwann")

        val result = repository.getAlwaysAwake()

        assertEquals("Unerwartetes Zeitformat vom Server", (result as Result.Error).exception.message)
    }

    // ---- failures ----------------------------------------------------------

    @Test
    fun `a 403 is reported as a permission problem`() = runTest {
        coEvery { sleepApi.getSleepConfig() } throws httpError(403)

        val result = repository.getAlwaysAwake()

        assertEquals("Nur Admins dürfen das ändern", (result as Result.Error).exception.message)
    }

    @Test
    fun `a dead connection is reported as unreachable`() = runTest {
        coEvery { sleepApi.getSleepConfig() } throws IOException("no route to host")

        val result = repository.getAlwaysAwake()

        assertEquals("Server nicht erreichbar", (result as Result.Error).exception.message)
    }
}
```

Ergänze den Import `io.mockk.CapturingSlot` für die `captureBody()`-Hilfsfunktion.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --no-build-cache --tests "com.baluhost.android.data.repository.SleepConfigRepositoryTest"`

Expected: FAIL beim Kompilieren mit `Unresolved reference: SleepConfigDto`.

- [ ] **Step 3: Create the DTO**

Create `app/src/main/java/com/baluhost/android/data/remote/dto/SleepConfigDto.kt`:

```kotlin
package com.baluhost.android.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * The two always-awake fields of the server's sleep config. The real response
 * carries dozens more — idle thresholds, schedules, core uptime, presence — and
 * this app has no business with any of them. Gson drops what is not declared.
 */
data class SleepConfigDto(
    @SerializedName("always_awake_enabled")
    val alwaysAwakeEnabled: Boolean = false,
    /** ISO-8601. Null means the override is permanent. May arrive without a zone offset. */
    @SerializedName("always_awake_until")
    val alwaysAwakeUntil: String? = null
)
```

- [ ] **Step 4: Create the domain model**

Create `app/src/main/java/com/baluhost/android/domain/model/AlwaysAwake.kt`:

```kotlin
package com.baluhost.android.domain.model

import java.time.Instant

/**
 * The always-awake override, which overrules every automatic sleep path.
 *
 * [until] is null while the override is permanent — the same encoding the server
 * uses, where a missing expiry means "no expiry" rather than "not set".
 */
data class AlwaysAwake(
    val enabled: Boolean,
    val until: Instant?
)
```

- [ ] **Step 5: Add the API methods**

In `app/src/main/java/com/baluhost/android/data/remote/api/SleepApi.kt` die Imports ergänzen:

```kotlin
import com.baluhost.android.data.remote.dto.SleepConfigDto
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.PUT
```

und innerhalb des Interface anfügen:

```kotlin
    @GET("system/sleep/config")
    suspend fun getSleepConfig(): SleepConfigDto

    /**
     * Takes a pre-serialised body on purpose. "Permanent" requires
     * always_awake_until to be present AND null in the JSON, which Gson's
     * reflective serialisation would drop.
     */
    @PUT("system/sleep/config")
    suspend fun updateSleepConfig(@Body body: RequestBody): SleepConfigDto
```

- [ ] **Step 6: Create the repository interface**

Create `app/src/main/java/com/baluhost/android/domain/repository/SleepConfigRepository.kt`:

```kotlin
package com.baluhost.android.domain.repository

import com.baluhost.android.domain.model.AlwaysAwake
import com.baluhost.android.util.Result
import java.time.Instant

interface SleepConfigRepository {

    suspend fun getAlwaysAwake(): Result<AlwaysAwake>

    /**
     * @param until null means permanent. Ignored when [enabled] is false — the
     *   server clears the expiry itself in that case.
     */
    suspend fun setAlwaysAwake(enabled: Boolean, until: Instant?): Result<AlwaysAwake>
}
```

- [ ] **Step 7: Implement the repository**

Create `app/src/main/java/com/baluhost/android/data/repository/SleepConfigRepositoryImpl.kt`:

```kotlin
package com.baluhost.android.data.repository

import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.data.remote.dto.SleepConfigDto
import com.baluhost.android.domain.model.AlwaysAwake
import com.baluhost.android.domain.repository.SleepConfigRepository
import com.baluhost.android.util.Result
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import javax.inject.Inject

class SleepConfigRepositoryImpl @Inject constructor(
    private val sleepApi: SleepApi
) : SleepConfigRepository {

    override suspend fun getAlwaysAwake(): Result<AlwaysAwake> = request {
        sleepApi.getSleepConfig()
    }

    override suspend fun setAlwaysAwake(enabled: Boolean, until: Instant?): Result<AlwaysAwake> =
        request { sleepApi.updateSleepConfig(buildBody(enabled, until)) }

    private suspend fun request(call: suspend () -> SleepConfigDto): Result<AlwaysAwake> {
        return try {
            Result.Success(call().toAlwaysAwake())
        } catch (e: HttpException) {
            val message =
                if (e.code() == 403) "Nur Admins dürfen das ändern"
                else "Always-Awake fehlgeschlagen: ${e.message()}"
            Result.Error(Exception(message, e))
        } catch (e: DateTimeParseException) {
            // A timestamp we cannot read is a broken contract, not a permanent
            // override. Saying so beats rendering "dauerhaft aktiv" at a user
            // whose override actually expires tonight.
            Result.Error(Exception("Unerwartetes Zeitformat vom Server", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }

    /**
     * Builds the request body by hand.
     *
     * The server applies partial updates from model_dump(exclude_unset=True) and
     * special-cases this one field: an explicit null clears the expiry and means
     * "permanent", while an omitted key leaves the previous expiry untouched.
     * Gson's reflective serialisation drops null fields, and GsonConverterFactory's
     * writer skips a JsonNull property too — JsonElement.toString() does not.
     */
    private fun buildBody(enabled: Boolean, until: Instant?): RequestBody {
        val json = JsonObject().apply {
            addProperty("always_awake_enabled", enabled)
            if (enabled) {
                if (until == null) add("always_awake_until", JsonNull.INSTANCE)
                // Instant.toString() is ISO-8601 with a trailing Z, which is what
                // the webapp sends and what the server's validator expects.
                else addProperty("always_awake_until", until.toString())
            }
            // Disabling needs no expiry: the server clears it on its own.
        }
        return json.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private fun SleepConfigDto.toAlwaysAwake() = AlwaysAwake(
        enabled = alwaysAwakeEnabled,
        until = alwaysAwakeUntil?.let(::parseServerInstant)
    )

    /**
     * The server normalises naive timestamps to UTC itself, so a response is not
     * guaranteed to carry an offset. Both shapes are accepted; anything else
     * throws and surfaces as an error rather than being guessed at.
     */
    private fun parseServerInstant(raw: String): Instant =
        try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: DateTimeParseException) {
            LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
```

- [ ] **Step 8: Bind the repository**

In `app/src/main/java/com/baluhost/android/di/RepositoryModule.kt` die Imports `com.baluhost.android.data.repository.SleepConfigRepositoryImpl` und `com.baluhost.android.domain.repository.SleepConfigRepository` ergänzen und innerhalb der abstrakten Klasse anfügen:

```kotlin
    @Binds
    @Singleton
    abstract fun bindSleepConfigRepository(
        sleepConfigRepositoryImpl: SleepConfigRepositoryImpl
    ): SleepConfigRepository
```

- [ ] **Step 9: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --no-build-cache --tests "com.baluhost.android.data.repository.SleepConfigRepositoryTest"`

Expected: BUILD SUCCESSFUL, 9 Tests grün.

- [ ] **Step 10: Full suite and build**

Run: `.\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --console=plain --no-build-cache` und danach die XML-Zählung aus den Global Constraints.

Expected: `tests=155 failures=0 errors=0` (146 + deine 9).

Run: `.\gradlew.bat assembleDebug --console=plain`

Expected: BUILD SUCCESSFUL. Der Hilt-Graph hat sich geändert; ein Fehler hier bedeutet ein fehlendes oder falsch platziertes Binding aus Step 8.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/remote/dto/SleepConfigDto.kt app/src/main/java/com/baluhost/android/domain/model/AlwaysAwake.kt app/src/main/java/com/baluhost/android/domain/repository/SleepConfigRepository.kt app/src/main/java/com/baluhost/android/data/repository/SleepConfigRepositoryImpl.kt app/src/main/java/com/baluhost/android/data/remote/api/SleepApi.kt app/src/main/java/com/baluhost/android/di/RepositoryModule.kt app/src/test/java/com/baluhost/android/data/repository/SleepConfigRepositoryTest.kt
git commit -m "feat(sleep): read and write the always-awake override"
```

---

### Task 2: Uhr, Use Cases und ViewModel

**Files:**
- Create: `app/src/main/java/com/baluhost/android/util/Clock.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/power/GetAlwaysAwakeUseCase.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/power/SetAlwaysAwakeUseCase.kt`
- Create: `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/AlwaysAwakeViewModel.kt`
- Modify: `app/src/main/java/com/baluhost/android/di/AppModule.kt`
- Test: `app/src/test/java/com/baluhost/android/presentation/ui/screens/settings/AlwaysAwakeViewModelTest.kt`

**Interfaces:**
- Consumes: `SleepConfigRepository` mit `getAlwaysAwake()` und `setAlwaysAwake(enabled, until)`; `AlwaysAwake(enabled, until)` — alles aus Task 1
- Produces:
  - `fun interface Clock { fun now(): Instant }` in `com.baluhost.android.util`
  - `AlwaysAwakeViewModel` mit `uiState: StateFlow<AlwaysAwakeUiState>`, `snackbarEvent: SharedFlow<String>` und den Aktionen `load()`, `setPreset(hours: Long?)`, `setCustom(until: Instant)`, `disable()`
  - `data class AlwaysAwakeUiState(isLoading, enabled, until, isSaving, loadError)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/baluhost/android/presentation/ui/screens/settings/AlwaysAwakeViewModelTest.kt`:

```kotlin
package com.baluhost.android.presentation.ui.screens.settings

import app.cash.turbine.test
import com.baluhost.android.domain.model.AlwaysAwake
import com.baluhost.android.domain.usecase.power.GetAlwaysAwakeUseCase
import com.baluhost.android.domain.usecase.power.SetAlwaysAwakeUseCase
import com.baluhost.android.util.Clock
import com.baluhost.android.util.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AlwaysAwakeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    /** Fixed so the boundary cases are exact rather than racing the wall clock. */
    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")

    private lateinit var getAlwaysAwake: GetAlwaysAwakeUseCase
    private lateinit var setAlwaysAwake: SetAlwaysAwakeUseCase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        getAlwaysAwake = mockk()
        setAlwaysAwake = mockk()
        coEvery { getAlwaysAwake() } returns Result.Success(AlwaysAwake(enabled = false, until = null))
        coEvery { setAlwaysAwake(any(), any()) } returns
            Result.Success(AlwaysAwake(enabled = true, until = null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AlwaysAwakeViewModel(getAlwaysAwake, setAlwaysAwake, Clock { now })

    // ---- loading -----------------------------------------------------------

    @Test
    fun `loads the current override on creation`() = runTest {
        coEvery { getAlwaysAwake() } returns
            Result.Success(AlwaysAwake(enabled = true, until = now.plus(Duration.ofHours(2))))

        val state = viewModel().uiState.value

        assertTrue(state.enabled)
        assertEquals(now.plus(Duration.ofHours(2)), state.until)
        assertTrue(!state.isLoading)
    }

    @Test
    fun `a failed load is shown rather than defaulted away`() = runTest {
        coEvery { getAlwaysAwake() } returns Result.Error(Exception("Server nicht erreichbar"))

        val state = viewModel().uiState.value

        assertEquals("Server nicht erreichbar", state.loadError)
    }

    // ---- presets -----------------------------------------------------------

    @Test
    fun `a one hour preset expires one hour from now`() = runTest {
        val vm = viewModel()

        vm.setPreset(1L)

        coVerify { setAlwaysAwake(true, now.plus(Duration.ofHours(1))) }
    }

    @Test
    fun `an eight hour preset expires eight hours from now`() = runTest {
        val vm = viewModel()

        vm.setPreset(8L)

        coVerify { setAlwaysAwake(true, now.plus(Duration.ofHours(8))) }
    }

    @Test
    fun `the permanent preset sends no expiry`() = runTest {
        val vm = viewModel()

        vm.setPreset(null)

        coVerify { setAlwaysAwake(true, null) }
    }

    @Test
    fun `disabling sends enabled false`() = runTest {
        coEvery { setAlwaysAwake(false, null) } returns
            Result.Success(AlwaysAwake(enabled = false, until = null))
        val vm = viewModel()

        vm.disable()

        coVerify { setAlwaysAwake(false, null) }
    }

    // ---- bounds on a custom instant ----------------------------------------

    @Test
    fun `a custom instant in the past is refused without a request`() = runTest {
        val vm = viewModel()

        vm.snackbarEvent.test {
            vm.setCustom(now.minusSeconds(1))

            assertEquals("Der Zeitpunkt muss in der Zukunft liegen", awaitItem())
        }
        coVerify(exactly = 0) { setAlwaysAwake(any(), any()) }
    }

    @Test
    fun `a custom instant under five minutes away is refused`() = runTest {
        val vm = viewModel()

        vm.snackbarEvent.test {
            vm.setCustom(now.plus(Duration.ofMinutes(4)))

            assertEquals("Mindestens 5 Minuten in der Zukunft", awaitItem())
        }
        coVerify(exactly = 0) { setAlwaysAwake(any(), any()) }
    }

    @Test
    fun `exactly five minutes away is accepted`() = runTest {
        val vm = viewModel()
        val target = now.plus(Duration.ofMinutes(5))

        vm.setCustom(target)

        coVerify { setAlwaysAwake(true, target) }
    }

    @Test
    fun `a custom instant beyond seven days is refused`() = runTest {
        val vm = viewModel()

        vm.snackbarEvent.test {
            vm.setCustom(now.plus(Duration.ofDays(7)).plusSeconds(1))

            assertEquals("Höchstens 7 Tage im Voraus", awaitItem())
        }
        coVerify(exactly = 0) { setAlwaysAwake(any(), any()) }
    }

    @Test
    fun `exactly seven days away is accepted`() = runTest {
        val vm = viewModel()
        val target = now.plus(Duration.ofDays(7))

        vm.setCustom(target)

        coVerify { setAlwaysAwake(true, target) }
    }

    // ---- failure handling --------------------------------------------------

    @Test
    fun `a failed save restores the previous state`() = runTest {
        coEvery { getAlwaysAwake() } returns
            Result.Success(AlwaysAwake(enabled = true, until = null))
        coEvery { setAlwaysAwake(any(), any()) } returns
            Result.Error(Exception("Server nicht erreichbar"))
        val vm = viewModel()
        assertTrue(vm.uiState.value.enabled)

        vm.snackbarEvent.test {
            vm.disable()

            assertEquals("Server nicht erreichbar", awaitItem())
        }
        // The override is still on, because the server never accepted the change.
        assertTrue(vm.uiState.value.enabled)
        assertNull(vm.uiState.value.until)
        assertTrue(!vm.uiState.value.isSaving)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --no-build-cache --tests "com.baluhost.android.presentation.ui.screens.settings.AlwaysAwakeViewModelTest"`

Expected: FAIL beim Kompilieren mit `Unresolved reference: Clock`.

- [ ] **Step 3: Create the clock**

Create `app/src/main/java/com/baluhost/android/util/Clock.kt`:

```kotlin
package com.baluhost.android.util

import java.time.Instant

/**
 * Injectable time source.
 *
 * Preset expiries and the five-minute / seven-day bounds are all computed
 * against "now". With Instant.now() wired in directly, the boundary cases could
 * only be tested approximately, and would race the wall clock.
 */
fun interface Clock {
    fun now(): Instant
}
```

- [ ] **Step 4: Provide the clock**

In `app/src/main/java/com/baluhost/android/di/AppModule.kt` den Import `com.baluhost.android.util.Clock` und `java.time.Instant` ergänzen und innerhalb des `object AppModule` anfügen:

```kotlin
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock { Instant.now() }
```

Das folgt dem bestehenden Muster von `provideNetworkMonitor` in derselben Datei.

- [ ] **Step 5: Create the use cases**

Create `app/src/main/java/com/baluhost/android/domain/usecase/power/GetAlwaysAwakeUseCase.kt`:

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.model.AlwaysAwake
import com.baluhost.android.domain.repository.SleepConfigRepository
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetAlwaysAwakeUseCase @Inject constructor(
    private val sleepConfigRepository: SleepConfigRepository
) {
    suspend operator fun invoke(): Result<AlwaysAwake> {
        return sleepConfigRepository.getAlwaysAwake()
    }
}
```

Create `app/src/main/java/com/baluhost/android/domain/usecase/power/SetAlwaysAwakeUseCase.kt`:

```kotlin
package com.baluhost.android.domain.usecase.power

import com.baluhost.android.domain.model.AlwaysAwake
import com.baluhost.android.domain.repository.SleepConfigRepository
import com.baluhost.android.util.Result
import java.time.Instant
import javax.inject.Inject

class SetAlwaysAwakeUseCase @Inject constructor(
    private val sleepConfigRepository: SleepConfigRepository
) {
    /** @param until null means permanent; ignored when [enabled] is false. */
    suspend operator fun invoke(enabled: Boolean, until: Instant?): Result<AlwaysAwake> {
        return sleepConfigRepository.setAlwaysAwake(enabled, until)
    }
}
```

- [ ] **Step 6: Create the ViewModel**

Create `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/AlwaysAwakeViewModel.kt`:

```kotlin
package com.baluhost.android.presentation.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baluhost.android.domain.usecase.power.GetAlwaysAwakeUseCase
import com.baluhost.android.domain.usecase.power.SetAlwaysAwakeUseCase
import com.baluhost.android.util.Clock
import com.baluhost.android.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class AlwaysAwakeUiState(
    val isLoading: Boolean = true,
    val enabled: Boolean = false,
    /** Null while the override is permanent — or while it is off entirely. */
    val until: Instant? = null,
    val isSaving: Boolean = false,
    val loadError: String? = null
)

@HiltViewModel
class AlwaysAwakeViewModel @Inject constructor(
    private val getAlwaysAwakeUseCase: GetAlwaysAwakeUseCase,
    private val setAlwaysAwakeUseCase: SetAlwaysAwakeUseCase,
    private val clock: Clock
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlwaysAwakeUiState())
    val uiState: StateFlow<AlwaysAwakeUiState> = _uiState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)
            when (val result = getAlwaysAwakeUseCase()) {
                is Result.Success -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    enabled = result.data.enabled,
                    until = result.data.until,
                    loadError = null
                )
                is Result.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadError = result.exception.message ?: "Laden fehlgeschlagen"
                )
                else -> {}
            }
        }
    }

    /** @param hours null selects the permanent override. */
    fun setPreset(hours: Long?) {
        val until = hours?.let { clock.now().plus(Duration.ofHours(it)) }
        save(enabled = true, until = until)
    }

    fun setCustom(until: Instant) {
        val now = clock.now()
        val message = when {
            !until.isAfter(now) -> "Der Zeitpunkt muss in der Zukunft liegen"
            until.isBefore(now.plus(MIN_HORIZON)) -> "Mindestens 5 Minuten in der Zukunft"
            until.isAfter(now.plus(MAX_HORIZON)) -> "Höchstens 7 Tage im Voraus"
            else -> null
        }
        if (message != null) {
            // Caught here so the user gets a sentence instead of the server's 422.
            // The server enforces the future and the seven days itself; the five
            // minutes are ours, so an expiry is not already lapsing as it is set.
            viewModelScope.launch { _snackbarEvent.emit(message) }
            return
        }
        save(enabled = true, until = until)
    }

    fun disable() = save(enabled = false, until = null)

    private fun save(enabled: Boolean, until: Instant?) {
        val previous = _uiState.value
        viewModelScope.launch {
            _uiState.value = previous.copy(isSaving = true)
            when (val result = setAlwaysAwakeUseCase(enabled, until)) {
                is Result.Success -> _uiState.value = previous.copy(
                    isSaving = false,
                    enabled = result.data.enabled,
                    until = result.data.until
                )
                is Result.Error -> {
                    // Put the old state back: the screen must not claim a change
                    // the server never accepted.
                    _uiState.value = previous.copy(isSaving = false)
                    _snackbarEvent.emit(result.exception.message ?: "Speichern fehlgeschlagen")
                }
                else -> {}
            }
        }
    }

    private companion object {
        val MIN_HORIZON: Duration = Duration.ofMinutes(5)
        val MAX_HORIZON: Duration = Duration.ofDays(7)
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --no-build-cache --tests "com.baluhost.android.presentation.ui.screens.settings.AlwaysAwakeViewModelTest"`

Expected: BUILD SUCCESSFUL, 12 Tests grün.

- [ ] **Step 8: Full suite and build**

Run: `.\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --console=plain --no-build-cache` plus XML-Zählung.

Expected: `tests=167 failures=0 errors=0` (155 + deine 12).

Run: `.\gradlew.bat assembleDebug --console=plain`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/baluhost/android/util/Clock.kt app/src/main/java/com/baluhost/android/di/AppModule.kt app/src/main/java/com/baluhost/android/domain/usecase/power/GetAlwaysAwakeUseCase.kt app/src/main/java/com/baluhost/android/domain/usecase/power/SetAlwaysAwakeUseCase.kt app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/AlwaysAwakeViewModel.kt app/src/test/java/com/baluhost/android/presentation/ui/screens/settings/AlwaysAwakeViewModelTest.kt
git commit -m "feat(sleep): add the always-awake view model with an injectable clock"
```

---

### Task 3: Bildschirm, Navigation und Einstiegspunkt

Dieses Projekt hat **kein `androidTest`-Sourceset**, es gibt also keine Compose-UI-Tests. Verifiziert wird über `assembleDebug`, die unveränderte Unit-Suite und eine manuelle Prüfung am Gerät.

**Files:**
- Create: `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/AlwaysAwakeScreen.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/navigation/Screen.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/main/MainScreen.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `AlwaysAwakeViewModel` mit `uiState`, `snackbarEvent`, `load()`, `setPreset(hours: Long?)`, `setCustom(until: Instant)`, `disable()` — aus Task 2
- Produces: nichts, was andere Tasks nutzen

- [ ] **Step 1: Add the route**

In `app/src/main/java/com/baluhost/android/presentation/navigation/Screen.kt` nach `object FritzBoxSettings : Screen("fritzbox_settings")` einfügen:

```kotlin
    object AlwaysAwake : Screen("always_awake")
```

- [ ] **Step 2: Create the screen**

Create `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/AlwaysAwakeScreen.kt`:

```kotlin
package com.baluhost.android.presentation.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baluhost.android.presentation.ui.theme.*
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val PRESETS: List<Pair<String, Long?>> = listOf(
    "1 Std" to 1L,
    "4 Std" to 4L,
    "8 Std" to 8L,
    "Dauerhaft" to null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlwaysAwakeScreen(
    onNavigateBack: () -> Unit,
    viewModel: AlwaysAwakeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Always-Awake", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück", tint = Slate400)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Slate950
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                uiState.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = Sky400) }

                uiState.loadError != null -> LoadErrorBlock(
                    message = uiState.loadError!!,
                    onRetry = { viewModel.load() }
                )

                else -> {
                    MasterSwitch(
                        enabled = uiState.enabled,
                        isSaving = uiState.isSaving,
                        onToggle = { on -> if (on) viewModel.setPreset(null) else viewModel.disable() }
                    )

                    StatusLine(enabled = uiState.enabled, until = uiState.until)

                    Text(
                        text = "Dauer",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                        fontWeight = FontWeight.Medium
                    )
                    PresetRow(
                        isSaving = uiState.isSaving,
                        onPreset = { hours -> viewModel.setPreset(hours) }
                    )

                    TextButton(
                        onClick = { showDatePicker = true },
                        enabled = !uiState.isSaving
                    ) { Text("Zeitpunkt wählen…", color = Sky400) }

                    Text(
                        text = "Always-Awake hat Vorrang: solange es aktiv ist, greift keine " +
                            "automatische Schlafautomatik und kein Zeitplan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                }
            }
        }
    }

    // Date first, then time — Material3 has no combined picker.
    if (showDatePicker) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("Weiter") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Abbrechen") }
            }
        ) { DatePicker(state = state) }
    }

    pickedDate?.let { date ->
        val timeState = rememberTimePickerState(is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            confirmButton = {
                TextButton(onClick = {
                    val local = LocalDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute))
                    // The picker speaks the user's local time; the server wants UTC.
                    viewModel.setCustom(local.atZone(ZoneId.systemDefault()).toInstant())
                    pickedDate = null
                }) { Text("Übernehmen") }
            },
            dismissButton = {
                TextButton(onClick = { pickedDate = null }) { Text("Abbrechen") }
            },
            title = { Text("Uhrzeit", color = Color.White) },
            text = { TimePicker(state = timeState) },
            containerColor = Slate900
        )
    }
}

@Composable
private fun MasterSwitch(enabled: Boolean, isSaving: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Always-Awake", style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(
                "Server wach halten",
                style = MaterialTheme.typography.bodySmall,
                color = Slate400
            )
        }
        if (isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Sky400, strokeWidth = 2.dp)
        } else {
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun PresetRow(isSaving: Boolean, onPreset: (Long?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PRESETS.forEach { (label, hours) ->
            OutlinedButton(onClick = { onPreset(hours) }, enabled = !isSaving) {
                Text(label)
            }
        }
    }
}

@Composable
private fun LoadErrorBlock(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Orange500)
        TextButton(onClick = onRetry) { Text("Erneut versuchen", color = Sky400) }
    }
}

/** Recomputes once a second so the remaining time stays honest while the screen is open. */
@Composable
private fun StatusLine(enabled: Boolean, until: Instant?) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(enabled, until) {
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }

    val text = when {
        !enabled -> "Aus — die Schlafautomatik greift normal."
        until == null -> "Dauerhaft aktiv"
        !until.isAfter(now) -> "Abgelaufen"
        else -> "Noch ${formatRemaining(Duration.between(now, until))} — bis ${formatUntil(until)}"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Green500 else Slate400)
}

private fun formatRemaining(d: Duration): String {
    val days = d.toDays()
    val hours = d.toHours() % 24
    val minutes = d.toMinutes() % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

private fun formatUntil(until: Instant): String {
    val local = until.atZone(ZoneId.systemDefault())
    val today = LocalDate.now(ZoneId.systemDefault())
    return if (local.toLocalDate() == today) {
        local.format(DateTimeFormatter.ofPattern("HH:mm"))
    } else {
        local.format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
    }
}
```

- [ ] **Step 3: Wire the route into the navigation graph**

In `app/src/main/java/com/baluhost/android/presentation/navigation/NavGraph.kt` den Import ergänzen:

```kotlin
import com.baluhost.android.presentation.ui.screens.settings.AlwaysAwakeScreen
```

und direkt nach dem `composable(Screen.FritzBoxSettings.route) { … }`-Block einfügen:

```kotlin
        composable(Screen.AlwaysAwake.route) {
            AlwaysAwakeScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
```

- [ ] **Step 4: Pass the navigation callback through MainScreen**

In `app/src/main/java/com/baluhost/android/presentation/ui/screens/main/MainScreen.kt`, im `SettingsScreen(...)`-Aufruf hinter `onNavigateToFritzBox = { … }` ergänzen (Komma nach dem vorherigen Argument nicht vergessen):

```kotlin
                        onNavigateToAlwaysAwake = {
                            parentNavController.navigate(Screen.AlwaysAwake.route)
                        }
```

- [ ] **Step 5: Add the entry point in the settings screen**

In `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsScreen.kt` die Signatur um einen Parameter erweitern — hinter `onNavigateToFritzBox: () -> Unit = {},` einfügen:

```kotlin
    onNavigateToAlwaysAwake: () -> Unit = {},
```

Dann direkt **vor** der Zeile `// ── Section: App-Einstellungen ──` den neuen Abschnitt einfügen:

```kotlin
                // ── Section: Server ──
                // Admin-only: the server's sleep-config routes are guarded by
                // get_current_admin, with no delegatable permission.
                if (isAdmin) {
                    SectionHeader(text = "Server")

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        intensity = GlassIntensity.Medium,
                        onClick = onNavigateToAlwaysAwake
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ALWAYS-AWAKE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Slate500,
                                    letterSpacing = 2.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Server wach halten, befristet oder dauerhaft",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Slate400
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Öffnen",
                                tint = Sky400
                            )
                        }
                    }
                }

```

Der Block spiegelt bewusst die Fritz!Box-Karte weiter oben in derselben Datei — gleiche `GlassCard`, gleiche Typografie, gleicher Chevron.

- [ ] **Step 6: Compile**

Run: `.\gradlew.bat assembleDebug --console=plain`

Expected: BUILD SUCCESSFUL. Fehler hier sind fast immer ein fehlendes Komma im `SettingsScreen(...)`-Aufruf aus Step 4 oder ein fehlender Import in Step 3.

- [ ] **Step 7: Full suite**

Run: `.\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --console=plain --no-build-cache` plus XML-Zählung.

Expected: `tests=167 failures=0 errors=0` — unverändert, diese Task fügt keine Tests hinzu.

- [ ] **Step 8: Manual check on a device**

Diesen Schritt kann kein Agent ausführen; er gehört dem Menschen. Im Report als offen vermerken und Folgendes zur Prüfung auflisten:

1. Als **Admin**: Einstellungen → Abschnitt „Server" → „Always-Awake" ist vorhanden und öffnet den Bildschirm.
2. Preset „4 Std" antippen → Statuszeile zeigt „Noch 3h 59m — bis HH:MM", der Schalter steht auf an.
3. Bildschirm verlassen und erneut öffnen → derselbe Zustand wird vom Server geladen, nicht aus dem Speicher geraten.
4. „Dauerhaft" antippen → Statuszeile zeigt „Dauerhaft aktiv". **Das ist der Test für die Null-Eigenheit**: bliebe stattdessen eine Uhrzeit stehen, ginge der explizite `null`-Wert nicht über die Leitung.
5. Schalter ausschalten → „Aus — die Schlafautomatik greift normal."
6. „Zeitpunkt wählen…" → Datum und Uhrzeit in 2 Minuten wählen → Meldung „Mindestens 5 Minuten in der Zukunft", nichts wird gespeichert.
7. Zeitpunkt in 10 Tagen wählen → „Höchstens 7 Tage im Voraus".
8. Als **Nicht-Admin** anmelden → der Abschnitt „Server" fehlt vollständig.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/AlwaysAwakeScreen.kt app/src/main/java/com/baluhost/android/presentation/navigation/Screen.kt app/src/main/java/com/baluhost/android/presentation/navigation/NavGraph.kt app/src/main/java/com/baluhost/android/presentation/ui/screens/main/MainScreen.kt app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsScreen.kt
git commit -m "feat(sleep): add the always-awake screen and its settings entry"
```

---

## Nach Abschluss

Der Plan ist erledigt, wenn `.\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --no-build-cache` **167 Tests / 0 Fehler** meldet, `.\gradlew.bat assembleDebug` baut und die manuelle Prüfung aus Task 3 Step 8 bestanden ist.

Besonders die Punkte 4 und 8 der manuellen Liste sind nicht optional: Punkt 4 ist der einzige Beleg, dass der explizite `null`-Wert für „permanent" tatsächlich über die Leitung geht — alle Unit-Tests dazu prüfen den erzeugten JSON-String, nicht die Antwort des echten Servers. Punkt 8 ist der einzige Beleg für die Admin-Beschränkung an der Oberfläche.
