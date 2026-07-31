# CLAUDE.md-Dokumentation für BaluApp

**Datum:** 2026-07-31
**Status:** Design abgenommen

## Problem

BaluApp hat **keine einzige CLAUDE.md**. Das Server-Repo BaluHost hat 17 — eine
im Wurzelverzeichnis und 16 in den Paketen —, und die tragen dort das Wissen, das
sich weder aus dem Code noch aus der Git-Historie ableiten lässt: warum eine
Konvention so ist, wo Neues hingehört, welche Fallstricke schon jemand erlitten
hat.

In dieser App fehlt das vollständig. Wer hier arbeitet, erarbeitet sich dieselben
Erkenntnisse jedes Mal neu — was in dieser Sitzung nachweislich passiert ist:
ein Gradle-Cache, der Tests als bestanden meldet, ohne sie auszuführen, hat
mehrere Stunden gekostet, bevor er als solcher erkannt wurde.

## Ausgangslage

Geprüft, nicht angenommen:

- **Keine CLAUDE.md**, kein `.claude/rules/`. `.claude/settings.local.json`
  enthält ausschließlich Berechtigungen.
- **Kein vectordb-MCP.** BaluHosts Root-Datei widmet dem einen ganzen Abschnitt;
  hier gibt es ihn nicht, und ihn zu übernehmen würde eine Fähigkeit
  dokumentieren, die nicht existiert.
- Paketstruktur unter `app/src/main/java/com/baluhost/android/`:
  `data/` (101 Kotlin-Dateien), `domain/` (96), `presentation/` (71), `util/`
  (14), `di/` (7), `service/` (1), `services/` (1).
- Testverzeichnis `app/src/test/`: 23 Dateien.
- **Drei strukturelle Inkonsistenzen**, die eine ehrliche Beschreibung nennen
  muss:
  - `service/vpn/` **und** `services/BaluFirebaseMessagingService.kt`
  - `domain/repo/LocalStorageRepository.kt` **und** `domain/repository/` (12 Dateien)
  - `presentation/viewmodel/` mit einer Datei, während ViewModels sonst neben
    ihren Screens liegen

## Vorbild: das Format aus BaluHost

Die dortigen Verzeichnisdateien folgen durchgängig einem Muster (siehe
`client/src/api/CLAUDE.md`, 64 Zeilen):

1. Titel und ein Absatz: was liegt hier und wozu
2. Kernbegriffe oder Basisbausteine
3. **Konventionen als Aufzählung, jeweils mit Begründung**
4. Dateitabelle
5. „So fügst du etwas Neues hinzu" als nummerierte Schritte

Punkt 3 ist der eigentliche Wert. „Repositories geben `Result<T>` zurück" ist
eine Regel, die man auch am Code ablesen kann. „…weil die Oberfläche zwischen
‚Server nicht erreichbar' und ‚Server sagt nein' unterscheiden muss" ist der
Grund, und nur der verhindert die nächste Abkürzung.

## Umfang: neun Dateien

Die Dateien liegen **in den Paketen selbst**, nicht gesammelt unter `docs/` —
Claude Code liest die CLAUDE.md des Verzeichnisses mit, in dem gearbeitet wird.

| Datei | Deckt ab |
|---|---|
| `CLAUDE.md` | Root: Überblick, Architektur, Bau- und Testbefehle, Warzen, Index |
| `app/src/main/java/com/baluhost/android/data/remote/CLAUDE.md` | 42 Dateien: Retrofit-APIs, DTOs, Interceptors |
| `.../data/repository/CLAUDE.md` | 13: `Result`-Muster, Fehlerabbildung |
| `.../data/local/CLAUDE.md` | 19: DataStore, verschlüsselte Ablage, App-Lock |
| `.../domain/model/CLAUDE.md` | 26: Domänentypen und was `null` jeweils bedeutet |
| `.../domain/usecase/CLAUDE.md` | 55: Aufbau, wann ein UseCase direkt eine API nutzt |
| `.../presentation/ui/CLAUDE.md` | 67: Screens, ViewModels, Theme, Navigation |
| `.../di/CLAUDE.md` | 7: Hilt-Module, `@Binds` gegen `@Provides` |
| `app/src/test/CLAUDE.md` | 23: die Fallstricke |

Fünf Verzeichnisse bekommen **keine** eigene Datei, sondern werden namentlich in
einer der neun untergebracht — bei zwei bis zehn Dateien trägt ein eigenes
Dokument wenig, und ungepflegte Dokumentation ist schlechter als keine:

| Verzeichnis | Dateien | Wird beschrieben in |
|---|---|---|
| `data/sync` | 10 | Root, Abschnitt „Architecture" |
| `data/worker` | 9 | Root, Abschnitt „Architecture" |
| `data/notification` | 5 | Root, Abschnitt „Architecture" |
| `util` | 14 | Root, Abschnitt „Architecture" |
| `presentation/navigation` | 2 | `presentation/ui/CLAUDE.md` — Navigation gehört zur UI-Schicht |

Jeweils mit einem Satz, was dort liegt, und dem Verweis auf die konkreten
Einstiegsdateien. Das reicht, damit niemand danach suchen muss.

## Die Fallstricke — der eigentliche Ertrag

`app/src/test/CLAUDE.md` hält fest, was diese Sitzung erarbeitet hat. Jeder
Punkt hat konkret Zeit gekostet:

**Der Cache lügt.** `gradle.properties` setzt `org.gradle.caching=true`. Ein
`gradlew testDebugUnitTest` meldet dann `BUILD SUCCESSFUL in 1s` mit
`FROM-CACHE` oder `UP-TO-DATE`, **ohne einen einzigen Test auszuführen**.
Verifiziert wird ausschließlich mit
`cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`, und weil Gradle bei
Erfolg **keine** Testzahl druckt, wird über die XML-Ergebnisse gezählt.

**Relaxte Mocks liefern leere Flows.** `mockk(relaxed = true)` auf
`PreferencesManager` gibt für Flow-Rückgaben einen leeren Flow zurück. Ruft ein
ViewModel-`init` darauf `.first()` auf, fliegt `NoSuchElementException`, bevor
der Testkörper beginnt. Vorbild mit erklärendem Kommentar:
`DashboardViewModelVpnActionTest`.

**Unbegrenzte Polling-Schleifen lassen `runTest{}` nie enden.** `VpnViewModel`
und `DashboardViewModel` starten in `init` Schleifen der Form
`while (true) { delay(n); … }`. Läuft `Dispatchers.setMain(testDispatcher)` in
`@Before`, übernimmt `runTest` denselben Scheduler; sein abschließender
„advance to idle" terminiert dann nie und häuft Allokationen an, bis der Heap
ausgeht. Der Stacktrace zeigt `kotlin.reflect…ProtoBuf` und führt in die Irre —
das ist nur die zufällig laufende Allokation, nicht die Ursache. Gegenmittel:
den `viewModelScope` **innerhalb** des Testkörpers abbrechen, per
`ViewModelStore.put()/clear()` (siehe `VpnViewModelTest`) oder per reflektivem
`clearViewModel()` (siehe `DashboardViewModelVpnActionTest`).

**`android.util.*` liefert `null` im JVM-Test.** `isReturnDefaultValues = true`
in `app/build.gradle.kts` macht etwa `Base64.decode(...)` zu `null`.
Produktivcode, der Framework-Klassen direkt aufruft, ist damit **nicht**
unit-testbar; die Abhängigkeit gehört hinter ein Interface. Beispiel:
`Base64Decoder` und `AndroidBase64Decoder` in `util/`.

**Eine injizierbare Uhr für Zeitgrenzen.** Wer `Instant.now()` fest verdrahtet,
kann Grenzfälle wie „genau fünf Minuten" nicht prüfen, ohne gegen die
Systemuhr zu rennen. Siehe `util/Clock.kt`.

**PowerShell zerlegt `-PappVersionName=1.2.3` am Punkt.** Solche Argumente
gehören in Anführungszeichen: `"-PappVersionName=1.2.3"`.

Der Cache-Punkt steht **zusätzlich** in der Root-Datei, weil er jeden Befehl
betrifft, den irgendwer eintippt — nicht nur Arbeit im Testverzeichnis.

## Strukturelle Inkonsistenzen werden benannt

Die Root-Datei bekommt einen Abschnitt „Known structural inconsistencies" mit
den drei oben gelisteten Punkten und jeweils der Angabe, wo Neues hingehört
(`domain/repository/`, nicht `domain/repo/`; ViewModels neben ihren Screens,
nicht in `presentation/viewmodel/`).

Aufräumen ist **nicht** Teil dieser Arbeit. Aber wer eine Struktur beschreibt und
ihre Ausnahmen verschweigt, baut eine Falle: die nächste Datei landet dann im
falschen der beiden Verzeichnisse, und die Inkonsistenz wächst.

## Nebenbefunde

Aus BaluHost übernommen, in die Root-Datei: taucht bei einer Änderung ein Fund
auf, der **nicht** zum aktuellen Task gehört — ein vorbestehender Bug, eine
Altlast, ein latentes Risiko —, dann weder still mitfixen noch verschweigen,
sondern benennen (Problem, Fundort als `file:line`, warum außerhalb des Umfangs)
und **fragen**, ob ein GitHub-Issue angelegt werden soll.

In dieser Sitzung wären das etwa gewesen: der tote verschachtelte
`onAppResume()`-Block in `FilesViewModel`, die zwei doppelten Paketverzeichnisse,
und dass `VpnRepositoryImpl` weiterhin direkt `android.util.Base64` aufruft und
damit aus demselben Grund untestbar ist wie der bereits reparierte Fall.

## Sprache

Die Dateien werden **englisch** geschrieben, wie `README.md`, `CHANGELOG.md` und
sämtliche BaluHost-CLAUDE.md. Die deutschen Nutzertexte der App sind davon
unberührt.

## Verifikation

Dokumentation lässt sich nicht per Test prüfen. Belegt wird stattdessen:

- **Jede Dateiangabe existiert.** Die Tabellen nennen konkrete Dateien; jeder
  genannte Pfad wird gegen das Repo geprüft. Eine Dokumentation, die auf nicht
  existierende Dateien zeigt, ist schlimmer als keine.
- **Jede Zählangabe stimmt.** Wo „42 Dateien" steht, wird gezählt.
- **Der Index in der Root-Datei nennt alle acht Verzeichnisdateien**, und jede
  davon existiert.
- Die Testsuite bleibt unberührt bei **170 Tests, 0 Fehlern** — dieses Projekt
  ändert keine Zeile Code.

## Bewusst nicht enthalten

- **Der vectordb-MCP-Abschnitt** aus BaluHosts Root-Datei. Existiert hier nicht.
- **„Quick Reference: Finding Things"** und **„Contact & Support"**. Abgewählt:
  beide müssten bei jedem Umbau mitgepflegt werden, und die Root-Datei bleibt
  ohne sie schlanker.
- **Die elf losen Markdown-Dateien im Wurzelverzeichnis**
  (`ANALYSIS_SUMMARY.md`, `IMPLEMENTIERUNGS_PLAN.md` und weitere). Aufräumen
  wäre sinnvoll, ist aber ein eigenes Thema.
- **Jede Änderung an Code oder Paketstruktur.** Dies ist reine Dokumentation.
