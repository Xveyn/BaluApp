# Testsuite-Restliste (nach Task 1: forkEvery=1)

Gemessen am 2026-07-31 mit `.\gradlew.bat testDebugUnitTest --console=plain` (Windows PowerShell, repo root), Konfiguration aus Task 1 (`forkEvery = 1` in `app/build.gradle.kts`).

## Ergebnis

```
102 tests completed, 27 failed
```

Deckt sich mit der Erwartung aus Task 1 (zwei Läufe, beide 102/27, identische Verteilung über die Klassen). Dieser dritte Lauf bestätigt beide Vorläufer: gleiche Zahl, gleiche Klassen, gleiche Testnamen. Keine neue Varianz beobachtet.

| Klasse | Fehlschläge |
|---|---|
| RegisterDeviceUseCaseTest | 2 |
| DeleteFileUseCaseTest | 1 |
| GetFilesUseCaseTest | 1 |
| ImportVpnConfigUseCaseTest | 4 |
| FilesViewModelTest | 9 |
| SettingsViewModelNetworkTest | 2 |
| VpnViewModelTest | 8 (7 OutOfMemoryError + 1 AssertionError) |
| **Summe** | **27** |

## Fehlschläge im Detail

### Task 3 — FilesViewModelTest (Setup-Fehler, `NoSuchElementException: Expected at least one element`)

Alle fünf Fälle werfen denselben Fehler an derselben Stelle: `FilesViewModel$checkAuthenticationAndLoadFiles$1.invokeSuspend(FilesViewModel.kt:190)` → `kotlinx.coroutines.flow.FlowKt.first` auf einem leeren Flow.

1. `clearError should remove error message` — `java.util.NoSuchElementException: Expected at least one element` (FilesViewModelTest.kt:306)
2. `navigateBack should return false at root` — `java.util.NoSuchElementException: Expected at least one element` (FilesViewModelTest.kt:198)
3. `navigateBack should return to previous path` — `java.util.NoSuchElementException: Expected at least one element` (FilesViewModelTest.kt:166)
4. `navigateToFolder should update path and load files` — `java.util.NoSuchElementException: Expected at least one element` (FilesViewModelTest.kt:139)
5. `loadFiles should update state with file list` — `java.util.NoSuchElementException: Expected at least one element` (FilesViewModelTest.kt:112)

### Task 4 — SettingsViewModelNetworkTest (`UncaughtExceptionsBeforeTest`)

1. `setHomeNetwork shows error when BSSID is null` — `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest: There were uncaught exceptions before the test started...` (SettingsViewModelNetworkTest.kt:82), verursacht durch `io.mockk.MockKException: no answer found for NetworkMonitor(#23).isWifiConnected() among the configured answers: (NetworkMonitor(#23).isCurrentlyWifiConnected()))` in `setup()` (SettingsViewModelNetworkTest.kt:61)
2. `toggleAutoVpn saves preference` — `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest: There were uncaught exceptions before the test started...` (SettingsViewModelNetworkTest.kt:93), gleiche Ursache: `io.mockk.MockKException: no answer found for NetworkMonitor(#41).isWifiConnected()...` in `setup()`

### Task 5 — DeleteFileUseCaseTest, GetFilesUseCaseTest, FilesViewModelTest-Textdrift

1. `DeleteFileUseCaseTest > invoke should return error when file not found` — `org.junit.ComparisonFailure: expected:<[]File not found> but was:<[Delete failed: ]File not found>` (DeleteFileUseCaseTest.kt:70)
2. `GetFilesUseCaseTest > invoke should return error when repository call fails` — `java.lang.AssertionError` (bare `assertTrue` failure, GetFilesUseCaseTest.kt:106)
3. `FilesViewModelTest > loadFiles should set error state on failure` — `org.junit.ComparisonFailure: expected:<[Network erro]r> but was:<[Keine Verbindung zum Serve]r>` (FilesViewModelTest.kt:281) — Textdrift wie in der Spec erwartet. Hat zusätzlich denselben unterdrückten `NoSuchElementException` im Hintergrund wie die Task-3-Fälle (siehe Anmerkung unten).

### Task 6 — ImportVpnConfigUseCaseTest

1. `invoke should parse and save VPN config successfully` — `java.lang.AssertionError` (bare `assertTrue`, ImportVpnConfigUseCaseTest.kt:31)
2. `invoke should handle minimal config correctly` — `java.lang.AssertionError` (bare `assertTrue`, ImportVpnConfigUseCaseTest.kt:69)
3. `invoke should succeed with empty fields for malformed config` — `java.lang.AssertionError` (bare `assertTrue`, ImportVpnConfigUseCaseTest.kt:111)
4. `invoke should handle config with multiple peers` — `java.lang.AssertionError` (bare `assertTrue`, ImportVpnConfigUseCaseTest.kt:128)

(`invoke should return error for invalid base64` ist grün — 5 Tests in der Klasse, 4 Fehlschläge.)

### Task 7 — RegisterDeviceUseCaseTest

1. `invoke should return success when registration is successful` — `java.lang.AssertionError` (bare `assertTrue`, RegisterDeviceUseCaseTest.kt:86)
2. `invoke should return error when API call fails` — `java.lang.AssertionError` (bare `assertTrue`, RegisterDeviceUseCaseTest.kt:119)

(`invoke should handle null user data gracefully` ist grün — 3 Tests in der Klasse, 2 Fehlschläge.)

### Task 8 — VpnViewModelTest (7 OutOfMemoryError + 1 AssertionError)

1. `disconnect should transition to disconnected state on success` — `java.lang.OutOfMemoryError: Java heap space` (Kotlin-Reflection-Metadaten-Parsing, `Collections.java:1479`)
2. `connect should show error on failure` — `java.lang.OutOfMemoryError: Java heap space`
3. `should not connect or disconnect while loading` — `java.lang.OutOfMemoryError: Java heap space`
4. `disconnect should do nothing when already disconnected` — `java.lang.OutOfMemoryError: Java heap space`
5. `initial state should check for VPN config` — `java.lang.OutOfMemoryError: Java heap space`
6. `connect should transition to connected state on success` — `java.lang.OutOfMemoryError: Java heap space`
7. `connect should do nothing when already connected` — `java.lang.OutOfMemoryError: Java heap space`
8. `initial state should show no config when missing` — `java.lang.AssertionError: expected:<Keine VPN-Konfiguration gefunden> but was:<null>` (VpnViewModelTest.kt:83), mit unterdrücktem `io.mockk.MockKException: no answer found for PreferencesManager(#34).getVpnType() among the configured answers: (PreferencesManager(#34).getVpnConfig())` und einem weiteren unterdrückten `OutOfMemoryError`.

Laut Auftrag ist dies laut Task-1-Befund (identisches Scheitern bei 512m/1g/2g/4g) ein Testklassen-Defekt, kein Speicher-Dimensionierungsproblem — daher jetzt vollständig Task 8 statt teilweise ungeklärt.

## Nicht eindeutig zuzuordnende Fehlschläge (Step 3)

Drei der neun `FilesViewModelTest`-Fehlschläge passen nicht wörtlich in eines der oben genannten Muster (weder `NoSuchElementException` noch die genannte Textdrift), teilen aber laut Stacktrace dieselbe Grundursache wie die fünf Task-3-Fälle: einen unterdrückten `java.util.NoSuchElementException: Expected at least one element` aus `FilesViewModel$checkAuthenticationAndLoadFiles$1` (FilesViewModel.kt:190).

1. `deleteFile should refresh list on success` — primärer Fehler `app.cash.turbine.TurbineAssertionError: No value produced in 3s`, verursacht durch `app.cash.turbine.TurbineTimeoutCancellationException: Timed out waiting for 3s`; unterdrückt: `java.util.NoSuchElementException: Expected at least one element` (dieselbe Stelle wie Task 3).
2. `initial state should load files at root` — primärer Fehler `java.lang.AssertionError: expected:<2> but was:<0>` (FilesViewModelTest.kt:77); im Stacktrace ebenfalls der unterdrückte `NoSuchElementException` an derselben Stelle.
3. `uploadFile should track progress and refresh on success` — primärer Fehler `java.lang.AssertionError` (bare `assertTrue`, FilesViewModelTest.kt:210); im Stacktrace ebenfalls der unterdrückte `NoSuchElementException` an derselben Stelle.

Einschätzung: alle drei sehen wie Folgeschäden desselben Setup-Defekts aus, den Task 3 laut Auftrag beheben soll (leerer Flow beim Auth-Check). Die Task-Beschreibung nennt aber nur das `NoSuchElementException`-Muster wörtlich, nicht diese drei abweichenden Fehlerbilder. Da der Plan nicht ausdrücklich sagt, ob Task 3 auch diese drei mit reparieren soll, wird hier nichts repariert — der Controller muss entscheiden, ob sie stillschweigend zu Task 3 gehören oder eine eigene Behandlung brauchen.

Für `VpnViewModelTest > initial state should show no config when missing` (aus der Spec als möglicherweise unzuzuordnen genannt) gilt: laut aktuellem Auftrag deckt das neu hinzugefügte Task 8 diesen Fall inklusive der 7 OutOfMemoryError-Fälle vollständig ab — kein offener Punkt mehr.
