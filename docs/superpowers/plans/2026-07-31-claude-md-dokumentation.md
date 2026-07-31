# CLAUDE.md-Dokumentation — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Neun CLAUDE.md-Dateien, die das Wissen festhalten, das sich weder aus dem Code noch aus der Git-Historie ableiten lässt — allen voran die Fallstricke, die in dieser Sitzung Zeit gekostet haben.

**Architecture:** Fünf Tasks. Die acht Verzeichnisdateien zuerst, die Root-Datei zuletzt — so ist ihr Index beim Anlegen überprüfbar und zeigt nie auf etwas, das noch nicht existiert. Es wird **keine Zeile Code** geändert.

**Tech Stack:** Markdown. Zur Verifikation: `ls`, `find`, `grep` gegen das Repo.

**Spec:** `docs/superpowers/specs/2026-07-31-claude-md-dokumentation-design.md`

## Global Constraints

- **Sprache: Englisch.** `README.md`, `CHANGELOG.md` und sämtliche BaluHost-CLAUDE.md sind es. Die deutschen Nutzertexte der App bleiben unberührt.
- **Format je Datei** (nach `D:\Programme (x86)\Baluhost\client\src\api\CLAUDE.md`):
  1. Titel und ein Absatz: was liegt hier und wozu
  2. Kernbegriffe oder Basisbausteine
  3. **Konventionen als Aufzählung, jeweils mit Begründung**
  4. Dateitabelle
  5. „Adding a new …" als nummerierte Schritte
- **Jede Konvention braucht ihr Warum.** „Repositories return `Result<T>`" kann man am Code ablesen; „…because the UI must tell ‚server unreachable' apart from ‚server said no'" ist der Grund und verhindert die nächste Abkürzung. Eine Aufzählung ohne Begründungen ist die häufigste Art, diese Aufgabe zu verfehlen.
- **Keine erfundenen Pfade.** Jede genannte Datei muss existieren. Vor dem Commit wird jeder Pfad geprüft — Dokumentation, die auf nicht existierende Dateien zeigt, ist schlimmer als keine.
- **Keine erfundenen Zahlen.** Wo eine Dateizahl steht, wird sie gezählt (`find <dir> -name "*.kt" | wc -l`).
- **Kein Code wird geändert.** Kein `.kt`, kein `.kts`, kein YAML. Die Testsuite bleibt bei **170 Tests, 0 Fehlern**; es besteht kein Anlass, sie überhaupt laufen zu lassen.
- `app/src/main/java/com/baluhost/android/data/worker/FolderSyncWorker.kt` hat uncommittete Änderungen aus fremder Arbeit — niemals anfassen, niemals mitcommitten. `git add` immer dateigenau, nie `git add -A`, nie `git commit -a`.
- Alle Pfade unten sind relativ zu `app/src/main/java/com/baluhost/android/`, sofern nicht anders angegeben.

---

### Task 1: `app/src/test/CLAUDE.md` — die Fallstricke

Die inhaltlich wertvollste Datei des Plans. Jeder Punkt darin hat in dieser Sitzung nachweislich Zeit gekostet, und keiner davon lässt sich aus dem Code erschließen.

**Files:**
- Create: `app/src/test/CLAUDE.md`

**Interfaces:**
- Consumes: nichts
- Produces: die Datei, auf die Task 5 in seinem Index verweist

- [ ] **Step 1: Datei anlegen**

Create `app/src/test/CLAUDE.md` mit **genau** diesem Inhalt:

```markdown
# Unit Tests

JVM unit tests under `app/src/test/`. There is **no `androidTest` source set** —
no instrumented or Compose UI tests exist, and none are expected. Anything that
needs a device is verified by hand.

## Running them — read this first

`gradle.properties` sets `org.gradle.caching=true`. A bare

```
./gradlew testDebugUnitTest
```

can report `BUILD SUCCESSFUL in 1s` with `FROM-CACHE` or `UP-TO-DATE` **without
executing a single test**. It is not a hypothetical: it happened repeatedly
during the work that produced this file, and a green run that proves nothing is
worse than a red one.

Always verify with:

```
./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache
```

Gradle prints **no test count when everything passes** — only on failure. To
confirm a run actually happened, count the XML results:

```powershell
$x = Get-ChildItem "app\build\test-results\testDebugUnitTest\*.xml" | ForEach-Object { [xml]$c = Get-Content $_.FullName; $c.testsuite }
"tests=$(($x | Measure-Object -Property tests -Sum).Sum) failures=$(($x | Measure-Object -Property failures -Sum).Sum) errors=$(($x | Measure-Object -Property errors -Sum).Sum)"
```

## Pitfalls

Each of these produced a confusing failure before it was understood.

### Relaxed mocks answer Flow calls with an empty flow

`mockk(relaxed = true)` on `PreferencesManager` returns an **empty** flow for
every Flow-returning member. A ViewModel `init` that calls `.first()` on one then
throws `NoSuchElementException` before the test body starts, and the failure
points at the ViewModel rather than at the mock.

Stub every flow the `init` path touches. `DashboardViewModelVpnActionTest` does
this and says why in a comment — copy that shape.

### An unbounded polling loop makes `runTest` never finish

`DashboardViewModel` launches a `while (true) { delay(n); … }` loop from `init` into
`viewModelScope`; `VpnViewModel` launches `while (isActive) { …; delay(n) }` from its
own `init`. Both are unbounded under `runTest`, because the test's own scope stays
active regardless of which condition guards the loop. With `Dispatchers.setMain(testDispatcher)`
in `@Before`, `runTest` adopts the same scheduler, so its closing "advance to
idle" never terminates — it keeps running the loop and allocating until the heap
is gone.

**The stacktrace lies.** It points at `kotlin.reflect…ProtoBuf`, which is merely
whatever allocation was in flight when memory ran out. Raising the heap does not
help; a genuinely unbounded loop exhausts any size.

Cancel the `viewModelScope` **inside** the test body, before `runTest` performs
its final advance:

- `VpnViewModelTest` routes every ViewModel through a `ViewModelStore` and calls
  `clear()` — public API, no reflection.
- `DashboardViewModelVpnActionTest` uses a reflective `clearViewModel()` helper.

Either works; they solve the same hazard.

### `android.util.*` returns null under plain JUnit

`app/build.gradle.kts` sets `isReturnDefaultValues = true`, so framework calls
return type defaults instead of throwing. `Base64.decode(...)` yields `null`, and
`String(null, UTF_8)` then throws — meaning a class calling it directly always
fails under test, no matter what the assertions say.

Production code that reaches for a framework class is not unit testable. Put the
dependency behind an interface: see `util/Base64Decoder.kt` with
`AndroidBase64Decoder` for production and `java.util.Base64` in the test.

### Time needs to be injectable

Boundary cases such as "exactly five minutes from now" cannot be tested against
`Instant.now()` without racing the wall clock. `util/Clock.kt` is a `fun
interface` bound in `AppModule`; view models take it as a constructor parameter
and tests hand in a fixed instant.

### PowerShell splits `-PappVersionName=1.2.3` at the dots

Gradle property arguments containing dots must be quoted:
`"-PappVersionName=1.2.3"`. Unquoted, PowerShell passes fragments and Gradle
reports a missing task.

## Conventions

- JUnit4 with MockK, Turbine and `kotlinx-coroutines-test`.
- One test class per production class, named `<Class>Test`, in the mirrored
  package.
- Test names are backticked sentences describing behaviour, not method names —
  `a failed save restores the previous state`, not `testSaveError`.
- Assert against real behaviour, not against the mock. A test that only verifies
  a mock was called passes even when the thing it guards is broken. Where the
  bug lives in serialisation, assert the serialised bytes —
  `SleepConfigRepositoryTest` reads the `RequestBody` back through an okio
  `Buffer` for exactly that reason.
- Seed a state that differs from the default before asserting a transition. An
  assertion that a value equals its own initial value proves nothing.

## Adding a test for a ViewModel

1. `Dispatchers.setMain(UnconfinedTestDispatcher())` in `@Before`,
   `Dispatchers.resetMain()` in `@After`.
2. Stub every `PreferencesManager` flow the `init` path calls `.first()` on.
3. If the ViewModel starts a polling loop in `init`, cancel its scope inside the
   test body — see the pitfall above.
4. Collect one-shot events with Turbine (`vm.snackbarEvent.test { … }`); assert
   `expectNoEvents()` when the point is that exactly one event fires.
```

Beachte: der eingebettete PowerShell-Block und die Gradle-Aufrufe stehen in dreifachen Code-Zäunen **innerhalb** des Markdown-Blocks oben. Beim Anlegen der Datei kommt nur der Inhalt zwischen den äußeren Zäunen hinein.

- [ ] **Step 2: Alle genannten Pfade prüfen**

Run in Git Bash:

```bash
cd "D:/Programme (x86)/BaluApp"
for f in \
  app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelVpnActionTest.kt \
  app/src/test/java/com/baluhost/android/presentation/ui/screens/vpn/VpnViewModelTest.kt \
  app/src/test/java/com/baluhost/android/data/repository/SleepConfigRepositoryTest.kt \
  app/src/main/java/com/baluhost/android/util/Base64Decoder.kt \
  app/src/main/java/com/baluhost/android/util/Clock.kt \
  app/build.gradle.kts gradle.properties ; do
  [ -f "$f" ] && echo "ok   $f" || echo "FEHLT $f"
done
grep -q "org.gradle.caching=true" gradle.properties && echo "ok   caching-Flag belegt" || echo "FEHLT caching-Flag"
grep -q "isReturnDefaultValues" app/build.gradle.kts && echo "ok   isReturnDefaultValues belegt" || echo "FEHLT isReturnDefaultValues"
[ -d app/src/androidTest ] && echo "WARNUNG androidTest existiert doch" || echo "ok   kein androidTest"
```

Erwartet: ausschließlich `ok`-Zeilen. Jede `FEHLT`-Zeile bedeutet, dass die Datei auf etwas verweist, das es nicht gibt — dann melde das, statt den Verweis zu erfinden oder still zu streichen.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/CLAUDE.md
git commit -m "docs: record the unit-test pitfalls this codebase keeps producing"
```

---

### Task 2: Die drei Dateien der Data-Schicht

**Files:**
- Create: `data/remote/CLAUDE.md`
- Create: `data/repository/CLAUDE.md`
- Create: `data/local/CLAUDE.md`

**Interfaces:**
- Consumes: nichts
- Produces: drei Dateien, auf die Task 5 in seinem Index verweist

- [ ] **Step 1: `data/remote/CLAUDE.md`**

Struktur nach dem Format aus den Global Constraints. Der Inhalt muss diese Fakten tragen — jeweils mit Begründung, nicht als nackte Regel:

- **Drei Unterverzeichnisse:** `api/` (14 Retrofit-Interfaces), `dto/` (19 Dateien plus `dto/sync/`), `interceptors/` (5).
- **Ein API-Interface je Fachbereich**, die Namen spiegeln die Server-Routen.
- **DTO-Felder tragen `@SerializedName("snake_case")` und haben Defaultwerte.** Grund: Gson lässt unbekannte Felder fallen und setzt fehlende auf den Default — ein Vertragsbruch äußert sich damit als „Feld ist `false`", nicht als Fehler. Deklariert wird nur, was die App wirklich liest.
- **`DynamicBaseUrlInterceptor` schreibt den Host zur Laufzeit um**, aus der gespeicherten Server-URL. `BuildConfig.BASE_URL` ist **nur der Rückfall** — die dort eingetragene Adresse ist regelmäßig veraltet und das ist folgenlos.
- **`ErrorInterceptor` protokolliert jeden Nicht-2xx** mit URL, Methode und geparstem Body und wirft die `IOException` unverändert weiter. Deshalb braucht kein Repository eigenes Logging für HTTP-Fehler.
- **`MobileApiFactory` existiert**, weil das Geräte-Pairing auf einen Server zeigt, der erst zur Laufzeit aus dem QR-Code bekannt wird. Ein zur Bauzeit verdrahteter Client wäre dafür der falsche.
- **Sonderfall `SleepApi.updateSleepConfig(@Body RequestBody)`:** nimmt einen vorserialisierten Body statt eines DTO, weil der Server ein explizites `null` braucht und Gsons reflektive Serialisierung Null-Felder fallen lässt. Nicht „vereinfachen".
- Dateitabelle für `api/` mit je einer Spalte „Server-Präfix" und „Zweck".
- „Adding a new API module": Interface in `api/`, DTOs in `dto/`, Bereitstellung in `di/NetworkModule.kt`.

- [ ] **Step 2: `data/repository/CLAUDE.md`**

Erforderliche Fakten:

- **13 Dateien.** Die Schnittstellen liegen in `domain/repository/`, die Implementierungen hier, gebunden in `di/RepositoryModule.kt` per `@Binds`.
- **Rückgabe ist immer `com.baluhost.android.util.Result<T>`** (`Success`/`Error`/`Loading`). Ein `when` darüber braucht `else -> {}`, weil `Loading` nie auftritt, der Compiler ihn aber kennt.
- **Fehlerabbildung:** `catch (e: HttpException)` → eine spezifische deutsche Meldung, `catch (e: Exception)` → `"Server nicht erreichbar"`. Grund: die Oberfläche muss „Server sagt nein" von „Server nicht erreichbar" unterscheiden, sonst kann sie dem Nutzer nicht sagen, ob Warten hilft.
- **`HttpException.message()` ist die HTTP-Reason-Phrase, nicht der Body** — und bei HTTP/2 leer. Wer die Servermeldung will, muss `e.response()?.errorBody()?.string()` lesen; `DeviceRepositoryImpl` und `SleepConfigRepositoryImpl` tun das.
- **Sonderfall `PowerRepositoryImpl.sendSuspend()`:** eine `IOException` gilt dort als **Erfolg**, weil der Server während der Anfrage weggeht und nie antwortet. Zusätzlich wartet der Aufruf höchstens fünf Sekunden, statt in den 120-Sekunden-Lesetimeout zu laufen.
- **Namensinkonsistenz benennen:** zwölf Dateien heißen `*RepositoryImpl.kt`, eine heißt `FileRepository.kt` ohne Suffix. Neues folgt dem `Impl`-Muster.
- Dateitabelle mit je einer Zeile pro Datei und ihrem Zuständigkeitsbereich.

- [ ] **Step 3: `data/local/CLAUDE.md`**

Erforderliche Fakten:

- **Vier Bereiche:** `database/` (Room — `BaluHostDatabase`, vier DAOs, vier Entities, Converters, ein Mapper), `datastore/PreferencesManager.kt`, `security/` (fünf Dateien), plus `cache/CachedFileDao.kt` und `PluginTranslationCache.kt`.
- **DataStore-Konvention:** Booleans werden als `"true"`/`"false"`-Strings über `stringPreferencesKey` abgelegt.
- **`PreferencesManager` gibt Flows zurück.** Wer im ViewModel-`init` `.first()` darauf aufruft, muss im Test jeden dieser Flows stubben — Verweis auf `app/src/test/CLAUDE.md`.
- **`security/` hält die sensiblen Dinge:** `SecurePreferencesManager` auf EncryptedSharedPreferences, `PinManager`, `BiometricAuthManager`, `AppLockManager`. Zugangsdaten gehören dorthin, nicht in den normalen DataStore.
- **`PluginTranslationCache` ist ein `@Singleton` im Arbeitsspeicher**, nichts wird persistiert; er wird beim Öffnen des Power-Dialogs neu gefüllt und fällt bei einem Fehlschlag auf die englischen Servertexte zurück.
- Dateitabelle, nach den vier Bereichen gegliedert.

- [ ] **Step 4: Pfade und Zahlen prüfen**

```bash
cd "D:/Programme (x86)/BaluApp/app/src/main/java/com/baluhost/android"
echo "remote gesamt: $(find data/remote -name '*.kt' | wc -l)   (erwartet 42, rekursiv inkl. dto/sync/)"
echo "api:          $(ls data/remote/api | wc -l)   (erwartet 14)"
echo "dto direkt:   $(ls data/remote/dto/*.kt | wc -l)   (erwartet 19, ohne sync/)"
echo "interceptors: $(ls data/remote/interceptors | wc -l)   (erwartet 5)"
echo "repository:   $(ls data/repository | wc -l)   (erwartet 13)"
echo "local:        $(find data/local -name '*.kt' | wc -l)   (erwartet 19)"
```

Weichen Zahlen ab, gilt die gezählte — trage sie ein und vermerke die Abweichung im Report.

Dann jeden in den drei Dateien genannten Pfad prüfen:

```bash
grep -oh '`[a-zA-Z0-9_/.]*\.kt`' data/remote/CLAUDE.md data/repository/CLAUDE.md data/local/CLAUDE.md \
  | tr -d '`' | sort -u | while read p; do
  find . -name "$(basename "$p")" | grep -q . && echo "ok   $p" || echo "FEHLT $p"
done
```

Erwartet: nur `ok`-Zeilen.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baluhost/android/data/remote/CLAUDE.md app/src/main/java/com/baluhost/android/data/repository/CLAUDE.md app/src/main/java/com/baluhost/android/data/local/CLAUDE.md
git commit -m "docs: document the data layer's conventions and their reasons"
```

---

### Task 3: Die zwei Dateien der Domain-Schicht

**Files:**
- Create: `domain/model/CLAUDE.md`
- Create: `domain/usecase/CLAUDE.md`

**Interfaces:**
- Consumes: nichts
- Produces: zwei Dateien, auf die Task 5 in seinem Index verweist

- [ ] **Step 1: `domain/model/CLAUDE.md`**

Erforderliche Fakten:

- **26 Dateien**, reine Kotlin-`data class`en und `enum`s. **Keine Framework-Typen** — kein `android.*`, kein Retrofit, kein Room. Grund: die Domänenschicht muss ohne Gerät testbar bleiben.
- **Nullbarkeit trägt Bedeutung**, und das ist der Teil, den man nicht raten kann. Mindestens diese drei ausschreiben:
  - `AlwaysAwake.until == null` heißt **permanent**, nicht „nicht gesetzt" — dieselbe Kodierung wie am Server.
  - `DesktopActionResult.sessionUnlocked` ist dreiwertig: `true`, `false` (verweigert) und `null` (der Server hat nichts dazu gesagt). Das Zusammenfalten von `null` und `false` würde eine Nutzermeldung zerstören.
  - `DesktopState.UNKNOWN` heißt „wir wissen es nicht" und führt dazu, dass die Oberfläche **gar nichts** anbietet — die falsche Hälfte anzubieten wäre schlimmer.
- **`PowerPermissions.hasAnyPermission` schließt `canUnlockSession` bewusst aus**, weil es kein eigenständiges Recht ist, sondern ein Zusatz zu `canToggleDesktop`.
- Dateitabelle, thematisch gruppiert (Dateien, Power, Sync, VPN, Benachrichtigungen, System).

- [ ] **Step 2: `domain/usecase/CLAUDE.md`**

Erforderliche Fakten:

- **55 Dateien in zehn Unterverzeichnissen:** `power/` (11), `system/` (9), `notification/` (8), `files/` (7), `shares/` (6), `vpn/` (4), `plugin/` (3), `activity/`, `auth/`, `cache/` (je 2).
- **Form:** `class X @Inject constructor(private val repo: …)` mit `suspend operator fun invoke(...)`. Hilt stellt sie automatisch bereit — **keine Änderung an einem DI-Modul nötig**.
- **Meistens dünne Durchreicher** zu einem Repository. Logik gehört ins Repository oder ins ViewModel, nicht hierher.
- **Die dokumentierte Ausnahme:** die UseCases in `plugin/` sprechen `PluginApi` direkt an, ohne Repository. Präzedenz ist `system/GetEnergyDashboardUseCase`. Das kehrt die Abhängigkeitsregel um, die der Rest der Schicht befolgt, und ist eine bewusste Entscheidung — wer ein `PluginRepository` einführt, sollte zuerst diese verschieben.
- **`IsGamingModeAvailableUseCase` gibt `Boolean` zurück, kein `Result`**, und schluckt Fehler. Grund: ein Fehlschlag heißt „lässt sich nicht feststellen", und die einzig sinnvolle Antwort darauf ist, den Eintrag auszublenden.
- Tabelle der zehn Unterverzeichnisse mit Dateizahl und Zweck.

- [ ] **Step 3: Prüfen**

```bash
cd "D:/Programme (x86)/BaluApp/app/src/main/java/com/baluhost/android"
echo "model:   $(find domain/model -name '*.kt' | wc -l)   (erwartet 26)"
echo "usecase: $(find domain/usecase -name '*.kt' | wc -l)   (erwartet 55)"
for d in domain/usecase/*/; do echo "  $(ls "$d" | wc -l | tr -d ' ')  $d"; done
for f in domain/model/AlwaysAwake.kt domain/model/DesktopActionResult.kt domain/model/DesktopState.kt domain/model/PowerPermissions.kt domain/usecase/plugin/IsGamingModeAvailableUseCase.kt domain/usecase/system/GetEnergyDashboardUseCase.kt; do
  [ -f "$f" ] && echo "ok   $f" || echo "FEHLT $f"
done
```

Erwartet: die Zahlen stimmen und nur `ok`-Zeilen.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/baluhost/android/domain/model/CLAUDE.md app/src/main/java/com/baluhost/android/domain/usecase/CLAUDE.md
git commit -m "docs: document the domain layer, including what null means where"
```

---

### Task 4: Präsentationsschicht und DI

**Files:**
- Create: `presentation/ui/CLAUDE.md`
- Create: `di/CLAUDE.md`

**Interfaces:**
- Consumes: nichts
- Produces: zwei Dateien, auf die Task 5 in seinem Index verweist

- [ ] **Step 1: `presentation/ui/CLAUDE.md`**

Erforderliche Fakten:

- **Drei Bereiche:** `screens/` (18 Feature-Verzeichnisse), `components/` (13), `theme/` (3).
- **Screen und ViewModel liegen zusammen** im Feature-Verzeichnis, etwa `screens/dashboard/DashboardScreen.kt` neben `DashboardViewModel.kt`. Das eine Verzeichnis `presentation/viewmodel/` mit einer einzelnen Datei ist eine Altlast — Neues gehört neben seinen Screen.
- **Navigation** (das Verzeichnis bekommt keine eigene Datei) besteht aus drei Punkten, die alle drei angefasst werden müssen: ein Routen-Objekt in `presentation/navigation/Screen.kt`, ein `composable`-Eintrag in `NavGraph.kt`, und — für alles unterhalb der Bottom-Navigation — ein durchgereichter Callback in `screens/main/MainScreen.kt`. Fehlt einer, kompiliert es und der Bildschirm ist unerreichbar.
- **Nutzertexte sind deutsche Literale im Code.** Es gibt **kein** i18n-Framework; `stringResource` wird nicht verwendet.
- **ViewModel-Muster:** `StateFlow` für Zustand, `MutableSharedFlow<String>(extraBufferCapacity = 1)` für Snackbar-Ereignisse. Bei einer Aktion **genau eine** Emission — zwei aufeinanderfolgende Snackbars für eine Nutzeraktion sind schlechte Bedienung, und eine zweite `emit` würde zudem so lange aussetzen, bis die erste eingesammelt ist.
- **Der Hintergrund kommt von `BaluBackground`**, mit `Scaffold(containerColor = Color.Transparent)`. Wer stattdessen eine Volltonfarbe setzt, bekommt einen Bildschirm, der neben allen anderen flach wirkt.
- **Snackbars müssen eingefärbt werden.** Das Theme ist fest auf `DarkColorScheme` gesetzt, dessen `inverseSurface` hell ist — der M3-Standard-Snackbar malt sonst eine fast weiße Leiste auf dunklen Grund. Vorbild: `screens/settings/FritzBoxSettingsScreen.kt`.
- **Zeilen in einer `Row` wachsen nicht mit.** Vier `OutlinedButton` nebeneinander überlaufen ein 360dp-Gerät; `horizontalScroll(rememberScrollState())` ist das etablierte Mittel (siehe `screens/detail/PowerDetailScreen.kt`).
- Tabelle der 18 Screen-Verzeichnisse mit je einem Satz.

- [ ] **Step 2: `di/CLAUDE.md`**

Erforderliche Fakten:

- **Sieben Module**, alle `@InstallIn(SingletonComponent::class)`.
- **`@Provides` gegen `@Binds`:** `AppModule` ist ein `object` mit `@Provides` (für Dinge, die konstruiert werden müssen — `NetworkMonitor`, `Clock`, `Base64Decoder`, `MobileApiFactory`), `RepositoryModule` ist eine `abstract class` mit `@Binds` (für Schnittstelle-auf-Implementierung, was billiger ist und keinen Rumpf braucht).
- **UseCases brauchen kein Modul.** `@Inject constructor` genügt, Hilt findet sie.
- **`NetworkModule` stellt Retrofit und sämtliche API-Interfaces bereit.** Ein neues API-Interface braucht dort eine `@Provides`-Funktion, sonst schlägt erst `assembleDebug` fehl, nicht die Unit-Tests — der Hilt-Graph wird beim APK-Bau aufgelöst.
- Tabelle der sieben Module mit Zweck und Stil (`@Provides` oder `@Binds`).

- [ ] **Step 3: Prüfen**

```bash
cd "D:/Programme (x86)/BaluApp/app/src/main/java/com/baluhost/android"
echo "screens:    $(ls presentation/ui/screens | wc -l)   (erwartet 18)"
echo "components: $(ls presentation/ui/components | wc -l)   (erwartet 13)"
echo "theme:      $(ls presentation/ui/theme | wc -l)   (erwartet 3)"
echo "di:         $(ls di | wc -l)   (erwartet 7)"
for f in presentation/navigation/Screen.kt presentation/navigation/NavGraph.kt presentation/ui/screens/main/MainScreen.kt presentation/ui/screens/settings/FritzBoxSettingsScreen.kt presentation/ui/screens/detail/PowerDetailScreen.kt di/AppModule.kt di/RepositoryModule.kt di/NetworkModule.kt; do
  [ -f "$f" ] && echo "ok   $f" || echo "FEHLT $f"
done
grep -q "DarkColorScheme" presentation/ui/theme/Theme.kt && echo "ok   DarkColorScheme belegt" || echo "FEHLT DarkColorScheme"
```

Erwartet: die Zahlen stimmen und nur `ok`-Zeilen.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/baluhost/android/presentation/ui/CLAUDE.md app/src/main/java/com/baluhost/android/di/CLAUDE.md
git commit -m "docs: document the UI layer and the Hilt modules"
```

---

### Task 5: Die Root-CLAUDE.md

Zuletzt, damit ihr Index auf Dateien zeigt, die bereits existieren.

**Files:**
- Create: `CLAUDE.md` (im Wurzelverzeichnis)

**Interfaces:**
- Consumes: die acht Dateien aus den Tasks 1 bis 4
- Produces: nichts

- [ ] **Step 1: Datei anlegen**

`CLAUDE.md` im Wurzelverzeichnis, englisch, mit diesen Abschnitten:

**`# CLAUDE.md`** und der Satz, dass die Datei Claude Code beim Arbeiten in diesem Repo führt.

**`## Side findings`** — die aus BaluHost übernommene Regel: taucht bei einer Änderung ein Fund auf, der **nicht** zum aktuellen Task gehört (ein vorbestehender Bug, eine Altlast, ein latentes Risiko, eine sinnvolle Folgearbeit), dann weder still mitfixen noch verschweigen, sondern (1) kurz benennen — Problem, Fundort als `file:line`, warum außerhalb des Umfangs —, (2) **fragen**, ob ein GitHub-Issue angelegt werden soll, und (3) bei Zustimmung eines mit Titel, Beschreibung, Fundort und Fix-Vorschlag anlegen und die Nummer zurückmelden. Ziel: solche Punkte landen im Issue-Tracker statt in einer Chat-Notiz.

**`## Project Overview`** — BaluApp ist der Android-Client für den selbstgehosteten Heimserver BaluHost (`Xveyn/BaluHost`). Kotlin, Jetpack Compose, MVVM mit Clean Architecture, Hilt, Retrofit, Room, WorkManager. `minSdk` 26. Der Server ist ein eigenes Repo; Änderungen an API-Verträgen gehören dorthin.

**`## Architecture`** — ein Baum von `app/src/main/java/com/baluhost/android/` mit einem Satz je Verzeichnis. Hier werden auch die fünf Verzeichnisse ohne eigene CLAUDE.md untergebracht: `data/sync` (10 Dateien, Sync-Orchestrierung), `data/worker` (9, WorkManager-Worker), `data/notification` (5, FCM und WebSocket-Benachrichtigungen), `util` (14, Querschnittshelfer — `Clock`, `Base64Decoder`, `NetworkStateManager`, `Result`).

**`## Building and testing`** — mit dem Cache-Fallstrick **prominent**: `./gradlew testDebugUnitTest` kann `FROM-CACHE` melden, ohne einen Test auszuführen; verifiziert wird mit `cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`, und Gradle druckt bei Erfolg keine Testzahl. Verweis auf `app/src/test/CLAUDE.md` für den Rest. Dazu `assembleDebug` und der Hinweis, dass `assembleRelease` Dinge fängt, die Unit-Tests nicht sehen.

**`## Releasing`** — zwei Sätze und ein Verweis auf den Abschnitt „Releasing" in der `README.md`; der Ablauf wird nicht doppelt gepflegt.

**`## Known structural inconsistencies`** — die drei Warzen, jeweils mit der Angabe, wo Neues hingehört:
- `service/vpn/` und `services/BaluFirebaseMessagingService.kt` existieren beide.
- `domain/repo/LocalStorageRepository.kt` steht neben `domain/repository/` mit zwölf Dateien. **Neues gehört nach `domain/repository/`.**
- `presentation/viewmodel/` enthält eine einzelne Datei, während ViewModels sonst neben ihren Screens liegen. **Neue ViewModels gehören neben ihren Screen.**

Dazu der Satz, warum sie hier stehen: eine Beschreibung, die ihre Ausnahmen verschweigt, ist eine Falle für den, der die nächste Datei anlegt.

**`## Directory guides`** — eine Liste der acht Verzeichnisdateien mit je einem Halbsatz, plus dem Hinweis aus BaluHost: **beim Hinzufügen oder Entfernen von Dateien und beim Ändern von Mustern mitpflegen.**

**Bewusst nicht aufnehmen** — drei Dinge aus BaluHosts Root-Datei wurden begründet abgewählt, sie sind kein Versehen und dürfen nicht ergänzt werden:
- Der **vectordb-MCP-Abschnitt**. Dieses Repo hat den Server nicht; ihn zu beschreiben würde eine Fähigkeit dokumentieren, die es nicht gibt.
- **„Quick Reference: Finding Things"** und **„Contact & Support"**. Beide müssten bei jedem Umbau mitgepflegt werden.
- Die **elf losen Markdown-Dateien im Wurzelverzeichnis** (`ANALYSIS_SUMMARY.md`, `IMPLEMENTIERUNGS_PLAN.md` und weitere) werden nicht aufgeräumt und nicht einzeln aufgeführt — eigenes Thema.

- [ ] **Step 2: Den Index gegen die Wirklichkeit prüfen**

```bash
cd "D:/Programme (x86)/BaluApp"
for f in \
  app/src/test/CLAUDE.md \
  app/src/main/java/com/baluhost/android/data/remote/CLAUDE.md \
  app/src/main/java/com/baluhost/android/data/repository/CLAUDE.md \
  app/src/main/java/com/baluhost/android/data/local/CLAUDE.md \
  app/src/main/java/com/baluhost/android/domain/model/CLAUDE.md \
  app/src/main/java/com/baluhost/android/domain/usecase/CLAUDE.md \
  app/src/main/java/com/baluhost/android/presentation/ui/CLAUDE.md \
  app/src/main/java/com/baluhost/android/di/CLAUDE.md ; do
  [ -f "$f" ] && echo "ok   $f" || echo "FEHLT $f"
done
echo "Gefundene CLAUDE.md gesamt: $(find . -name CLAUDE.md -not -path './.git/*' | wc -l)   (erwartet 9)"
```

Erwartet: acht `ok`-Zeilen und die Gesamtzahl 9.

Prüfe zusätzlich, dass die drei Warzen tatsächlich noch bestehen — sie zu dokumentieren, nachdem sie behoben wurden, wäre so falsch wie sie zu verschweigen:

```bash
cd "D:/Programme (x86)/BaluApp/app/src/main/java/com/baluhost/android"
[ -d service ] && [ -d services ] && echo "ok   service/ und services/ existieren beide" || echo "PRUEFEN service/services"
[ -f domain/repo/LocalStorageRepository.kt ] && echo "ok   domain/repo/ existiert" || echo "PRUEFEN domain/repo"
[ -d presentation/viewmodel ] && echo "ok   presentation/viewmodel/ existiert" || echo "PRUEFEN presentation/viewmodel"
```

- [ ] **Step 3: Sicherstellen, dass kein Code angefasst wurde**

```bash
cd "D:/Programme (x86)/BaluApp"
git diff --stat HEAD~4 HEAD -- '*.kt' '*.kts' '*.yml' '*.properties'
```

Erwartet: **keine Ausgabe**. Dieser Plan ist reine Dokumentation; jede Zeile hier wäre ein Fehler.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add the root CLAUDE.md with the architecture and its known warts"
```

---

## Nach Abschluss

Der Plan ist erledigt, wenn neun CLAUDE.md-Dateien existieren, jeder darin genannte Pfad auf eine vorhandene Datei zeigt, jede Zahl gezählt ist, und `git diff` über die fünf Commits keine Code-Datei berührt.

Es gibt **keine** manuelle Abnahme durch den Menschen — Dokumentation lässt sich nicht ausprobieren. Ihr Wert zeigt sich erst beim nächsten Mal, wenn jemand in einem dieser Verzeichnisse arbeitet und den Fallstrick **nicht** erneut erlebt.
