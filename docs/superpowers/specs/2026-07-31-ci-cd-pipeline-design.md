# CI/CD-Pipeline überarbeiten

**Datum:** 2026-07-31
**Status:** Design abgenommen

## Problem

Die Pipeline prüft zu wenig und liefert ungeprüft aus.

`ci.yml` läuft **ausschließlich** bei einem Pull Request nach `main`
(`ci.yml:3-5`). Pushes auf `development` — der Branch, auf dem tatsächlich
gearbeitet wird — lösen nichts aus. Ein Fehler fällt erst auf, wenn jemand einen
PR öffnet.

`release.yml` läuft bei jedem Push auf `main` (`release.yml:3-5`) und **führt
keinen einzigen Test aus**. Es baut die signierte Release-APK und veröffentlicht
ein GitHub-Release. Der Testlauf existiert also nur als PR-Gate; wer direkt auf
`main` pusht, veröffentlicht ungeprüft.

Dazu kommt: Releases sollen künftig an Tags hängen (major/minor/patch), nicht an
jedem Push auf `main`.

## Ausgangslage

Geprüft, nicht angenommen:

- **Es gibt keinen einzigen Git-Tag und kein einziges GitHub-Release.** Der
  Release-Workflow hat noch nie etwas ausgeliefert. Wir stellen also keinen
  laufenden Prozess um, sondern richten ihn erstmalig ein.
- Die bisherigen Fehlschläge auf `main` waren `push`-Events, also `release.yml`.
  Zu dem Zeitpunkt stand `org.gradle.java.home=C:\\Users\\SvenB\\.jdk\\jdk-21.0.8`
  in `gradle.properties` — auf einem Ubuntu-Runner existiert der Pfad nicht.
  Behoben durch Commit „fix(ci): remove local java.home from gradle.properties".
- Die `pull_request`-Fehlschläge davor waren die kaputte Testsuite. Die ist
  repariert; die Suite steht bei 170 Tests, 0 Fehlern.
- `app/build.gradle.kts:19-20` hat `versionCode = 1` und `versionName = "1.0.0"`
  fest verdrahtet. `release.yml:42-45` schreibt den `versionCode` per `sed` um.
- Es gibt eine `README.md`, aber **keine `CHANGELOG.md`**. Im Wurzelverzeichnis
  liegen elf weitere lose Markdown-Dateien.
- Das Projekt hat **kein `androidTest`-Sourceset**.

## Designentscheidungen

**Der Tag ist die Wahrheit für die Version.** `versionName` und `versionCode`
werden aus dem Tag abgeleitet und zur Buildzeit injiziert. Nur eine Stelle zu
pflegen, kein Vergessen möglich, und der `versionCode` wächst garantiert
monoton — was Android und der Play Store verlangen. Die Werte in
`build.gradle.kts` bleiben als Rückfall für lokale Builds stehen.

**Der CHANGELOG-Eintrag wird nur beim Release geprüft**, nicht bei jedem PR.
Kein Reibungsverlust im Alltag, aber kein Release ohne dokumentierte Änderungen.
Der Abschnitt liefert zugleich die Release-Notes, die damit im Repo stehen statt
nur auf GitHub.

**Der Release-Workflow prüft sich selbst vollständig.** Er verlässt sich nicht
darauf, dass die CI den Commit schon gesehen hat — ein Tag kann auf jedem Commit
sitzen, auch auf einem, der nie durch einen PR ging.

## Die zwei Workflows

`ci.yml` ist das Prüfnetz: läuft oft, blockiert nichts außer fehlerhaftem Code.
`release.yml` ist der Auslieferungsweg: läuft selten, nur auf einen Tag hin.

### `ci.yml`

**Auslöser** wird erweitert um `push` auf `development`, zusätzlich zum
bestehenden `pull_request` nach `main`.

**Schritte:** JDK 21 → `setup-gradle` → `google-services.json` dekodieren →
Tests → `assembleDebug`.

Drei Korrekturen am Bestehenden:

**Tests laufen mit `--no-build-cache`.** Die `gradle/actions/setup-gradle@v4`
cached `~/.gradle` inklusive Build-Cache, und `gradle.properties` setzt
`org.gradle.caching=true`. Ohne das Flag kann Gradle `testDebugUnitTest` als
`FROM-CACHE` als erfolgreich melden, **ohne einen einzigen Test auszuführen**.
Genau das ist in dieser Sitzung lokal passiert: ein `BUILD SUCCESSFUL in 1s`,
das nichts belegte. Bei einem Workflow, dessen einziger Zweck das Testen ist,
wäre das der schlimmste denkbare Fehlermodus — er meldet grün und prüft nichts.

**`-Dorg.gradle.jvmargs="-Xmx4g"` entfällt.** Der Parameter kam gegen einen
`OutOfMemoryError`, dessen tatsächliche Ursache eine nie terminierende
Polling-Schleife in `VpnViewModelTest` war. Die ist behoben. Der Parameter
behauptet eine Diagnose, die widerlegt ist, und das ist schlechter als kein
Parameter.

**Testreport als Artefakt bei Fehlschlag** (`if: failure()`), aus
`app/build/reports/tests/testDebugUnitTest`. Ein roter Lauf hinterlässt sonst
nur Konsolenausgabe.

**`assembleDebug` als zusätzliches Gate.** Es fängt, was Unit-Tests nicht sehen:
einen kaputten Hilt-Graphen, fehlende Ressourcen, Manifest-Fehler. In dieser
Sitzung wäre so ein Fall zweimal fast durchgerutscht — beide Male hat erst
`assembleDebug` es gezeigt.

Der Auto-Merge-Schritt bleibt unverändert; er wartet künftig zusätzlich auf
`assembleDebug`.

### `release.yml`

**Auslöser** wechselt von `push: branches: [main]` auf `push: tags: ['v*.*.*']`.

Ablauf in dieser Reihenfolge. Jeder Schritt kann abbrechen, bevor irgendetwas
veröffentlicht wird:

1. **Tag parsen.** Strikt `v<major>.<minor>.<patch>` mit reinen Ziffern. Alles
   andere bricht ab.
2. **Herkunft prüfen.** Der getaggte Commit muss von `main` aus erreichbar sein
   (`git merge-base --is-ancestor`). Verhindert ein Release aus einem
   Feature-Branch. Erfordert `fetch-depth: 0` beim Checkout, sonst fehlt die
   Historie für den Test.
3. **CHANGELOG-Abschnitt extrahieren.** Fehlt `## [1.2.3]`, bricht der Lauf ab.
4. **Tests** mit `--no-build-cache`.
5. **`assembleRelease`** mit injizierter Version.
6. **GitHub-Release** mit der APK und den Notes aus dem CHANGELOG-Abschnitt.

Die Keystore- und `google-services.json`-Schritte bleiben wie sie sind.

## Versionsinjektion

`app/build.gradle.kts` liest zwei Gradle-Properties mit Rückfallwert:

```kotlin
versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
versionName = (findProperty("appVersionName") as String?) ?: "1.0.0"
```

Die Namen tragen bewusst das Präfix `app`, damit sie nicht mit Gradle-eigenen
Begriffen kollidieren.

Der Workflow rechnet aus dem Tag:

```
versionCode = major * 10000 + minor * 100 + patch
```

Aus `v1.2.3` wird `10203`. **Grenze:** Minor und Patch müssen unter 100 bleiben,
sonst kippt die Monotonie — `v1.0.100` ergäbe denselben Code wie `v1.1.0`. Bei
einer App mit dreistelligen Patch-Ständen wäre die Formel zu ändern; für den
absehbaren Gebrauch reicht sie.

Lokale Debug-Builds bleiben bei `1.0.0` / `1`, weil die Properties dann fehlen.
Das `sed`-Umschreiben von `build.gradle.kts` aus `release.yml:42-45` entfällt
ersatzlos — ein Workflow, der die Quelldatei während des Laufs verändert, ist
schwerer nachzuvollziehen als eine injizierte Property.

## CHANGELOG.md

Neu angelegt im **Keep-a-Changelog**-Format mit SemVer, identisch zu dem, was
das BaluHost-Server-Repo verwendet, damit beide Repos gleich aussehen:

```markdown
# Changelog

All notable changes to the BaluHost Android app will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

## [1.0.0] - 2026-07-31

### Added
...
```

Der `## [1.0.0]`-Abschnitt hält den heutigen Stand fest — und zwar als knappe
Aufstellung dessen, **was die App kann**, nicht als Abschrift der Commit-Titel.
Ein Changelog richtet sich an jemanden, der wissen will, was sich für ihn
ändert; eine Liste von 40 Commit-Betreffs beantwortet das nicht. Für 1.0.0 heißt
das: die Funktionsbereiche (Dateien, Sync, Shares, VPN, Benachrichtigungen,
Power-Steuerung mit Sleep, Suspend, Wake, Display-Toggle, Gaming-Modus und
Always-Awake), in wenigen Zeilen unter `### Added`.

**Extraktion:** der Workflow schneidet den Text zwischen `## [1.2.3]` und der
nächsten Zeile aus, die mit `## [` beginnt. Ist das Ergebnis leer oder die
Überschrift nicht vorhanden, bricht er ab.

## README.md

Ein kurzer Abschnitt **„Release erstellen"** wird ergänzt:

1. Änderungen unter `## [Unreleased]` sammeln
2. Beim Release den Abschnitt in `## [1.2.3] - JJJJ-MM-TT` umbenennen und einen
   neuen leeren `## [Unreleased]` darüber setzen
3. Commit auf `main` bringen
4. `git tag v1.2.3 && git push origin v1.2.3`

Damit steht der Ablauf an einer Stelle, die jemand auch findet, der nicht in
Workflow-YAML liest.

## Fehlerverhalten

| Fall | Verhalten |
|---|---|
| Tag passt nicht auf `v<zahl>.<zahl>.<zahl>` | Abbruch mit klarer Meldung |
| Getaggter Commit nicht von `main` erreichbar | Abbruch — kein Release aus einem Seitenzweig |
| CHANGELOG-Abschnitt fehlt oder ist leer | Abbruch, bevor gebaut wird |
| Tests rot | Abbruch, kein Release |
| `assembleRelease` scheitert | Abbruch, kein Release |
| CI: Tests rot | Testreport als Artefakt, Auto-Merge unterbleibt |
| CI: `assembleDebug` scheitert | Auto-Merge unterbleibt |

Kein Schritt veröffentlicht etwas, bevor alle vorherigen grün sind.

## Verifikation

Die Workflows lassen sich nicht per Unit-Test prüfen. Belegt wird stattdessen:

- Die Änderung an `build.gradle.kts` darf lokale Builds nicht verändern:
  `.\gradlew.bat assembleDebug` baut weiterhin, und ein Aufruf mit
  `-PappVersionCode=10203 -PappVersionName=1.2.3` erzeugt eine APK, deren
  Manifest diese Werte trägt. Das ist per `aapt2 dump badging` oder über die
  generierte `BuildConfig` prüfbar und gehört in den Plan.
- Die CHANGELOG-Extraktion wird als Shell-Schnipsel lokal gegen die neue
  `CHANGELOG.md` durchgespielt, bevor sie im Workflow landet.
- Die YAML-Dateien werden nach der Änderung von GitHub selbst validiert; ein
  Syntaxfehler zeigt sich im ersten Lauf.
- Ein echter Lauf beider Workflows steht aus und ist die eigentliche Abnahme:
  ein Push auf `development` muss die CI auslösen, und ein Testtag muss ein
  Release erzeugen.

## Bewusst nicht enthalten

- **Die elf losen Markdown-Dateien im Wurzelverzeichnis** (`ANALYSIS_SUMMARY.md`,
  `IMPLEMENTIERUNGS_PLAN.md`, `NEXT_STEPS_IMPLEMENTATION.md` und weitere).
  Aufräumen wäre sinnvoll, ist aber ein eigenes Thema.
- **CHANGELOG-Pflicht pro PR.** Verworfen: erzwingt lückenlose Pflege, nervt
  aber bei Refactorings und Doku-Änderungen und wird dann mit Alibi-Einträgen
  umgangen.
- **Instrumentierte Tests in der CI.** Es gibt kein `androidTest`-Sourceset.
- **Lint oder statische Analyse.** Sinnvoll, aber eine eigene Entscheidung mit
  eigenem Aufräumaufwand an bestehenden Warnungen.
- **Änderungen am Auto-Merge.** Bleibt wie er ist.
