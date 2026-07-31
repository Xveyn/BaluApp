# Testsuite reparieren — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `gradlew testDebugUnitTest` läuft grün — lokal und auf CI.

**Architecture:** Die 36 Fehlschläge haben fünf verschiedene Ursachen und werden in einer festen Reihenfolge abgearbeitet: zuerst das Speicherproblem der Test-JVM, weil eine sterbende JVM den wahren Zustand aller anderen Klassen verdeckt, dann eine Neuvermessung, dann die Test-Setups, dann die veralteten Assertions, zuletzt zwei kleine Refactorings, die Produktivcode überhaupt erst unit-testbar machen.

**Tech Stack:** Kotlin, JUnit4, MockK, Turbine, kotlinx-coroutines-test, Hilt, Retrofit, Gradle 8.9 / AGP 8.5.2 / JDK 21.

**Spec:** `docs/superpowers/specs/2026-07-31-testsuite-reparieren-design.md`

## Global Constraints

- **Testkommando:** `.\gradlew.bat testDebugUnitTest` (Windows-Session; CI nutzt `./gradlew testDebugUnitTest`). Einzelne Klasse: `.\gradlew.bat testDebugUnitTest --tests "voll.qualifizierter.Name"`.
- **Kein `@Ignore`, keine gelöschten Tests als Abkürzung.** Ein Test, der nicht grün zu bekommen ist, ist ein BLOCKED-Report, keine stillschweigende Entschärfung.
- **Fehlerzahl nach jeder Task festhalten.** Jede Task endet mit einem vollen Suite-Lauf und der Zeile `X tests completed, Y failed` im Report. So bleiben Fortschritt und Regressionen sichtbar.
- **Keine Regression:** kein Test, der zu Beginn einer Task grün war, darf danach rot sein. Der Referenzstand ist `.superpowers/sdd/2026-07-31-app-desktop-toggle-gaming-mode/baseline-failures.txt` bzw. die in Task 2 neu geschriebene Liste.
- **`app/src/main/java/com/baluhost/android/data/worker/FolderSyncWorker.kt` hat uncommittete Änderungen aus fremder Arbeit** — niemals anfassen, niemals mitcommitten. `git add` immer dateigenau, nie `git add -A` oder `git commit -a`.
- **Produktivverhalten bleibt, wo es bewusst gewählt wurde.** Insbesondere schluckt `GetFilesUseCase` weiterhin Fehler (Task 5) und `RegisterDeviceUseCase` baut weiterhin einen Client auf die QR-Server-URL (Task 7). Refactorings dürfen die Laufzeitwirkung nicht verändern.
- **Deutsche Strings bleiben deutsch.** Die App hat kein i18n-Framework; Nutzertexte stehen als Literale im Code.

---

### Task 1: Speicher der Test-JVM

Die einzige Task dieses Plans, deren Lösung **nicht** vorab feststeht. `app/build.gradle.kts` setzt kein `maxHeapSize`, die Gradle-Test-JVM läuft also auf dem Default von 512 MB, und 15 Tests sterben mit `OutOfMemoryError` in `kotlin.reflect...ProtoBuf` (MockKs Reflection-Pfad).

**Wichtige Vorerfahrung, nicht ignorieren:** ein Versuch mit `maxHeapSize = "2g"` machte es **schlimmer** — es fielen mehr Tests um als vorher, darunter das bis dahin grüne `DashboardViewModelVpnActionTest`. Der naheliegende Heap-Bump ist also nicht gesetzt. Deshalb werden zwei Hypothesen gegeneinander gemessen und nicht gestapelt.

**Files:**
- Modify: `app/build.gradle.kts:69-73` (der `testOptions`-Block)

**Interfaces:**
- Consumes: nichts
- Produces: eine grüne `NetworkStateManagerBssidTest` im vollen Lauf; die gewählte Gradle-Konfiguration ist die Grundlage für alle folgenden Tasks

> **Nachtrag nach Ausführung (2026-07-31).** Task 1 ist mit `forkEvery = 1` erledigt
> (Commit `24c9e02`): 46 → 27 Fehlschläge, zwei übereinstimmende Läufe, keine
> Regression. Die ursprüngliche Akzeptanz „alle 15 OOM-Tests grün" wurde vom
> Menschen bewusst auf `NetworkStateManagerBssidTest` (8 Tests) eingeschränkt.
>
> Grund: ein Kontrollexperiment belegt, dass `maxHeapSize` innerhalb von
> `unitTests { all { … } }` sehr wohl wirkt (16 MB ließ den Worker abstürzen,
> `--info` zeigte `-Xmx2g` auf den geforkten JVMs) — und `VpnViewModelTest` fällt
> trotzdem bei 1 GB, 2 GB **und** 4 GB völlig identisch um; bei 4 GB wurde der Build
> zusätzlich instabil. Damit ist belegt, dass es kein Sizing-Problem ist, sondern
> ein Defekt in der Testklasse selbst. Er wird in **Task 8** behandelt.
>
> Die unten in Step 4/5 beschriebene Hypothese B ist damit erledigt und war
> erfolglos; die Steps bleiben als Messprotokoll stehen.

- [ ] **Step 1: Ausgangsstand messen und festhalten**

Run: `.\gradlew.bat testDebugUnitTest --console=plain`

Erwartet: `102 tests completed, 36 failed`. Notiere die Zahl. Weicht sie ab, halte die tatsächliche Zahl fest und arbeite mit dieser weiter — sie ist ab jetzt dein Referenzstand.

- [ ] **Step 2: Hypothese A — Fork pro Testklasse**

Der Stacktrace zeigt Akkumulation von Kotlin-Metadaten über den Lauf hinweg. `forkEvery` gibt jeder Testklasse eine frische JVM, statt den Heap zu vergrößern.

In `app/build.gradle.kts` den `testOptions`-Block ersetzen durch:

```kotlin
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                // MockK deserialises Kotlin metadata through kotlin-reflect and
                // holds onto it. Across a full run that outgrows the Test task's
                // 512m default, and whichever class runs late dies with an OOM.
                // A fresh JVM per class keeps the accumulation bounded.
                it.forkEvery = 1
            }
        }
    }
```

- [ ] **Step 3: Hypothese A messen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain`

Notiere `X tests completed, Y failed` und prüfe zwei Dinge:
1. Sind die 15 OOM-Tests (`NetworkStateManagerBssidTest`, `VpnViewModelTest`) grün?
2. Ist **kein** vorher grüner Test rot geworden? Insbesondere `DashboardViewModelVpnActionTest`, `SyncRepositoryImplTest`, `FileMetadataCRDTTest`, `ConflictDetectionServiceTest`, `QrScannerViewModelTest`, `SleepScheduleUtilTest`, `BssidReaderTest`, `WireGuardConfigParserTest`.

Sind beide Bedingungen erfüllt → weiter mit Step 6. Sonst → Step 4.

- [ ] **Step 4: Hypothese B — größerer Heap statt Fork**

Nur wenn Hypothese A gescheitert ist. Die Änderung aus Step 2 **ersetzen** (nicht ergänzen — Hypothesen werden nicht gestapelt):

```kotlin
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                // MockK's kotlin-reflect path outgrows the Test task's 512m
                // default over a full run.
                it.maxHeapSize = "1g"
            }
        }
    }
```

Bewusst 1 GB, nicht 2 GB: bei 2 GB wurden zuvor mehr Tests rot, vermutlich weil verändertes GC-Timing die Turbine- und Coroutine-Tests kippt. Ein kleinerer Schritt ist die vorsichtigere Messung.

- [ ] **Step 5: Hypothese B messen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain`

Dieselben zwei Prüfungen wie in Step 3.

Sind beide erfüllt → weiter mit Step 6.

Sind sie es nicht: **STOP und BLOCKED melden.** Keine dritte Variante raten, keine Kombination aus beiden probieren. Berichte im Report: welche Konfiguration welche Fehlerzahl ergab, welche Tests jeweils rot waren, und welche Tests durch die Änderung *neu* rot wurden. Der Controller entscheidet dann.

- [ ] **Step 6: Vollen Lauf zur Bestätigung wiederholen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain`

Zweimal dasselbe Ergebnis ist die Mindestanforderung, bevor eine Speicher- oder Timing-Änderung als stabil gilt. Weichen die beiden Läufe voneinander ab, ist das Ergebnis flaky — melde das als DONE_WITH_CONCERNS mit beiden Zahlen.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts
git commit -m "fix(test): stop the unit test JVM running out of memory"
```

---

### Task 2: Restliste neu vermessen

Kurze, rein dokumentierende Task. Sie existiert, weil Task 1 den Blick auf die übrigen Klassen freigelegt hat und die Fehlerliste jetzt anders aussehen kann als in der Spec geschätzt. Die Tasks 3–7 arbeiten gegen **diese** Liste, nicht gegen die Schätzung.

**Files:**
- Create: `docs/superpowers/plans/2026-07-31-testsuite-restliste.md`

**Interfaces:**
- Consumes: die Gradle-Konfiguration aus Task 1
- Produces: `docs/superpowers/plans/2026-07-31-testsuite-restliste.md` — die verbindliche Restliste für Tasks 3–7

- [ ] **Step 1: Fehlschläge einsammeln**

Run: `.\gradlew.bat testDebugUnitTest --console=plain`

- [ ] **Step 2: Liste schreiben**

Lege `docs/superpowers/plans/2026-07-31-testsuite-restliste.md` an mit:
- der Zeile `X tests completed, Y failed`
- pro verbleibendem Fehlschlag: Testklasse, Testname, Fehlermeldung (die erste Zeile des Stacktrace reicht)
- pro Fehlschlag die Zuordnung zu einer Task dieses Plans: Task 3 (`FilesViewModelTest`), Task 4 (`SettingsViewModelNetworkTest`), Task 5 (`DeleteFileUseCaseTest`, `GetFilesUseCaseTest`, Textdrift in `FilesViewModelTest`), Task 6 (`ImportVpnConfigUseCaseTest`), Task 7 (`RegisterDeviceUseCaseTest`)
- eine ausdrückliche Zeile für jeden Fehlschlag, der **keiner** Task zuzuordnen ist

Die Fehlermeldungen stehen ausführlich in `app/build/test-results/testDebugUnitTest/TEST-*.xml` im `<failure message="...">`-Attribut.

- [ ] **Step 3: Nicht zuzuordnende Fehlschläge melden**

Gibt es Fehlschläge ohne Task-Zuordnung — etwa der in der Spec erwähnte `VpnViewModelTest > initial state should show no config when missing` (`expected:<Keine VPN-Konfiguration gefunden> but was:<null>`), der erst nach Task 1 sicher beurteilbar ist — dann melde DONE_WITH_CONCERNS und liste sie im Report auf. Nicht selbst reparieren: der Plan deckt sie nicht ab, und der Controller muss entscheiden.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/plans/2026-07-31-testsuite-restliste.md
git commit -m "docs: record the remaining test failures after the memory fix"
```

---

### Task 3: FilesViewModelTest — fehlende Flow-Stubs

`FilesViewModelTest` mockt `PreferencesManager` mit `mockk(relaxed = true)` und stubbt keinen einzigen Flow. Ein relaxter Mock liefert für Flow-Rückgaben einen **leeren** Flow. `FilesViewModel.init` ruft über `checkAuthenticationAndLoadFiles()` aber `.first()` darauf auf (`FilesViewModel.kt:190-191`), und `.first()` auf einem leeren Flow wirft `NoSuchElementException` — bevor der Test überhaupt läuft.

Das Vorbild steht im Repo: `DashboardViewModelVpnActionTest` stubbt jeden im `init` benutzten Flow explizit und begründet es im Kommentar.

**Files:**
- Modify: `app/src/test/java/com/baluhost/android/presentation/ui/screens/files/FilesViewModelTest.kt:38-68` (der `setup()`-Block)

**Interfaces:**
- Consumes: die Restliste aus Task 2
- Produces: nichts, was andere Tasks nutzen

- [ ] **Step 1: Fehlschlag bestätigen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.presentation.ui.screens.files.FilesViewModelTest"`

Erwartet: mehrere Tests FAILED, davon welche mit `java.util.NoSuchElementException: Expected at least one element`.

- [ ] **Step 2: Flow-Stubs ergänzen**

In `setup()`, direkt nach `networkStateManager = mockk(relaxed = true)` und **vor** dem `coEvery { getFilesUseCase(...) }`, einfügen:

```kotlin
        // FilesViewModel.init calls .first() on these two (FilesViewModel.kt:190-191).
        // A relaxed mock answers a Flow-returning call with an EMPTY flow, and
        // .first() on an empty flow throws NoSuchElementException before the test
        // body ever runs. Same reason DashboardViewModelVpnActionTest stubs its
        // PreferencesManager flows explicitly.
        every { preferencesManager.getDeviceId() } returns flowOf("device1")
        every { preferencesManager.getAccessToken() } returns flowOf("token")
        // Collected rather than .first()-ed, so an empty flow would not throw —
        // stubbed anyway so the ViewModel sees a realistic state.
        every { preferencesManager.getServerUrl() } returns flowOf("http://192.168.1.100:3000")
        every { preferencesManager.getVpnConfig() } returns flowOf(null)
```

`flowOf` ist in Zeile 13 bereits importiert.

- [ ] **Step 3: Klasse laufen lassen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.presentation.ui.screens.files.FilesViewModelTest"`

Erwartet: kein `NoSuchElementException` mehr.

Es bleiben voraussichtlich zwei Fehlschläge übrig, die **nicht** zu dieser Task gehören und in Task 5 behandelt werden: die Textdrift `expected:<[Network erro]r> but was:<[Keine Verbindung zum Serve]r>` und möglicherweise `expected:<2> but was:<0>`. Repariere sie hier **nicht**.

Verschwinden auch der Turbine-Fehlschlag (`No value produced in 3s`) und der nackte `AssertionError` — gut, sie waren Folgeschäden des Setups. Bleiben sie bestehen, halte das im Report fest.

- [ ] **Step 4: Volle Suite**

Run: `.\gradlew.bat testDebugUnitTest --console=plain`

Erwartet: Fehlerzahl gegenüber Task 2 gesunken, kein vorher grüner Test rot.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/baluhost/android/presentation/ui/screens/files/FilesViewModelTest.kt
git commit -m "test(files): stub the preference flows FilesViewModel reads on init"
```

---

### Task 4: SettingsViewModelNetworkTest — strikter NetworkMonitor-Mock

Diese Klasse stubbt ihre `PreferencesManager`-Flows bereits vollständig. Der Fehler liegt woanders: `networkMonitor = mockk()` ist **strikt** (kein `relaxed`), gestubbt ist nur `isCurrentlyWifiConnected()`. `SettingsViewModel.init` ruft aber auch `observeWifiState()` auf, und das sammelt die Property `networkMonitor.isWifiConnected` (`SettingsViewModel.kt:281`, Typ `Flow<Boolean>`). Der Zugriff auf einen nicht gestubbten Aufruf eines strikten Mocks wirft — innerhalb eines `viewModelScope.launch`, weshalb es als `UncaughtExceptionsBeforeTest` erscheint.

**Files:**
- Modify: `app/src/test/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModelNetworkTest.kt:39-47` (der Stub-Block in `setup()`)

**Interfaces:**
- Consumes: die Restliste aus Task 2
- Produces: nichts, was andere Tasks nutzen

- [ ] **Step 1: Fehlschlag bestätigen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.presentation.ui.screens.settings.SettingsViewModelNetworkTest"`

Erwartet: 3 Tests FAILED mit `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`.

- [ ] **Step 2: Den fehlenden Stub ergänzen**

Direkt nach `every { networkMonitor.isCurrentlyWifiConnected() } returns true` (Zeile 47) einfügen:

```kotlin
        // init also runs observeWifiState(), which collects this property
        // (SettingsViewModel.kt:281). networkMonitor is a strict mock, so an
        // unstubbed access throws inside viewModelScope — surfacing as
        // UncaughtExceptionsBeforeTest rather than as a readable failure.
        every { networkMonitor.isWifiConnected } returns flowOf(true)
```

`flowOf` ist in Zeile 16 bereits importiert.

- [ ] **Step 3: Klasse laufen lassen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.presentation.ui.screens.settings.SettingsViewModelNetworkTest"`

Erwartet: BUILD SUCCESSFUL, 3/3 grün.

- [ ] **Step 4: Volle Suite**

Run: `.\gradlew.bat testDebugUnitTest --console=plain`

Erwartet: Fehlerzahl gesunken, kein vorher grüner Test rot.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModelNetworkTest.kt
git commit -m "test(settings): stub the wifi flow SettingsViewModel collects on init"
```

---

### Task 5: Veraltete Assertions nachziehen

Drei Tests behaupten ein Verhalten, das der Produktivcode bewusst nicht mehr hat. Der Produktivcode bleibt unverändert — hier ändern sich ausschließlich Tests.

**Files:**
- Modify: `app/src/test/java/com/baluhost/android/domain/usecase/files/DeleteFileUseCaseTest.kt`
- Modify: `app/src/test/java/com/baluhost/android/domain/usecase/files/GetFilesUseCaseTest.kt:92-109`
- Modify: `app/src/test/java/com/baluhost/android/presentation/ui/screens/files/FilesViewModelTest.kt` (nur die Textdrift-Assertion)

**Interfaces:**
- Consumes: die Restliste aus Task 2; die Setup-Reparatur aus Task 3
- Produces: nichts, was andere Tasks nutzen

- [ ] **Step 1: Die drei Fehlschläge bestätigen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.domain.usecase.files.*" --tests "com.baluhost.android.presentation.ui.screens.files.FilesViewModelTest"`

Erwartet, unter anderem:
- `org.junit.ComparisonFailure: expected:<[]File not found> but was:<[Delete failed: ]File not found>`
- `org.junit.ComparisonFailure: expected:<[Network erro]r> but was:<[Keine Verbindung zum Serve]r>`
- ein `AssertionError` in `GetFilesUseCaseTest > invoke should return error when repository call fails`

- [ ] **Step 2: DeleteFileUseCaseTest nachziehen**

`DeleteFileUseCase.kt:19` verpackt jeden Fehler als `Exception("Delete failed: ${e.message}", e)`. Betroffen ist in dieser Klasse genau **eine** Assertion, `DeleteFileUseCaseTest.kt:85`. Ersetze

```kotlin
        assertEquals(errorMessage, errorResult.exception.message)
```

durch

```kotlin
        // DeleteFileUseCase wraps the cause: "Delete failed: <original>".
        assertEquals("Delete failed: $errorMessage", errorResult.exception.message)
```

Die übrigen Tests der Klasse bleiben unverändert. Insbesondere `invoke should return error when user lacks permissions` (Zeile 104) prüft mit `.contains("Permission denied")` und besteht mit dem Präfix weiterhin — nicht anfassen.

- [ ] **Step 3: GetFilesUseCaseTest auf das gewollte Fehlerschlucken umstellen**

`GetFilesUseCase` fängt **jede** Exception und gibt `Result.Success(emptyList())` zurück — absichtlich, siehe Kommentar in `GetFilesUseCase.kt:29-30`. Der Test behauptet noch `Result.Error`.

Den Test in `GetFilesUseCaseTest.kt:92-109` vollständig ersetzen durch:

```kotlin
    @Test
    fun `invoke swallows repository failures and returns an empty list`() = runTest {
        // Given
        val path = "documents"

        coEvery {
            fileRepository.getFiles(path, false)
        } throws Exception("Failed to list files")

        // When
        val result = getFilesUseCase(path)

        // Then — deliberate: a failure here must not become a Result.Error,
        // because the Files screen would navigate to the QR scanner on one.
        // The user learns about the outage from ServerConnectivityChecker
        // instead. See the comment in GetFilesUseCase.
        assertTrue(result is Result.Success)
        assertTrue((result as Result.Success).data.isEmpty())
    }
```

Der Testname benennt das Schlucken jetzt ausdrücklich — die Absicht steht damit im Test und nicht nur in einem Kommentar der Implementierung.

- [ ] **Step 4: Textdrift in FilesViewModelTest nachziehen**

Im Test `loadFiles should set error state on failure` stubbt Zeile 285 den UseCase mit `Result.Error(Exception(errorMessage))`, wobei `errorMessage` = `"Network error"` ist. Das `FilesViewModel` übernimmt diesen Text jedoch nicht, sondern setzt seine eigene deutsche Meldung. Die Korrektur gehört deshalb an die **Assertion**, nicht an den Stub.

`FilesViewModelTest.kt:301` ersetzen:

```kotlin
            assertEquals(errorMessage, errorState.error)
```

durch

```kotlin
            // The ViewModel does not pass the cause's text through — it reports
            // its own user-facing wording, so the stub's message is only an input.
            assertEquals("Keine Verbindung zum Server", errorState.error)
```

`val errorMessage = "Network error"` in Zeile 283 bleibt stehen; es ist weiterhin die Eingabe für den Stub.

Falls in derselben Klasse noch `expected:<2> but was:<0>` auftritt: das ist **keine** Textdrift. Nicht raten — im Report als offenen Punkt melden, wenn Task 3 ihn nicht bereits mitbehoben hat.

- [ ] **Step 5: Die betroffenen Klassen laufen lassen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.domain.usecase.files.*" --tests "com.baluhost.android.presentation.ui.screens.files.FilesViewModelTest"`

Erwartet: alle drei behandelten Fehlschläge grün.

- [ ] **Step 6: Volle Suite**

Run: `.\gradlew.bat testDebugUnitTest --console=plain`

Erwartet: Fehlerzahl gesunken, kein vorher grüner Test rot.

- [ ] **Step 7: Commit**

```bash
git add app/src/test/java/com/baluhost/android/domain/usecase/files/DeleteFileUseCaseTest.kt app/src/test/java/com/baluhost/android/domain/usecase/files/GetFilesUseCaseTest.kt app/src/test/java/com/baluhost/android/presentation/ui/screens/files/FilesViewModelTest.kt
git commit -m "test(files): align assertions with the current error contracts"
```

---

### Task 6: ImportVpnConfigUseCase unit-testbar machen

`ImportVpnConfigUseCase` dekodiert mit `android.util.Base64`. Im reinen JVM-Unit-Test greift `isReturnDefaultValues = true` aus `app/build.gradle.kts`, die Framework-Methode liefert `null`, und `String(null, UTF_8)` wirft — die Klasse gibt unter Test also **immer** `Result.Error` zurück. Alle vier Tests der Klasse können so nie grün werden, egal wie man die Assertions dreht.

Der Decoder wandert hinter ein Interface: Android-Implementierung in Produktion, `java.util.Base64` im Test. Das Laufzeitverhalten ändert sich nicht.

**Files:**
- Create: `app/src/main/java/com/baluhost/android/util/Base64Decoder.kt`
- Modify: `app/src/main/java/com/baluhost/android/di/AppModule.kt`
- Modify: `app/src/main/java/com/baluhost/android/domain/usecase/vpn/ImportVpnConfigUseCase.kt:17-26`
- Modify: `app/src/test/java/com/baluhost/android/domain/usecase/vpn/ImportVpnConfigUseCaseTest.kt:19-23` (der `setup()`-Block)

**Interfaces:**
- Consumes: die Restliste aus Task 2
- Produces:
  - `interface Base64Decoder { fun decode(input: String): ByteArray }` in `com.baluhost.android.util`
  - `class AndroidBase64Decoder : Base64Decoder` ebendort
  - `ImportVpnConfigUseCase(preferencesManager: PreferencesManager, base64Decoder: Base64Decoder)` — **zweiter** Konstruktorparameter, angehängt

- [ ] **Step 1: Fehlschlag bestätigen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.domain.usecase.vpn.ImportVpnConfigUseCaseTest"`

Erwartet: 4 Tests FAILED mit `java.lang.AssertionError` (die Tests erwarten `Result.Success`, bekommen aber `Result.Error`).

- [ ] **Step 2: Interface und Android-Implementierung anlegen**

Create `app/src/main/java/com/baluhost/android/util/Base64Decoder.kt`:

```kotlin
package com.baluhost.android.util

import android.util.Base64
import javax.inject.Inject

/**
 * Base64 decoding behind an interface.
 *
 * android.util.Base64 is an Android framework class. Plain JVM unit tests run
 * against a stubbed framework where it returns null, so anything calling it
 * directly cannot be unit tested at all — which is exactly what happened to
 * ImportVpnConfigUseCase.
 */
interface Base64Decoder {
    /** @throws IllegalArgumentException if [input] is not valid Base64. */
    fun decode(input: String): ByteArray
}

class AndroidBase64Decoder @Inject constructor() : Base64Decoder {
    override fun decode(input: String): ByteArray = Base64.decode(input, Base64.DEFAULT)
}
```

- [ ] **Step 3: Binding ergänzen**

In `app/src/main/java/com/baluhost/android/di/AppModule.kt` den Import `com.baluhost.android.util.AndroidBase64Decoder` und `com.baluhost.android.util.Base64Decoder` ergänzen und innerhalb des `object AppModule` anfügen:

```kotlin
    @Provides
    @Singleton
    fun provideBase64Decoder(): Base64Decoder = AndroidBase64Decoder()
```

Das folgt dem bestehenden Muster von `provideNetworkMonitor` in derselben Datei.

- [ ] **Step 4: UseCase auf den injizierten Decoder umstellen**

In `ImportVpnConfigUseCase.kt` den Import `android.util.Base64` entfernen, `com.baluhost.android.util.Base64Decoder` ergänzen, und Konstruktor sowie Dekodierung ersetzen:

```kotlin
class ImportVpnConfigUseCase @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val base64Decoder: Base64Decoder
) {

    suspend operator fun invoke(configBase64: String): Result<VpnConfig> {
        return try {
            val configString = String(
                base64Decoder.decode(configBase64),
                Charsets.UTF_8
            )
```

Der restliche Methodenrumpf bleibt unverändert, einschließlich des `catch`-Zweigs — ungültiges Base64 wirft weiterhin und wird weiterhin zu `Result.Error`.

- [ ] **Step 5: Test auf den JVM-Decoder umstellen**

In `ImportVpnConfigUseCaseTest.kt` den `setup()`-Block ersetzen durch:

```kotlin
    @Before
    fun setup() {
        preferencesManager = mockk(relaxed = true)
        // The production decoder calls android.util.Base64, which is stubbed to
        // null in JVM unit tests. java.util.Base64 is the real thing and throws
        // IllegalArgumentException on malformed input, exactly like the Android
        // one — so the "invalid base64" test keeps its meaning.
        val base64Decoder = object : Base64Decoder {
            override fun decode(input: String): ByteArray =
                java.util.Base64.getDecoder().decode(input)
        }
        importVpnConfigUseCase = ImportVpnConfigUseCase(preferencesManager, base64Decoder)
    }
```

Und den Import `com.baluhost.android.util.Base64Decoder` ergänzen. Die vier Testkörper bleiben unverändert — sie kodieren bereits mit `java.util.Base64`.

- [ ] **Step 6: Klasse laufen lassen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.domain.usecase.vpn.ImportVpnConfigUseCaseTest"`

Erwartet: BUILD SUCCESSFUL, alle Tests grün — inklusive `invoke should return error for invalid base64`, weil `java.util.Base64` bei `not_valid_base64!!!` wirft.

- [ ] **Step 7: Volle Suite**

Run: `.\gradlew.bat testDebugUnitTest --console=plain`

Erwartet: Fehlerzahl gesunken, kein vorher grüner Test rot. Ein Hilt-Fehler beim Kompilieren bedeutet, dass das Binding aus Step 3 fehlt oder falsch platziert ist.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/baluhost/android/util/Base64Decoder.kt app/src/main/java/com/baluhost/android/di/AppModule.kt app/src/main/java/com/baluhost/android/domain/usecase/vpn/ImportVpnConfigUseCase.kt app/src/test/java/com/baluhost/android/domain/usecase/vpn/ImportVpnConfigUseCaseTest.kt
git commit -m "refactor(vpn): inject the base64 decoder so the import can be tested"
```

---

### Task 7: RegisterDeviceUseCase unit-testbar machen

`RegisterDeviceUseCase` nimmt ein `mobileApi` entgegen und **benutzt es nie**. Stattdessen baut es sich in `invoke()` ein eigenes Retrofit auf die aus dem QR-Code stammende Server-URL. Der Mock des Tests greift deshalb nie durch; der Test versucht tatsächlich, `https://test.com/api/` zu erreichen, und scheitert.

Das dynamische Verhalten ist fachlich richtig — Pairing zeigt naturgemäß auf einen beliebigen Server. Falsch ist nur die tote Abhängigkeit. Der Retrofit-Bau wandert hinter eine injizierte Factory; die Laufzeitwirkung bleibt identisch.

**Files:**
- Create: `app/src/main/java/com/baluhost/android/data/remote/api/MobileApiFactory.kt`
- Modify: `app/src/main/java/com/baluhost/android/di/AppModule.kt`
- Modify: `app/src/main/java/com/baluhost/android/domain/usecase/auth/RegisterDeviceUseCase.kt:27-82`
- Modify: `app/src/test/java/com/baluhost/android/domain/usecase/auth/RegisterDeviceUseCaseTest.kt:20-34` (Felder und `setup()`)

**Interfaces:**
- Consumes: die Restliste aus Task 2; das `@Provides`-Muster in `AppModule` aus Task 6
- Produces:
  - `interface MobileApiFactory { fun create(baseUrl: String, tokenProvider: () -> String?): MobileApi }`
  - `RegisterDeviceUseCase(mobileApiFactory: MobileApiFactory, preferencesManager: PreferencesManager)` — der bisherige `mobileApi`-Parameter **entfällt ersatzlos**

- [ ] **Step 1: Fehlschlag bestätigen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.domain.usecase.auth.RegisterDeviceUseCaseTest"`

Erwartet: mehrere Tests FAILED mit `java.lang.AssertionError`.

- [ ] **Step 2: Factory anlegen**

Create `app/src/main/java/com/baluhost/android/data/remote/api/MobileApiFactory.kt`:

```kotlin
package com.baluhost.android.data.remote.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Builds a MobileApi bound to a server URL that is only known at runtime.
 *
 * Device pairing points at whatever server the QR code names, so the injected
 * MobileApi — which is wired to BuildConfig.BASE_URL — is the wrong client for
 * it. Behind this interface the registration use case can be unit tested
 * without a real Retrofit reaching for the network.
 */
interface MobileApiFactory {
    /**
     * @param baseUrl the server root from the QR code, with or without a
     *   trailing slash; the "api/" segment is appended here.
     * @param tokenProvider consulted per request, so a token obtained during
     *   registration is picked up by later calls on the same client.
     */
    fun create(baseUrl: String, tokenProvider: () -> String?): MobileApi
}

class RetrofitMobileApiFactory @Inject constructor() : MobileApiFactory {

    override fun create(baseUrl: String, tokenProvider: () -> String?): MobileApi {
        val finalUrl = baseUrl.let { if (it.endsWith("/")) it else "$it/" } + "api/"

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                tokenProvider()?.let {
                    requestBuilder.header("Authorization", "Bearer $it")
                }
                chain.proceed(requestBuilder.build())
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(finalUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MobileApi::class.java)
    }
}
```

- [ ] **Step 3: Binding ergänzen**

In `AppModule.kt` die Imports `com.baluhost.android.data.remote.api.MobileApiFactory` und `com.baluhost.android.data.remote.api.RetrofitMobileApiFactory` ergänzen und anfügen:

```kotlin
    @Provides
    @Singleton
    fun provideMobileApiFactory(): MobileApiFactory = RetrofitMobileApiFactory()
```

- [ ] **Step 4: UseCase umstellen**

In `RegisterDeviceUseCase.kt`:

Konstruktor ersetzen:

```kotlin
class RegisterDeviceUseCase @Inject constructor(
    private val mobileApiFactory: MobileApiFactory,
    private val preferencesManager: PreferencesManager
) {
```

Den Import `com.baluhost.android.data.remote.api.MobileApi` durch `com.baluhost.android.data.remote.api.MobileApiFactory` ersetzen.

Den gesamten Block von `val finalUrl = ...` bis `val dynamicMobileApi = retrofit.create(...)` (die Zeilen 40-67, inklusive der beiden `Log.d`-Aufrufe zur URL, des `OkHttpClient`- und des `Retrofit`-Baus) ersetzen durch:

```kotlin
            // The QR code names the server, so the client is built per call.
            android.util.Log.d("RegisterDevice", "Using server URL: $serverUrl")

            // Set once registration succeeds; the factory's interceptor reads it
            // per request, so later calls on this client carry the token.
            var accessToken: String? = null

            val dynamicMobileApi = mobileApiFactory.create(serverUrl) { accessToken }
```

Alles ab `val deviceInfo = DeviceInfoDto(` bleibt unverändert — insbesondere die Zuweisung `accessToken = response.accessToken` und der `registerPushToken`-Aufruf.

**Wichtig:** die alte Deklaration `var accessToken: String? = null` aus Zeile 45 darf nicht doppelt stehenbleiben; sie ist im eingefügten Block bereits enthalten.

- [ ] **Step 5: Test auf die Factory umstellen**

In `RegisterDeviceUseCaseTest.kt` das Feld `mobileApi` durch ein Factory-Feld ersetzen und `setup()` so anpassen, dass die Factory den gemockten `MobileApi` zurückgibt:

```kotlin
    private lateinit var mobileApi: MobileApi
    private lateinit var mobileApiFactory: MobileApiFactory
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var registerDeviceUseCase: RegisterDeviceUseCase

    @Before
    fun setup() {
        mobileApi = mockk()
        preferencesManager = mockk(relaxed = true)
        // The use case builds its client from the QR code's server URL. Handing
        // back the mock here is what makes the coEvery stubs below take effect —
        // previously the injected MobileApi was ignored and a real Retrofit went
        // looking for https://test.com/api/.
        mobileApiFactory = mockk()
        every { mobileApiFactory.create(any(), any()) } returns mobileApi
        registerDeviceUseCase = RegisterDeviceUseCase(mobileApiFactory, preferencesManager)
    }
```

Den Import `com.baluhost.android.data.remote.api.MobileApiFactory` ergänzen. Die Testkörper bleiben unverändert — ihre `coEvery { mobileApi.registerDevice(...) }`-Stubs greifen jetzt zum ersten Mal.

Der Test stubbt `preferencesManager` relaxt; `getFcmToken().first()` läuft dabei auf einen leeren Flow und würde werfen. Ergänze deshalb in `setup()`:

```kotlin
        every { preferencesManager.getFcmToken() } returns flowOf(null)
```

und den Import `kotlinx.coroutines.flow.flowOf`.

- [ ] **Step 6: Klasse laufen lassen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.domain.usecase.auth.RegisterDeviceUseCaseTest"`

Erwartet: BUILD SUCCESSFUL.

Bleibt ein Test rot, weil er ein Detail des früheren Inline-Retrofit prüfte, melde ihn im Report — nicht die Factory verbiegen, damit ein Test grün wird.

- [ ] **Step 7: Volle Suite**

Run: `.\gradlew.bat testDebugUnitTest --console=plain`

Erwartet: **`102 tests completed, 0 failed`** — dies ist die letzte Task, hier muss die Suite grün sein. Ist sie es nicht, benenne im Report jeden verbliebenen Fehlschlag.

- [ ] **Step 8: Kompilierung der App prüfen**

Run: `.\gradlew.bat assembleDebug`

Erwartet: BUILD SUCCESSFUL. Die Konstruktoränderung an `RegisterDeviceUseCase` betrifft den Hilt-Graphen; ein Fehler hier bedeutet ein fehlendes oder falsch platziertes Binding aus Step 3.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/remote/api/MobileApiFactory.kt app/src/main/java/com/baluhost/android/di/AppModule.kt app/src/main/java/com/baluhost/android/domain/usecase/auth/RegisterDeviceUseCase.kt app/src/test/java/com/baluhost/android/domain/usecase/auth/RegisterDeviceUseCaseTest.kt
git commit -m "refactor(auth): build the pairing client through an injected factory"
```

---

### Task 8: VpnViewModelTest — OOM in der Testklasse selbst

Nach Task 1 gehört diese Klasse zu den echten Defekten, nicht mehr zum Speicherthema. Belegt: sie fällt in einer **eigenen frischen JVM** mit `OutOfMemoryError` um, und zwar bei 512 MB, 1 GB, 2 GB und 4 GB **identisch**. Mehr Heap ist nachweislich nicht die Lösung — die Klasse hält etwas fest, das sie nicht festhalten sollte. Der Stacktrace zeigt `kotlin.reflect.jvm.internal.impl.metadata.ProtoBuf`, also MockKs Reflection-Pfad.

Diese Task hat als einzige **kein vorgegebenes Ergebnis**. Sie ist eine Untersuchung mit anschließender Reparatur; wenn die Ursache nicht zu finden ist, ist das ein BLOCKED-Report und keine Notlösung.

**Files:**
- Modify: `app/src/test/java/com/baluhost/android/presentation/ui/screens/vpn/VpnViewModelTest.kt`
- Ggf. modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/vpn/VpnViewModel.kt` — nur, wenn die Ursache dort liegt und die Änderung das Laufzeitverhalten nicht verändert

**Interfaces:**
- Consumes: die Gradle-Konfiguration aus Task 1 (`forkEvery = 1`)
- Produces: nichts, was andere Tasks nutzen

- [ ] **Step 1: Fehlschlag isoliert reproduzieren**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.presentation.ui.screens.vpn.VpnViewModelTest"`

Erwartet: 7 Tests mit `OutOfMemoryError`, dazu ein `AssertionError` in `initial state should show no config when missing` (`expected:<Keine VPN-Konfiguration gefunden> but was:<null>`). Der `AssertionError` ist ein eigener, kleinerer Defekt — behandle ihn in Step 5, nicht vorher.

- [ ] **Step 2: Eingrenzen, welcher Test die Speicherlast erzeugt**

Läuft ein **einzelner** Test der Klasse für sich grün?

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.presentation.ui.screens.vpn.VpnViewModelTest.initial state should check for VPN config"`

- Grün → die Last entsteht über die Tests **hinweg**, also ist es Zustand, der zwischen ihnen bestehen bleibt. Weiter mit Step 3.
- Rot → schon ein einzelner Test sprengt den Heap. Dann liegt die Ursache im `setup()` oder im ViewModel-Konstruktor. Weiter mit Step 4.

Halte das Ergebnis im Report fest — es entscheidet, welche der beiden Ursachen vorliegt.

- [ ] **Step 3: Mock-Aufräumen prüfen**

MockK hält gemockte Klassen samt deserialisierter Kotlin-Metadaten fest. Ohne Aufräumen wächst das über die Tests einer Klasse hinweg.

Prüfe in `VpnViewModelTest`, ob es ein `@After` gibt, das `clearAllMocks()` oder `unmockkAll()` aufruft, und ob `Dispatchers.resetMain()` erfolgt. Zum Vergleich: `GetFilesUseCaseTest` hat ein `@After teardown()` mit `clearAllMocks()`, `DashboardViewModelVpnActionTest` ein `@After` mit `Dispatchers.resetMain()`.

Fehlt das Aufräumen, ergänze:

```kotlin
    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }
```

`unmockkAll()` ist gegenüber `clearAllMocks()` das stärkere Mittel: es gibt auch statische und Objekt-Mocks frei, statt nur aufgezeichnete Aufrufe zu vergessen.

- [ ] **Step 4: Auf teure Mocks im Setup prüfen**

Sieh dir an, was `setup()` mockt. `mockk(relaxed = true)` auf einen großen Typ zwingt MockK, dessen komplette Signatur über kotlin-reflect aufzulösen; bei mehreren solchen Mocks pro Test summiert sich das erheblich. Auch ein `@get:Rule`, das pro Test ein neues ViewModel samt Objektgraph baut, kommt in Frage.

Ändere hier **eine** Sache und miss erneut. Nicht mehrere Vermutungen auf einmal — sonst ist am Ende nicht klar, was gewirkt hat.

- [ ] **Step 5: Den AssertionError beheben**

`initial state should show no config when missing` erwartet `Keine VPN-Konfiguration gefunden`, bekommt aber `null`. Das ist dieselbe Art Vertragsdrift wie in Task 5: entweder setzt das ViewModel die Meldung nicht mehr, oder der Test prüft das falsche Feld.

Lies `VpnViewModel`, stelle fest, welches von beidem zutrifft, und ziehe **den Test** nach — das Produktivverhalten bleibt unangetastet, sofern nicht offensichtlich ein Bug vorliegt. Ist es ein echter Bug im ViewModel, repariere ihn nicht selbst, sondern melde ihn im Report.

- [ ] **Step 6: Klasse laufen lassen**

Run: `.\gradlew.bat testDebugUnitTest --console=plain --tests "com.baluhost.android.presentation.ui.screens.vpn.VpnViewModelTest"`

Erwartet: BUILD SUCCESSFUL, 8/8 grün, kein `OutOfMemoryError`.

Bekommst du den OOM nicht weg: **BLOCKED melden.** Kein `@Ignore`, keine gelöschten Tests, kein Heap-Bump als Umgehung — Task 1 hat bereits belegt, dass Heap hier nicht hilft. Berichte, was Step 2 ergeben hat, was du geändert und gemessen hast.

- [ ] **Step 7: Volle Suite, zweimal**

Run: `.\gradlew.bat testDebugUnitTest --console=plain` — und danach ein zweites Mal.

Erwartet: **`102 tests completed, 0 failed`** in beiden Läufen. Zwei Läufe, weil diese Suite nachweislich schwankt.

- [ ] **Step 8: Commit**

```bash
git add app/src/test/java/com/baluhost/android/presentation/ui/screens/vpn/VpnViewModelTest.kt
git commit -m "test(vpn): stop the VPN view model tests exhausting their JVM"
```

Wurde auch `VpnViewModel.kt` geändert, gehört sie mit in denselben `git add`.

---

## Nach Abschluss

Der Plan ist erledigt, wenn `.\gradlew.bat testDebugUnitTest` grün durchläuft und `.\gradlew.bat assembleDebug` baut. Danach:

1. CI prüfen (`gh run list`) — der `-Xmx4g`-Parameter im Workflow adressiert die Daemon-JVM, nicht die Test-JVM; ob er nach Task 1 noch nötig ist, kann dann entschieden werden.
2. Den pausierten Feature-Plan `docs/superpowers/plans/2026-07-31-app-desktop-toggle-gaming-mode.md` bei Task 1 wieder aufnehmen. Sein Ledger liegt unter `.superpowers/sdd/2026-07-31-app-desktop-toggle-gaming-mode/progress.md`; die dort notierte BASE ist überholt und muss neu bestimmt werden.
