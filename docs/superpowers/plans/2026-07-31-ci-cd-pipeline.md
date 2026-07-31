# CI/CD-Pipeline überarbeiten — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die CI prüft jeden Push auf `development`, und ein Release entsteht nur noch aus einem Tag — nach bestandenen Tests und mit Release-Notes aus dem CHANGELOG.

**Architecture:** Drei Tasks. Task 1 macht die Version über Gradle-Properties injizierbar und baut `ci.yml` um. Task 2 legt `CHANGELOG.md` an und dokumentiert den Release-Ablauf in der `README.md`. Task 3 stellt `release.yml` von „Push auf main" auf „Tag" um und hängt es an Tests und CHANGELOG. Die Reihenfolge ist bindend: Task 3 braucht die Properties aus Task 1 und die Datei aus Task 2.

**Tech Stack:** GitHub Actions (`actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`, `actions/upload-artifact@v4`), Gradle 8.9 / AGP 8.5.2, Bash auf `ubuntu-latest`, `gh` CLI.

**Spec:** `docs/superpowers/specs/2026-07-31-ci-cd-pipeline-design.md`

## Global Constraints

- **Workflows lassen sich nicht per Unit-Test prüfen.** Belegt wird über lokale Gradle-Läufe und über das Durchspielen der Shell-Logik gegen echte Eingaben. Ein echter Workflow-Lauf ist die Abnahme und bleibt dem Menschen.
- **Die Testsuite darf sich nicht verändern.** Ausgangsstand: **170 Tests, 0 Fehler, 0 Errors**. Keine Task dieses Plans fügt Tests hinzu oder entfernt welche.
- **Vorsicht mit dem Gradle-Cache.** `gradle.properties` setzt `org.gradle.caching=true`. Ein blosses `.\gradlew.bat testDebugUnitTest` meldet `BUILD SUCCESSFUL in 1s` mit `FROM-CACHE` oder `UP-TO-DATE`, **ohne einen Test auszuführen**. Lokal verifiziert wird ausschliesslich mit:

  ```
  .\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --console=plain --no-build-cache
  ```

  Gradle druckt bei Erfolg **keine** Testzahl. Zählen über die XMLs:

  ```powershell
  $x = Get-ChildItem "app\build\test-results\testDebugUnitTest\*.xml" | ForEach-Object { [xml]$c = Get-Content $_.FullName; $c.testsuite }
  "tests=$(($x | Measure-Object -Property tests -Sum).Sum) failures=$(($x | Measure-Object -Property failures -Sum).Sum) errors=$(($x | Measure-Object -Property errors -Sum).Sum)"
  ```

- **Dokumentation ist Englisch.** `README.md` und die CHANGELOG des Server-Repos sind es; die neuen Texte folgen dem. Nutzertexte in der App bleiben davon unberührt.
- **`app/src/main/java/com/baluhost/android/data/worker/FolderSyncWorker.kt` hat uncommittete Änderungen aus fremder Arbeit** — niemals anfassen, niemals mitcommitten. `git add` immer dateigenau, nie `git add -A`, nie `git commit -a`.
- **Keine Secrets in Klartext.** Alle Secrets kommen wie bisher aus `${{ secrets.* }}`; es werden keine neuen eingeführt.

---

### Task 1: Version injizierbar machen und `ci.yml` umbauen

**Files:**
- Modify: `app/build.gradle.kts:19-20`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: nichts
- Produces: die Gradle-Properties `appVersionName` und `appVersionCode`, die Task 3 beim Release-Build setzt

- [ ] **Step 1: Ausgangsstand der Version festhalten**

Run: `.\gradlew.bat assembleDebug --console=plain`

Dann den erzeugten `BuildConfig` lesen:

```powershell
Get-Content "app\build\generated\source\buildConfig\debug\com\baluhost\android\BuildConfig.java" | Select-String "VERSION_"
```

Erwartet: `VERSION_CODE = 1` und `VERSION_NAME = "1.0.0"`. Das ist der Referenzwert für Step 3.

- [ ] **Step 2: Version aus Properties lesen**

In `app/build.gradle.kts` die Zeilen 19-20 ersetzen:

```kotlin
        // The release workflow derives both from the git tag and passes them in.
        // Local and debug builds get the fallbacks below, so nothing changes for
        // day-to-day work. Prefixed with "app" so they cannot collide with
        // Gradle's own vocabulary.
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("appVersionName") as String?) ?: "1.0.0"
```

- [ ] **Step 3: Beide Pfade verifizieren**

Zuerst der Rückfallpfad — er muss sich gegenüber Step 1 **nicht** verändert haben:

Run: `.\gradlew.bat clean assembleDebug --console=plain`

```powershell
Get-Content "app\build\generated\source\buildConfig\debug\com\baluhost\android\BuildConfig.java" | Select-String "VERSION_"
```

Erwartet: unverändert `VERSION_CODE = 1`, `VERSION_NAME = "1.0.0"`.

Dann der injizierte Pfad:

Run: `.\gradlew.bat clean assembleDebug --console=plain -PappVersionCode=10203 -PappVersionName=1.2.3`

```powershell
Get-Content "app\build\generated\source\buildConfig\debug\com\baluhost\android\BuildConfig.java" | Select-String "VERSION_"
```

Erwartet: `VERSION_CODE = 10203` und `VERSION_NAME = "1.2.3"`.

Zeigt der zweite Lauf weiterhin `1` und `1.0.0`, greift die Property nicht — dann stimmt der Name nicht oder der Cast schlägt fehl. Nicht weitermachen, bevor beide Läufe die erwarteten Werte zeigen.

- [ ] **Step 4: `ci.yml` ersetzen**

`.github/workflows/ci.yml` vollständig durch Folgendes ersetzen:

```yaml
name: CI

on:
  pull_request:
    branches: [main]
  push:
    branches: [development]

permissions:
  contents: write
  pull-requests: write

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Decode google-services.json
        run: echo "${{ secrets.GOOGLE_SERVICES_JSON }}" | base64 -d > app/google-services.json

      # --no-build-cache is load-bearing, not tidiness. setup-gradle restores the
      # Gradle build cache and gradle.properties sets org.gradle.caching=true, so
      # without it testDebugUnitTest can be served FROM-CACHE and report success
      # without executing a single test — the worst failure mode a test job has.
      - name: Run unit tests
        run: ./gradlew testDebugUnitTest --no-build-cache --no-daemon

      - name: Upload test report
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-report
          path: app/build/reports/tests/testDebugUnitTest
          retention-days: 7

      # Catches what unit tests cannot: a broken Hilt graph, missing resources,
      # a malformed manifest.
      - name: Build debug APK
        run: ./gradlew assembleDebug --no-daemon

      # Guarded on the event: this workflow now also runs on pushes to
      # development, where there is no pull request to merge.
      - name: Enable auto-merge
        if: success() && github.event_name == 'pull_request'
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: gh pr merge ${{ github.event.pull_request.number }} --auto --squash
```

Drei Änderungen gegenüber vorher, jede mit einem Grund:

1. `push` auf `development` als zusätzlicher Auslöser.
2. `-Dorg.gradle.jvmargs="-Xmx4g"` entfällt — es kam gegen einen `OutOfMemoryError`, dessen wirkliche Ursache eine nie terminierende Polling-Schleife war. Die ist behoben.
3. Der Auto-Merge ist auf `github.event_name == 'pull_request'` eingeschränkt. **Ohne diese Bedingung würde der Schritt bei jedem Development-Push fehlschlagen**, weil `github.event.pull_request.number` dann leer ist.

- [ ] **Step 5: YAML gegenlesen**

Run: `git diff .github/workflows/ci.yml`

Prüfe im Diff:
- die Einrückung der neuen `push:`-Sektion liegt auf derselben Ebene wie `pull_request:`
- der `if:`-Ausdruck des Auto-Merge-Schritts enthält beide Bedingungen
- kein Schritt hat seinen `name:` verloren

GitHub validiert das YAML beim ersten Lauf; ein Syntaxfehler zeigt sich dort. Ein Logikfehler nicht — deshalb dieser Durchgang.

- [ ] **Step 6: Volle Suite**

Run: `.\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --console=plain --no-build-cache` plus XML-Zählung aus den Global Constraints.

Expected: `tests=170 failures=0 errors=0` — unverändert. Diese Task fasst keinen Testcode an.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts .github/workflows/ci.yml
git commit -m "ci: check every development push and derive the version from properties"
```

---

### Task 2: `CHANGELOG.md` anlegen und Release-Ablauf dokumentieren

**Files:**
- Create: `CHANGELOG.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: nichts
- Produces: `CHANGELOG.md` mit einem `## [Unreleased]`- und einem `## [1.0.0] - 2026-07-31`-Abschnitt, aus dem Task 3 die Release-Notes schneidet

- [ ] **Step 1: `CHANGELOG.md` anlegen**

Create `CHANGELOG.md`:

```markdown
# Changelog

All notable changes to the BaluHost Android app will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

## [1.0.0] - 2026-07-31

### Added

- Device pairing with a BaluHost server via QR code, including WireGuard VPN import
- File browser with upload, download, move and delete, plus an offline queue for
  actions taken without a connection
- Folder sync with schedules, and a sync status view
- Shares: create, list and manage server shares from the app
- Dashboard with system telemetry, RAID and S.M.A.R.T. status, energy figures and
  server uptime
- Push and in-app notifications with quiet hours and per-category preferences
- Power control: soft sleep, suspend, wake, and Wake-on-LAN via the Fritz!Box
- Display toggle — turn the server's displays off to drop the GPU to idle, and
  back on, including an optional session unlock
- Gaming mode — displays on plus Steam Big Picture, contributed by the server's
  bundled `steam_gaming` plugin
- Always-awake override with 1h / 4h / 8h presets, a free expiry and a permanent
  mode, admin only
- App lock with PIN and biometric unlock, and an automatic lock timeout
```

Das ist bewusst eine Aufstellung dessen, **was die App kann**, keine Abschrift von Commit-Titeln. Ein Changelog beantwortet die Frage „was ändert sich für mich" — vierzig Commit-Betreffs tun das nicht.

- [ ] **Step 2: Die Extraktionslogik gegen die echte Datei durchspielen**

Genau dieses Snippet landet später in Task 3. Es hier zu prüfen, solange man den Text noch vor sich hat, ist billiger als ein fehlgeschlagener Release-Lauf.

In einer Bash-Shell (Git Bash) im Projektverzeichnis:

```bash
NAME=1.0.0
awk -v v="## [$NAME]" '
  index($0, v) == 1 { found=1; next }
  found && /^## \[/ { exit }
  found { print }
' CHANGELOG.md
```

Erwartet: der Block `### Added` mit den Stichpunkten, **ohne** die Überschrift `## [1.0.0] - 2026-07-31` selbst und **ohne** irgendetwas aus `## [Unreleased]`.

Dann der Negativfall — er muss leer bleiben:

```bash
NAME=9.9.9
awk -v v="## [$NAME]" '
  index($0, v) == 1 { found=1; next }
  found && /^## \[/ { exit }
  found { print }
' CHANGELOG.md
```

Erwartet: keine Ausgabe. Kommt hier etwas, wäre eine fehlende Version im Release-Workflow nicht erkennbar und würde als leere Notes durchgehen.

- [ ] **Step 3: Release-Ablauf in die `README.md`**

In `README.md` direkt **vor** der Zeile `## Security` einfügen. Der Block unten
ist mit vier Backticks umschlossen, weil er selbst einen dreifachen Code-Zaun
enthält — in die `README.md` kommt nur der Inhalt zwischen den vier Backticks:

````markdown
## Releasing

Releases are cut from a tag, not from a push to `main`. The tag is the source of
truth for the version: `versionName` comes from it, and `versionCode` is derived
as `major * 10000 + minor * 100 + patch`.

1. Collect changes under `## [Unreleased]` in `CHANGELOG.md` as you go.
2. To release, rename that section to `## [1.2.3] - YYYY-MM-DD` and put a fresh
   empty `## [Unreleased]` above it.
3. Get the commit onto `main`.
4. Tag and push:

```bash
git tag v1.2.3
git push origin v1.2.3
```

The release workflow refuses to build if the tag is malformed, if the tagged
commit is not reachable from `main`, if `CHANGELOG.md` has no section for that
version, or if the tests fail. The extracted changelog section becomes the
release notes.

Minor and patch must stay below 100, or the `versionCode` formula loses its
monotonicity.
````

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md README.md
git commit -m "docs: add a changelog and document the release process"
```

---

### Task 3: `release.yml` auf Tags umstellen

**Files:**
- Modify: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: die Gradle-Properties `appVersionName` und `appVersionCode` aus Task 1; `CHANGELOG.md` aus Task 2
- Produces: nichts, was andere Tasks nutzen

- [ ] **Step 1: Die Tag-Logik lokal durchspielen**

Bevor irgendetwas in den Workflow wandert, wird die Rechnung gegen echte Eingaben geprüft. In einer Bash-Shell:

```bash
for TAG in v1.2.3 v0.0.1 v2.10.0 v1.0.100 1.2.3 v1.2 v1.2.3-beta; do
  if [[ ! "$TAG" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "$TAG -> ABGELEHNT (Format)"
    continue
  fi
  MAJOR="${BASH_REMATCH[1]}"; MINOR="${BASH_REMATCH[2]}"; PATCH="${BASH_REMATCH[3]}"
  if (( MINOR > 99 || PATCH > 99 )); then
    echo "$TAG -> ABGELEHNT (minor/patch >= 100)"
    continue
  fi
  echo "$TAG -> name=${MAJOR}.${MINOR}.${PATCH} code=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))"
done
```

Erwartet, Zeile für Zeile:

```
v1.2.3 -> name=1.2.3 code=10203
v0.0.1 -> name=0.0.1 code=1
v2.10.0 -> name=2.10.0 code=21000
v1.0.100 -> ABGELEHNT (minor/patch >= 100)
1.2.3 -> ABGELEHNT (Format)
v1.2 -> ABGELEHNT (Format)
v1.2.3-beta -> ABGELEHNT (Format)
```

Weicht etwas ab, stimmt der reguläre Ausdruck oder die Formel nicht — nicht weitermachen.

- [ ] **Step 2: `release.yml` ersetzen**

`.github/workflows/release.yml` vollständig durch Folgendes ersetzen:

```yaml
name: Release APK

on:
  push:
    tags: ['v*.*.*']

jobs:
  release:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4
        with:
          # Full history: the ancestry check below needs main's commits.
          fetch-depth: 0

      - name: Parse tag and derive version
        id: version
        run: |
          TAG="${GITHUB_REF_NAME}"
          if [[ ! "$TAG" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
            echo "::error::Tag '$TAG' is not of the form v<major>.<minor>.<patch>"
            exit 1
          fi
          MAJOR="${BASH_REMATCH[1]}"
          MINOR="${BASH_REMATCH[2]}"
          PATCH="${BASH_REMATCH[3]}"
          if (( MINOR > 99 || PATCH > 99 )); then
            echo "::error::minor and patch must stay below 100, or the versionCode formula loses monotonicity"
            exit 1
          fi
          echo "name=${MAJOR}.${MINOR}.${PATCH}" >> "$GITHUB_OUTPUT"
          echo "code=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))" >> "$GITHUB_OUTPUT"
          echo "tag=$TAG" >> "$GITHUB_OUTPUT"

      - name: Verify the tag is reachable from main
        run: |
          git fetch origin main
          if ! git merge-base --is-ancestor "$GITHUB_SHA" origin/main; then
            echo "::error::The tagged commit is not reachable from main — refusing to release from a side branch"
            exit 1
          fi

      - name: Extract release notes from the changelog
        run: |
          NAME="${{ steps.version.outputs.name }}"
          awk -v v="## [$NAME]" '
            index($0, v) == 1 { found=1; next }
            found && /^## \[/ { exit }
            found { print }
          ' CHANGELOG.md > release-notes.md
          if [ -z "$(tr -d '[:space:]' < release-notes.md)" ]; then
            echo "::error::CHANGELOG.md has no non-empty section for $NAME"
            exit 1
          fi

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Decode google-services.json
        run: echo "${{ secrets.GOOGLE_SERVICES_JSON }}" | base64 -d > app/google-services.json

      # Same reasoning as in ci.yml: without --no-build-cache the task can be
      # served FROM-CACHE and report success without running anything. A release
      # is the last place that should happen.
      - name: Run unit tests
        run: ./gradlew testDebugUnitTest --no-build-cache --no-daemon

      - name: Upload test report
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: release-test-report
          path: app/build/reports/tests/testDebugUnitTest
          retention-days: 7

      - name: Decode keystore
        run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > app/baluhost-release.jks

      - name: Build release APK
        env:
          KEYSTORE_PATH: baluhost-release.jks
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: >
          ./gradlew assembleRelease --no-daemon
          -PappVersionName=${{ steps.version.outputs.name }}
          -PappVersionCode=${{ steps.version.outputs.code }}

      - name: Create GitHub release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          gh release create "${{ steps.version.outputs.tag }}" \
            app/build/outputs/apk/release/app-release.apk \
            --title "Release ${{ steps.version.outputs.name }}" \
            --notes-file release-notes.md
```

Was gegenüber vorher entfällt und warum:

- Der Auslöser `push: branches: [main]` samt `paths-ignore` — Releases hängen jetzt am Tag.
- Der Schritt „Extract version", der `versionName` per `grep` aus `build.gradle.kts` fischte und `versionCode` aus `github.run_number` bildete. Der Lauf-Zähler hat mit der Version nichts zu tun.
- Der Schritt „Set versionCode", der `build.gradle.kts` mit `sed` **während des Laufs umschrieb**. Ein Workflow, der seine eigene Quelldatei verändert, ist schwer nachzuvollziehen; die injizierte Property tut dasselbe ohne Nebenwirkung.
- `--generate-notes` — die Notes kommen jetzt aus dem CHANGELOG.

`KEYSTORE_PATH` bleibt der relative Wert `baluhost-release.jks`: `build.gradle.kts` löst ihn über `file(ksPath)` gegen das App-Modul auf, und die Datei wird nach `app/baluhost-release.jks` geschrieben. Nicht ändern.

- [ ] **Step 3: YAML gegenlesen**

Run: `git diff .github/workflows/release.yml`

Prüfe im Diff:
- `on: push: tags:` ist korrekt eingerückt und `branches:` ist verschwunden
- jeder `run:`-Block mit mehreren Zeilen nutzt `|`, der Gradle-Aufruf nutzt `>`
- `fetch-depth: 0` steht unter `with:` beim Checkout
- die `steps.version.outputs.*`-Verweise passen zu den in Step „Parse tag" gesetzten Namen (`name`, `code`, `tag`)
- der Keystore wird **nach** dem Testlauf dekodiert — ein Test-Fehlschlag soll passieren, bevor Signaturmaterial auf die Platte kommt

- [ ] **Step 4: Volle Suite**

Run: `.\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --console=plain --no-build-cache` plus XML-Zählung.

Expected: `tests=170 failures=0 errors=0` — unverändert.

- [ ] **Step 5: Release-Build lokal proben**

Ein echter Release-Build braucht das Keystore-Secret und ist lokal nicht durchführbar. Prüfbar ist aber, dass der Gradle-Aufruf selbst trägt und die Version ankommt:

Run: `.\gradlew.bat clean assembleDebug --console=plain -PappVersionName=1.2.3 -PappVersionCode=10203`

```powershell
Get-Content "app\build\generated\source\buildConfig\debug\com\baluhost\android\BuildConfig.java" | Select-String "VERSION_"
```

Erwartet: `VERSION_CODE = 10203`, `VERSION_NAME = "1.2.3"` — dieselbe Property-Übergabe, die der Workflow an `assembleRelease` hängt.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: build releases from tags, gated on tests and a changelog entry"
```

---

## Nach Abschluss

Der Plan ist erledigt, wenn beide Workflow-Dateien geändert sind, `.\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --no-build-cache` weiterhin **170 Tests / 0 Fehler** meldet und die Versionsinjektion in beide Richtungen nachgewiesen ist.

**Die eigentliche Abnahme steht danach noch aus und gehört dem Menschen**, weil kein Agent einen Workflow-Lauf auslösen kann:

1. **Push auf `development`** → die CI muss anspringen, Tests und `assembleDebug` grün melden, und der Auto-Merge-Schritt muss übersprungen werden (kein PR vorhanden). Springt sie nicht an, stimmt der Auslöser nicht; schlägt der Auto-Merge fehl, fehlt die `event_name`-Bedingung.
2. **Testtag setzen**, etwa `git tag v1.0.0 && git push origin v1.0.0` — der Release-Workflow muss durchlaufen und ein GitHub-Release mit APK erzeugen, dessen Notes der `## [1.0.0]`-Abschnitt sind.
3. **Negativprobe:** ein Tag ohne CHANGELOG-Abschnitt, etwa `v9.9.9`, muss **vor dem Bauen** abbrechen. Das ist der Beleg, dass das Gate greift und nicht nur dekorativ ist. Danach den Tag wieder löschen (`git push --delete origin v9.9.9`).

Punkt 3 ist der wichtigste: ein Gate, das nie ausgelöst hat, ist ein unbewiesenes Gate.
