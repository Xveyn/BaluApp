# Testsuite reparieren

**Datum:** 2026-07-31
**Status:** Design zur Abnahme

## Problem

`gradlew testDebugUnitTest` meldet 102 Tests, davon **36 rot** — lokal wie auf CI
(die letzten acht CI-Läufe sind allesamt `failure`). Damit ist TDD im Repo
faktisch abgeschaltet: kein Entwickler und kein Agent kann „Suite grün" als
Abnahmekriterium verwenden, und neue Fehlschläge verschwinden im Rauschen.

Konkreter Anlass: der Plan
`docs/superpowers/plans/2026-07-31-app-desktop-toggle-gaming-mode.md` verlangt in
jeder Task „volle Suite grün" und ist deshalb pausiert.

## Vorbedingung: Gradle lief lokal überhaupt nicht

Die globale `~/.gradle/gradle.properties` setzte

```
org.gradle.java.home=C:\Users\SvenB\.jdk\jdk-21.0.8
```

In einer Java-Properties-Datei ist der Backslash ein Escape-Zeichen, also kam bei
Gradle `C:UsersSvenB.jdkjdk-21.0.8` an und der Start brach ab. Bereits behoben
durch Umstellung auf Forward Slashes; Backup liegt als
`~/.gradle/gradle.properties.bak`. Das ist Maschinen-Konfiguration außerhalb des
Repos und nicht Teil dieses Projekts — hier nur dokumentiert, damit der Befund
nicht verlorengeht.

## Befund

Alle acht betroffenen Testklassen wurden einzeln laufen gelassen. Ergebnis:
**sieben von acht fallen auch isoliert um** — es handelt sich also überwiegend um
echte Defekte, nicht um Wechselwirkungen zwischen Tests. Einzig
`NetworkStateManagerBssidTest` ist allein grün und damit reines Kollateral.

Die 36 Fehlschläge verteilen sich auf fünf Ursachen:

| Gruppe | Tests | Ursache |
|---|---|---|
| C | 15 | `OutOfMemoryError` in der Test-JVM |
| B | 8–11 | Relaxed-Mocks liefern leere Flows |
| A1 | 2 | Fehlertext im Produktivcode geändert |
| A2 | 1 | Verhalten bewusst geändert |
| A3 | 6 | Produktivcode ist nicht unit-testbar |

Die Grenze zwischen B und A ist erst nach Behebung von C exakt zu ziehen — C
verdeckt den wahren Zustand der betroffenen Klassen. Ein Fehlschlag in
`VpnViewModelTest` (`expected:<Keine VPN-Konfiguration gefunden> but was:<null>`)
ist bis dahin nicht sicher zuzuordnen.

### C — Speicher (15 Tests)

`NetworkStateManagerBssidTest` (8) und `VpnViewModelTest` (7) sterben mit
`java.lang.OutOfMemoryError: Java heap space`. Der Stacktrace zeigt jedes Mal
dieselbe Stelle: `kotlin.reflect.jvm.internal.impl.metadata.ProtoBuf` beim
Deserialisieren der Kotlin-Builtins — das ist MockKs Reflection-Pfad.

`app/build.gradle.kts` setzt **kein** `maxHeapSize`, die Gradle-Test-JVM läuft
also auf dem Default von 512 MB.

**Gegenbeweis, der beachtet werden muss:** ein Versuch mit
`testOptions.unitTests.all { it.maxHeapSize = "2g" }` machte es *schlimmer* — es
fielen mehr Tests um als vorher, darunter das bis dahin grüne
`DashboardViewModelVpnActionTest`. Der naheliegende Heap-Bump ist also **nicht**
als gesetzt anzunehmen. Zweite Hypothese, die mitgeprüft gehört: `forkEvery`, das
die Test-Klassen auf mehrere JVMs verteilt und damit die Akkumulation gar nicht
erst entstehen lässt.

> **Korrektur (nach Abschluss der Reparatur, Task 8):** Die obige Diagnose
> "MockKs Reflection-Pfad akkumuliert Kotlin-Metadaten über den Lauf hinweg"
> ist **widerlegt**. Die tatsächliche Ursache war `VpnViewModelTest`: weil
> `Dispatchers.setMain(testDispatcher)` in `@Before` läuft, übernimmt `runTest{}`
> denselben `TestCoroutineScheduler` wie `VpnViewModel`s unbegrenzte
> `while (isActive) { …; delay(3000) }`-Polling-Schleife aus `init{}`. Der
> "advance to idle"-Schritt, den `runTest{}` am Ende jedes Testkörpers ausführt,
> terminiert dadurch nie und häuft unbegrenzt Allokationen an, bis der Heap
> ausgeht — unabhängig von der konfigurierten Heap-Größe. Der
> `kotlin.reflect...ProtoBuf`-Frame im Stacktrace war nur die zufällig gerade
> laufende Allokation im Moment des OOM, nicht die Ursache. Der Fix (Task 8)
> kapselt jede in `VpnViewModelTest` konstruierte ViewModel-Instanz in einem
> `ViewModelStore` und ruft `clear()` **innerhalb** des Testkörpers auf, bevor
> `runTest{}` seinen finalen Advance ausführt — siehe
> `app/src/test/java/com/baluhost/android/presentation/ui/screens/vpn/VpnViewModelTest.kt`.
> Details: `.superpowers/sdd/2026-07-31-testsuite-reparieren/task-8-report.md`.

### B — Relaxed-Mocks liefern leere Flows (8–11 Tests)

`FilesViewModelTest` (5×) scheitert mit
`java.util.NoSuchElementException: Expected at least one element`,
`SettingsViewModelNetworkTest` (3×) mit
`kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`.

Ursache: `mockk(relaxed = true)` auf `PreferencesManager` gibt für
Flow-Rückgaben einen leeren Flow zurück. Ruft der ViewModel-`init`-Block darauf
`.first()` auf, fliegt `NoSuchElementException`, bevor der Test überhaupt beginnt.

**Das Repo kennt die Lösung bereits.**
`DashboardViewModelVpnActionTest` stubbt jeden im `init` benutzten Flow explizit
und begründet es im Kommentar:

> Stub all PreferencesManager flows called during ViewModel init to avoid
> NoSuchElementException when `.first()` is called on empty relaxed-mock flows.

Diese Klasse ist das Vorbild; die kaputten Klassen werden darauf angeglichen.
Ein weiterer Fehlschlag in `FilesViewModelTest`
(`app.cash.turbine.TurbineAssertionError: No value produced in 3s`) und zwei
nackte `AssertionError` derselben Klasse sind mutmaßlich Folgeschäden desselben
Setup-Problems — das bestätigt sich, sobald das Setup steht.

### A1 — Fehlertext geändert (2 Tests)

- `DeleteFileUseCase` verpackt Fehler inzwischen als
  `Exception("Delete failed: ${e.message}", e)`. Der Test erwartet noch den
  nackten `File not found`.
- `FilesViewModel` meldet `Keine Verbindung zum Server` statt `Network error`.

Beides sind gewollte Textumstellungen ohne Verhaltensänderung. Die Tests werden
nachgezogen.

### A2 — Fehlerschlucken in GetFilesUseCase (1 Test)

`GetFilesUseCase` fängt **jede** Exception und gibt `Result.Success(emptyList())`
zurück, mit begründendem Kommentar im Code („to avoid navigation to QR screen";
die Sichtbarkeit für den Nutzer stellt der `ServerConnectivityChecker` her).

**Entschieden:** Das Verhalten ist gewollt und bleibt. Der Test wird darauf
umgeschrieben — und sein Name benennt das Schlucken ausdrücklich, damit die
Absicht künftig aus dem Test selbst hervorgeht und nicht nur aus einem Kommentar
in der Implementierung.

### A3 — Produktivcode ist nicht unit-testbar (6 Tests)

Der eigentliche Fund. Diese Tests können nicht grün werden, egal was man an den
Assertions ändert:

**`ImportVpnConfigUseCase` (4 Tests).** Die Implementierung dekodiert mit
`android.util.Base64`. Im reinen JVM-Unit-Test greift `isReturnDefaultValues =
true` aus `app/build.gradle.kts:69-73`, die Methode liefert `null`, und
`String(null, UTF_8)` wirft — die Klasse gibt unter Test also *immer*
`Result.Error` zurück. Der Test kodiert derweil mit `java.util.Base64`, ohne
`mockkStatic` und ohne Robolectric.

**`RegisterDeviceUseCase` (2 Tests).** Die Implementierung ignoriert das
injizierte `mobileApi` vollständig und baut sich in `invoke()` ein eigenes
Retrofit auf die aus dem QR-Code stammende Server-URL. Der Mock des Tests greift
deshalb nie; der Test versucht tatsächlich, `https://test.com/api/` zu erreichen.

Das Produktivverhalten ist fachlich **richtig** — QR-Pairing zeigt naturgemäß auf
einen beliebigen Server, das deckt sich mit dem Pairing-Flow im BaluHost-Repo.
Falsch ist nur, dass die Klasse eine injizierte Abhängigkeit annimmt und dann
nicht benutzt. Eine Klasse, die ihre Abhängigkeit ignoriert, ist ein
Wartungsproblem unabhängig vom Test.

**Entschieden:** Produktivcode testbar machen.

- `ImportVpnConfigUseCase` bekommt den Base64-Decoder als injizierte
  Abhängigkeit — Android-Implementierung in Produktion, `java.util.Base64` im
  Test.
- `RegisterDeviceUseCase` bekommt eine injizierte Factory, die zu einer Server-URL
  eine `MobileApi` liefert, statt Retrofit inline zu bauen. Das dynamische
  Verhalten bleibt vollständig erhalten; das ungenutzte `mobileApi`-Feld
  verschwindet.

Danach laufen die sechs Tests weitgehend unverändert.

## Reihenfolge

C zuerst, dann neu messen, dann B, dann A. Der Grund ist nicht Bequemlichkeit:
solange die Test-JVM stirbt, sind die Ergebnisse der übrigen Klassen nicht
belastbar, und man repariert womöglich Symptome eines Speicherproblems.

1. **C — Speicher.** Hypothesen `maxHeapSize` und `forkEvery` gegeneinander
   messen. Akzeptanz: die 15 OOM-Tests laufen grün, **und** kein bis dahin grüner
   Test wird rot. Danach die Suite neu vermessen und die Restliste festschreiben.
2. **Neu messen.** Die verbleibende Fehlerliste ist die Arbeitsgrundlage für 3–5;
   sie kann von der obigen Schätzung abweichen.
3. **B — Test-Setup.** `FilesViewModelTest` und `SettingsViewModelNetworkTest` auf
   das Muster aus `DashboardViewModelVpnActionTest` bringen.
4. **A1 + A2 — Tests nachziehen.** Drei Tests auf das heutige, gewollte Verhalten.
5. **A3 — Testbarkeit herstellen.** Die zwei Refactorings, dann die sechs Tests.

Jeder Schritt endet mit einem vollen Suite-Lauf und einer festgehaltenen
Fehlerzahl, damit Fortschritt und Regressionen sichtbar bleiben.

## Akzeptanzkriterium

`gradlew testDebugUnitTest` läuft grün — lokal und auf CI. Keine
`@Ignore`-Annotationen, keine gelöschten Tests als Abkürzung.

## Bewusst nicht enthalten

- **Neue Testabdeckung.** Dieses Projekt repariert, was da ist. Lücken zu füllen
  ist eine eigene Aufgabe.
- **Robolectric einführen.** Wurde für A3 erwogen und verworfen: es hätte
  `RegisterDeviceUseCase` ohnehin nicht testbar gemacht (das echte Retrofit ginge
  weiterhin ins Netz) und verlangsamt die Suite spürbar.
- **Tests löschen.** Ebenfalls erwogen und verworfen — es kostete echte Abdeckung
  an zwei sicherheitsrelevanten Stellen, Geräte-Registrierung und VPN-Import.
- **Die CI-Konfiguration.** Der `-Xmx4g`-Parameter im Workflow adressiert die
  Daemon-JVM, nicht die Test-JVM. Ob er nach Schritt 1 noch nötig ist, wird dort
  entschieden; eine eigene CI-Überarbeitung ist es nicht.
- **`FolderSyncWorker.kt`.** Die Datei hat uncommittete Änderungen aus einer
  anderen Arbeit und wird nicht angefasst.
