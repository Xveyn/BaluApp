# Gaming-Modus beenden (App-Gegenpart) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der Dashboard-Power-Dialog zeigt „Gaming-Modus" bzw. „Gaming-Modus beenden" — je nachdem, welche Richtung der Server gerade anbietet — und löst mit dem zweiten Eintrag die Action `gaming_mode_end` aus BaluHost PR #497/#500 aus.

**Architecture:** Rein clientseitig. Der Server ist fertig: derselbe Endpunkt `POST plugins/{name}/menu-actions/{actionId}`, nur eine zweite Action-ID. Die bestehende Kette (Konstanten-Objekt → Verfügbarkeitsprüfung über das UI-Manifest → Action-UseCase → ViewModel-Flag + Funktion → `PowerOptionButton`) wird um den zweiten Eintrag erweitert. Der einzige nicht-additive Schritt: `IsGamingModeAvailableUseCase` liefert heute ein `Boolean` für genau eine Action; da das Manifest **ein** HTTP-Call ist, der bei jedem Dialog-Öffnen läuft, wird der UseCase zu `GetGamingModeActionsUseCase` mit einem zweifeldrigen Ergebnis umgebaut, statt einen zweiten UseCase mit einem zweiten Manifest-Call danebenzustellen.

**Der entscheidende Punkt: die Richtungswahl gehört dem Server, nicht dem Client.** Seit BaluHost PR #500 führt das Plugin selbst Buch darüber, welche Richtung gerade sinnvoll ist (Merker-Datei plus Display-Zählung), und das UI-Manifest enthält **genau einen** der beiden Menüpunkte. Der Client leitet daraus nichts ab und rechnet nichts nach: er rendert, was das Manifest anbietet. Damit ist er automatisch mit allen drei Serverständen verträglich — vor #497 (nur `gaming_mode`), zwischen #497 und #500 (beide gleichzeitig), ab #500 (genau einer) — ohne eine Exklusivitäts-Annahme, die er nicht selbst prüfen kann.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Retrofit, JUnit4 + MockK + Turbine + `kotlinx-coroutines-test`.

## Global Constraints

- **Server-Kontrakt ist fix und wird nicht verändert** — er kommt aus BaluHost PR #497, #499 und #500 (alle gemerged am 2026-08-01, Stand geprüft gegen `main`). Kein API-Endpunkt, kein DTO und keine Server-Datei wird in diesem Plan angefasst.
- **`GET plugins/ui/manifest` liefert genau einen der beiden Gaming-Menüpunkte** (PR #500): `get_ui_manifest()` wählt anhand Merker-Datei × Display-Zählung zwischen `gaming_mode` und `gaming_mode_end`; jeder Zweifelsfall löst zu `gaming_mode` auf, weil ein falsches „Beenden" jemandem die Fenster minimieren würde. **Der Client darf daraus keine Exklusivitäts-Invariante machen** — er rendert pro Eintrag genau dann, wenn das Manifest ihn nennt, und ist damit auch gegen einen Server zwischen #497 und #500 (beide Einträge) korrekt.
- **Beide Actions bleiben klickbar, egal was das Manifest zeigt** (`get_menu_items()` deklariert weiterhin beide). Ein Klick, der einen Zustandswechsel überholt, läuft also nicht in einen 404. Der Client braucht deshalb keine Absicherung gegen ein veraltetes Menü.
- **Der Zustand kippt durch genau diese Klicks.** Ein erfolgreicher Start setzt den Merker, ein erfolgreiches Beenden löscht ihn — die App holt das Manifest bei jedem Öffnen des Power-Dialogs neu (`onPowerDialogOpened()`), was dem `refreshMenuItems()` der Webapp entspricht. Es braucht kein Auto-Refresh und keine WebSocket-Benachrichtigung; der Dialog schließt sich nach jeder Aktion ohnehin.
- Action-ID: `gaming_mode_end`. Plugin-Name: `steam_gaming`. Sprache für Übersetzungen: `de`.
- Message-Keys des Servers, alle mit deutscher Übersetzung im Manifest ausgeliefert:
  | Key | `ok` | Deutsch |
  |---|---|---|
  | `menu_gaming_mode_ended` | `true` | Gaming-Modus beendet |
  | `menu_end_steam_not_running` | **`true`** | Steam läuft nicht - nichts zu beenden |
  | `menu_end_game_running` | `false` | Es läuft noch ein Spiel |
  | `menu_end_close_failed` | `false` | Big Picture konnte nicht geschlossen werden |
  | `menu_end_windows_failed` | `false` | Big Picture ist zu, aber die Fenster blieben offen |
- **`menu_end_steam_not_running` kommt mit `ok=true`.** Das ist ein No-op, kein Fehler, und muss als `Result.Success` beim Nutzer landen. Beim Kopieren von `StartGamingModeUseCase` nicht umdrehen.
- **`menu_end_windows_failed` existiert weiter, feuert aber nur noch bei einem echten Fehlschlag.** PR #499 hat die Rücklese-Prüfung über KWins `showingDesktop` entfernt, weil die Property den temporären Show-Desktop-Modus beschreibt und nicht „Fenster sind unten" — sie machte aus jedem erfolgreichen Lauf eine rote Meldung. Übrig bleibt Exit ≠ 0 als einziger redlich meldbarer Fehler. Für den Client ändert sich dadurch nichts am Kontrakt, wohl aber die Erwartung beim Handtest: dieser Fehler darf im Normalbetrieb **nicht** mehr auftreten.
- **Die Beenden-Action fasst die Displays nicht an** (bewusste Server-Entscheidung, „Displays aus" ist ein eigener Menüpunkt). Der Client darf `desktopState` deshalb weder bei Erfolg noch bei Fehlschlag verändern — anders als `startGamingMode()`, das auf `RUNNING` bzw. `UNKNOWN` setzt.
- Nutzertexte sind deutsche String-Literale direkt im Code, es gibt kein i18n-Framework (siehe `presentation/ui/CLAUDE.md`).
- Testlauf ausschließlich mit `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache` — ein blanker `testDebugUnitTest` kann `BUILD SUCCESSFUL` melden, ohne einen einzigen Test auszuführen (siehe `app/src/test/CLAUDE.md`).
- Windows/PowerShell: Befehle **nie** mit `&&` verketten, sondern mit `;` bzw. `if ($?) { … }`.

---

### Task 1: Verfügbarkeitsprüfung liefert beide Actions

Der bestehende `IsGamingModeAvailableUseCase` beantwortet „gibt es `gaming_mode`?" mit einem `Boolean`. Er wird zu `GetGamingModeActionsUseCase` mit einem zweifeldrigen Ergebnis umgebaut, damit ein Manifest-Call beide Menüpunkte beantwortet. Eigener Ergebnis-Typ als verschachtelte `data class` folgt dem Präzedenzfall `GetCacheStatsUseCase` (siehe `domain/usecase/CLAUDE.md`).

Zwei Felder, obwohl ein aktueller Server immer genau eines davon setzt: der UseCase liest ab, was im Manifest steht, statt eine Exklusivität zu erzwingen, die er nicht prüfen kann. Ein Server zwischen BaluHost #497 und #500 nennt beide Einträge, einer vor #497 nur den Start — beides muss unfallfrei durchlaufen.

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/domain/usecase/plugin/GamingMode.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/plugin/GetGamingModeActionsUseCase.kt`
- Delete: `app/src/main/java/com/baluhost/android/domain/usecase/plugin/IsGamingModeAvailableUseCase.kt`
- Test: `app/src/test/java/com/baluhost/android/domain/usecase/plugin/GamingModeUseCaseTest.kt`

**Interfaces:**
- Consumes: `PluginApi.getUiManifest(): PluginUiManifestDto` (Feld `plugins`), `PluginUiInfoDto(name, menuItems, translations)`, `PluginMenuItemDto(id)`, `PluginTranslationCache.put(plugin, map)`.
- Produces: `GamingMode.END_ACTION_ID = "gaming_mode_end"`; `GetGamingModeActionsUseCase.invoke(): GetGamingModeActionsUseCase.GamingModeActions` mit den Feldern `canStart: Boolean` und `canEnd: Boolean`, beide mit Default `false`.

- [ ] **Step 1: Testdatei auf den neuen UseCase umstellen und die neuen Fälle als Failing Tests schreiben**

In `GamingModeUseCaseTest.kt` die Fixture um die zweite Action erweitern — das bestehende `steamPlugin` bleibt bewusst einaktionig, damit „nur Start vorhanden" weiter abgedeckt ist:

```kotlin
    private val bothActionsPlugin = steamPlugin.copy(
        menuItems = listOf(
            PluginMenuItemDto(id = "gaming_mode"),
            PluginMenuItemDto(id = "gaming_mode_end")
        )
    )
```

Die vier bestehenden Verfügbarkeitstests (`available when the plugin contributes the gaming_mode action`, `unavailable when the plugin is missing from the manifest`, `unavailable when the plugin no longer contributes the action`, `unavailable when the manifest call fails`) auf den neuen Typ umschreiben und die neuen Fälle ergänzen — der ganze Block ersetzt die alten vier Tests:

```kotlin
    @Test
    fun `the start action is offered when the plugin contributes it`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(steamPlugin))

        assertTrue(GetGamingModeActionsUseCase(pluginApi, cache)().canStart)
    }

    @Test
    fun `the end action alone is offered when the manifest names only it`() = runTest {
        // Der Normalfall gegen einen Server ab BaluHost PR #500: das Manifest
        // nennt genau eine Richtung, hier die Beenden-Richtung.
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(
            listOf(steamPlugin.copy(menuItems = listOf(PluginMenuItemDto(id = "gaming_mode_end"))))
        )

        val actions = GetGamingModeActionsUseCase(pluginApi, cache)()

        assertFalse(actions.canStart)
        assertTrue(actions.canEnd)
    }

    @Test
    fun `both actions are read off as-is when the manifest names both`() = runTest {
        // Kein aktueller Server tut das, ein Stand zwischen #497 und #500 schon.
        // Der UseCase liest ab, was dasteht, statt eine Exklusivität zu
        // erzwingen, die er nicht prüfen kann.
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(bothActionsPlugin))

        val actions = GetGamingModeActionsUseCase(pluginApi, cache)()

        assertTrue(actions.canStart)
        assertTrue(actions.canEnd)
    }

    @Test
    fun `the end action is not offered while the manifest only has the start action`() = runTest {
        // Ein Server vor PR #497 liefert genau das, ein Server ab #500 immer
        // dann, wenn der Gaming-Modus gerade nicht läuft.
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(steamPlugin))

        assertFalse(GetGamingModeActionsUseCase(pluginApi, cache)().canEnd)
    }

    @Test
    fun `no action is offered when the plugin is missing from the manifest`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(emptyList())

        val actions = GetGamingModeActionsUseCase(pluginApi, cache)()

        assertFalse(actions.canStart)
        assertFalse(actions.canEnd)
    }

    @Test
    fun `no action is offered when the plugin contributes no menu items`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(
            listOf(steamPlugin.copy(menuItems = emptyList()))
        )

        val actions = GetGamingModeActionsUseCase(pluginApi, cache)()

        assertFalse(actions.canStart)
        assertFalse(actions.canEnd)
    }

    @Test
    fun `no action is offered when the manifest call fails`() = runTest {
        coEvery { pluginApi.getUiManifest() } throws RuntimeException("no network")

        val actions = GetGamingModeActionsUseCase(pluginApi, cache)()

        assertFalse(actions.canStart)
        assertFalse(actions.canEnd)
    }
```

In den zwei Action-Tests, die vorher den Cache über die Verfügbarkeitsprüfung befüllt haben (`a refused action is reported with the German plugin string` und `a successful action returns the German plugin string`), den Aufruf `IsGamingModeAvailableUseCase(pluginApi, cache)()` durch `GetGamingModeActionsUseCase(pluginApi, cache)()` ersetzen — sonst kompiliert die Datei nicht.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*GamingModeUseCaseTest"`
Expected: Kompilierfehler, `Unresolved reference: GetGamingModeActionsUseCase`.

- [ ] **Step 3: Konstante ergänzen**

`GamingMode.kt` vollständig:

```kotlin
package com.baluhost.android.domain.usecase.plugin

/** Identifiers of the bundled steam_gaming plugin's power-menu actions. */
internal object GamingMode {
    const val PLUGIN_NAME = "steam_gaming"
    const val ACTION_ID = "gaming_mode"
    const val END_ACTION_ID = "gaming_mode_end"
    const val LANGUAGE = "de"
}
```

- [ ] **Step 4: Neuen UseCase anlegen**

`GetGamingModeActionsUseCase.kt` vollständig:

```kotlin
package com.baluhost.android.domain.usecase.plugin

import com.baluhost.android.data.local.PluginTranslationCache
import com.baluhost.android.data.remote.api.PluginApi
import javax.inject.Inject

/**
 * Which of the steam_gaming plugin's power-menu actions the server currently
 * offers. The plugin ships with BaluHost but can be switched off, the manifest
 * lists only enabled plugins, and the two menu items can appear independently —
 * an older server has `gaming_mode` without `gaming_mode_end`.
 *
 * One manifest call answers both questions on purpose: this runs on every
 * opening of the power dialog, and two use cases would mean two HTTP calls for
 * one response.
 *
 * Fills [PluginTranslationCache] on the way through, so [StartGamingModeUseCase]
 * and [EndGamingModeUseCase] can resolve the message keys they get back.
 *
 * Failures are swallowed on purpose: this is a discovery call, and the only
 * sensible answer to "cannot tell" is to hide the entries.
 */
class GetGamingModeActionsUseCase @Inject constructor(
    private val pluginApi: PluginApi,
    private val translationCache: PluginTranslationCache
) {
    /** Defaults to "nothing offered" so every failure path can return it as-is. */
    data class GamingModeActions(
        val canStart: Boolean = false,
        val canEnd: Boolean = false
    )

    suspend operator fun invoke(): GamingModeActions {
        return try {
            val plugin = pluginApi.getUiManifest().plugins
                .firstOrNull { it.name == GamingMode.PLUGIN_NAME }
                ?: return GamingModeActions()

            plugin.translations?.get(GamingMode.LANGUAGE)?.let {
                translationCache.put(GamingMode.PLUGIN_NAME, it)
            }

            GamingModeActions(
                canStart = plugin.menuItems.any { it.id == GamingMode.ACTION_ID },
                canEnd = plugin.menuItems.any { it.id == GamingMode.END_ACTION_ID }
            )
        } catch (_: Exception) {
            GamingModeActions()
        }
    }
}
```

- [ ] **Step 5: Alte Datei löschen**

```bash
git rm "app/src/main/java/com/baluhost/android/domain/usecase/plugin/IsGamingModeAvailableUseCase.kt"
```

Danach `DashboardViewModel.kt` kompiliert nicht mehr — das ist erwartet und wird in Task 3 behoben. Die Tests dieses Tasks laufen trotzdem nicht, weil das Test-Sourceset gegen das Haupt-Sourceset kompiliert; deshalb ist Step 6 hier **nur** die Kompilierprüfung des UseCase selbst, und der grüne Testlauf folgt am Ende von Task 3.

- [ ] **Step 6: Übergangsweise das ViewModel mitziehen, damit der Baum kompiliert**

Damit dieser Task für sich testbar bleibt, in `DashboardViewModel.kt` nur die minimale mechanische Anpassung vornehmen — die eigentliche Erweiterung kommt in Task 3:

- Import `com.baluhost.android.domain.usecase.plugin.IsGamingModeAvailableUseCase` → `com.baluhost.android.domain.usecase.plugin.GetGamingModeActionsUseCase` (Zeile 34).
- Konstruktorparameter (Zeile 87) `private val isGamingModeAvailableUseCase: IsGamingModeAvailableUseCase,` → `private val getGamingModeActionsUseCase: GetGamingModeActionsUseCase,`.
- Zuweisung in `onPowerDialogOpened()` (Zeilen 599–603):

```kotlin
            _gamingModeAvailable.value = if (_isAdmin.value) {
                getGamingModeActionsUseCase().canStart
            } else {
                false
            }
```

Gleiches in `DashboardViewModelDesktopActionTest.kt`: Import, Feld, `mockk()`-Zuweisung und Konstruktorargument umbenennen, und die zwei Stubs anpassen —
`coEvery { isGamingModeAvailableUseCase() } returns false` wird zu
`coEvery { getGamingModeActionsUseCase() } returns GetGamingModeActionsUseCase.GamingModeActions()`,
`coEvery { isGamingModeAvailableUseCase() } returns true` (im Test `onPowerDialogOpened asks about gaming mode for an admin`) wird zu
`coEvery { getGamingModeActionsUseCase() } returns GetGamingModeActionsUseCase.GamingModeActions(canStart = true)`,
und die beiden `coVerify(exactly = 0) { isGamingModeAvailableUseCase() }` auf `getGamingModeActionsUseCase` umbenennen.

- [ ] **Step 7: Tests laufen lassen und grün bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS. Anschließend die XML-Ergebnisse zählen (Befehl in `app/src/test/CLAUDE.md`) und bestätigen, dass `tests` > 0 ist — ein grüner Lauf ohne ausgeführte Tests beweist nichts.

- [ ] **Step 8: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/domain/usecase/plugin" "app/src/test/java/com/baluhost/android/domain/usecase/plugin/GamingModeUseCaseTest.kt" "app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt" "app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelDesktopActionTest.kt"
git commit -m "refactor(plugin): report both gaming-mode actions from one manifest call"
```

---

### Task 2: EndGamingModeUseCase

**Files:**
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/plugin/EndGamingModeUseCase.kt`
- Test: `app/src/test/java/com/baluhost/android/domain/usecase/plugin/GamingModeUseCaseTest.kt`

**Interfaces:**
- Consumes: `GamingMode.PLUGIN_NAME`, `GamingMode.END_ACTION_ID` (Task 1); `PluginApi.runMenuAction(name: String, actionId: String): PluginMenuActionResultDto` mit den Feldern `ok: Boolean`, `messageKey: String?`, `messageText: String?`; `PluginTranslationCache.resolve(plugin, key, fallback): String`.
- Produces: `EndGamingModeUseCase.invoke(): com.baluhost.android.util.Result<String>` — `Result.Success(deutscher Text)` bei `ok=true`, `Result.Error(Exception(deutscher Text))` bei `ok=false`.

- [ ] **Step 1: Failing Tests schreiben**

Ans Ende von `GamingModeUseCaseTest.kt` anfügen. Die `bothActionsPlugin`-Fixture aus Task 1 wird hier zum Befüllen des Übersetzungs-Caches gebraucht, deshalb bekommt sie die Beenden-Übersetzungen — dazu in Task 1s Fixture-Definition die `translations`-Map überschreiben:

```kotlin
    private val bothActionsPlugin = steamPlugin.copy(
        menuItems = listOf(
            PluginMenuItemDto(id = "gaming_mode"),
            PluginMenuItemDto(id = "gaming_mode_end")
        ),
        translations = mapOf(
            "de" to mapOf(
                "menu_gaming_mode_started" to "Gaming-Modus gestartet",
                "menu_steam_failed" to "Displays sind an, aber Steam startete nicht",
                "menu_gaming_mode_ended" to "Gaming-Modus beendet",
                "menu_end_steam_not_running" to "Steam läuft nicht - nichts zu beenden",
                "menu_end_game_running" to "Es läuft noch ein Spiel"
            )
        )
    )
```

Die neuen Tests:

```kotlin
    @Test
    fun `ending the gaming mode returns the German plugin string`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(bothActionsPlugin))
        GetGamingModeActionsUseCase(pluginApi, cache)()
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } returns
            PluginMenuActionResultDto(
                ok = true,
                messageKey = "menu_gaming_mode_ended",
                messageText = "Gaming mode ended"
            )

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals("Gaming-Modus beendet", (result as Result.Success).data)
    }

    @Test
    fun `a running game blocks the end action and is reported as an error`() = runTest {
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(bothActionsPlugin))
        GetGamingModeActionsUseCase(pluginApi, cache)()
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } returns
            PluginMenuActionResultDto(
                ok = false,
                messageKey = "menu_end_game_running",
                messageText = "A game is still running: Factorio"
            )

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals("Es läuft noch ein Spiel", (result as Result.Error).exception.message)
    }

    @Test
    fun `Steam not running is a no-op, not a failure`() = runTest {
        // Der Server meldet diesen Fall mit ok=true. Er darf nicht als Fehler
        // beim Nutzer landen — es gibt schlicht nichts zu beenden.
        coEvery { pluginApi.getUiManifest() } returns PluginUiManifestDto(listOf(bothActionsPlugin))
        GetGamingModeActionsUseCase(pluginApi, cache)()
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } returns
            PluginMenuActionResultDto(
                ok = true,
                messageKey = "menu_end_steam_not_running",
                messageText = "Steam is not running - nothing to end"
            )

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals("Steam läuft nicht - nichts zu beenden", (result as Result.Success).data)
    }

    @Test
    fun `the end action falls back to message_text when the key was never translated`() = runTest {
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } returns
            PluginMenuActionResultDto(
                ok = false,
                messageKey = "menu_end_windows_failed",
                messageText = "Big Picture was closed, but the windows stayed up"
            )

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals(
            "Big Picture was closed, but the windows stayed up",
            (result as Result.Error).exception.message
        )
    }

    @Test
    fun `a 403 on the end action is reported as it being unavailable`() = runTest {
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } throws HttpException(
            Response.error<Any>(403, "".toResponseBody("application/json".toMediaTypeOrNull()))
        )

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals(
            "Gaming-Modus beenden nicht verfügbar",
            (result as Result.Error).exception.message
        )
    }

    @Test
    fun `an unreachable server is reported as such for the end action`() = runTest {
        coEvery { pluginApi.runMenuAction("steam_gaming", "gaming_mode_end") } throws
            java.io.IOException("connect timed out")

        val result = EndGamingModeUseCase(pluginApi, cache)()

        assertEquals("Server nicht erreichbar", (result as Result.Error).exception.message)
    }
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*GamingModeUseCaseTest"`
Expected: Kompilierfehler, `Unresolved reference: EndGamingModeUseCase`.

- [ ] **Step 3: UseCase implementieren**

`EndGamingModeUseCase.kt` vollständig:

```kotlin
package com.baluhost.android.domain.usecase.plugin

import com.baluhost.android.data.local.PluginTranslationCache
import com.baluhost.android.data.remote.api.PluginApi
import com.baluhost.android.util.Result
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Runs the steam_gaming plugin's gaming_mode_end action: Big Picture closed and
 * the desktop cleared.
 *
 * Deliberately leaves the displays alone — the server does too, because
 * "displays off" is its own power-menu entry.
 *
 * Note that "Steam is not running" comes back as ok=true: it is a no-op, not a
 * failure, and lands in [Result.Success] like any other successful outcome.
 *
 * Mirrors [StartGamingModeUseCase]: the plugin reports its own failures as
 * ok=false with a message key rather than as an HTTP error, so both branches are
 * mapped by hand and the caller always gets a finished German sentence.
 */
class EndGamingModeUseCase @Inject constructor(
    private val pluginApi: PluginApi,
    private val translationCache: PluginTranslationCache
) {
    suspend operator fun invoke(): Result<String> {
        return try {
            val response = pluginApi.runMenuAction(
                GamingMode.PLUGIN_NAME, GamingMode.END_ACTION_ID
            )
            val message = translationCache.resolve(
                GamingMode.PLUGIN_NAME, response.messageKey, response.messageText
            )
            if (response.ok) Result.Success(message) else Result.Error(Exception(message))
        } catch (e: HttpException) {
            Result.Error(Exception("Gaming-Modus beenden nicht verfügbar", e))
        } catch (e: Exception) {
            Result.Error(Exception("Server nicht erreichbar", e))
        }
    }
}
```

- [ ] **Step 4: Tests laufen lassen und grün bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS, XML-Zählung > 0.

- [ ] **Step 5: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/domain/usecase/plugin/EndGamingModeUseCase.kt" "app/src/test/java/com/baluhost/android/domain/usecase/plugin/GamingModeUseCaseTest.kt"
git commit -m "feat(plugin): add the gaming_mode_end action use case"
```

---

### Task 3: ViewModel-Anbindung

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt`
- Test: `app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelDesktopActionTest.kt`

**Interfaces:**
- Consumes: `GetGamingModeActionsUseCase` (Task 1), `EndGamingModeUseCase` (Task 2).
- Produces: `DashboardViewModel.gamingModeEndAvailable: StateFlow<Boolean>` und `DashboardViewModel.endGamingMode()` — beide von Task 4 verwendet.

- [ ] **Step 1: Failing Tests schreiben**

In `DashboardViewModelDesktopActionTest.kt`: Feld und Stub für den neuen UseCase ergänzen. Zu den Feld-Deklarationen (nach `startGamingModeUseCase`):

```kotlin
    private lateinit var endGamingModeUseCase: EndGamingModeUseCase
```

In `setup()` nach `startGamingModeUseCase = mockk()`:

```kotlin
        endGamingModeUseCase = mockk()
```

In `createViewModel()` als letztes Konstruktorargument nach `startGamingModeUseCase = startGamingModeUseCase`:

```kotlin
            endGamingModeUseCase = endGamingModeUseCase
```

Import ergänzen: `import com.baluhost.android.domain.usecase.plugin.EndGamingModeUseCase`.

Neue Tests ans Ende der Klasse:

```kotlin
    @Test
    fun `onPowerDialogOpened offers the end entry when the server advertises that direction`() = runTest {
        // Der Normalfall bei laufendem Gaming-Modus: der Server nennt genau
        // die Beenden-Richtung, die Start-Richtung verschwindet.
        coEvery { getGamingModeActionsUseCase() } returns
            GetGamingModeActionsUseCase.GamingModeActions(canStart = false, canEnd = true)
        val vm = createViewModel()

        vm.onPowerDialogOpened()

        assertFalse(vm.gamingModeAvailable.value)
        assertTrue(vm.gamingModeEndAvailable.value)
        clearViewModel(vm)
    }

    @Test
    fun `a server that advertises only the start action leaves the end entry hidden`() = runTest {
        coEvery { getGamingModeActionsUseCase() } returns
            GetGamingModeActionsUseCase.GamingModeActions(canStart = true, canEnd = false)
        val vm = createViewModel()

        vm.onPowerDialogOpened()

        assertTrue(vm.gamingModeAvailable.value)
        assertFalse(vm.gamingModeEndAvailable.value)
        clearViewModel(vm)
    }

    @Test
    fun `a second dialog opening takes the flipped direction from the server`() = runTest {
        // Der Zustand kippt durch die Aktion selbst: nach einem erfolgreichen
        // Start setzt der Server seinen Merker, das Manifest zeigt danach die
        // andere Richtung. Weil onPowerDialogOpened() bei jedem Öffnen neu
        // fragt, muss ein zuvor gesetztes Flag auch wieder zurückfallen —
        // sonst stünden nach einigen Klicks beide Einträge im Dialog.
        coEvery { getGamingModeActionsUseCase() } returns
            GetGamingModeActionsUseCase.GamingModeActions(canStart = true, canEnd = false)
        val vm = createViewModel()
        vm.onPowerDialogOpened()
        assertTrue(vm.gamingModeAvailable.value)

        coEvery { getGamingModeActionsUseCase() } returns
            GetGamingModeActionsUseCase.GamingModeActions(canStart = false, canEnd = true)

        vm.onPowerDialogOpened()

        assertFalse(vm.gamingModeAvailable.value)
        assertTrue(vm.gamingModeEndAvailable.value)
        clearViewModel(vm)
    }

    @Test
    fun `onPowerDialogOpened hides the end entry for a non-admin`() = runTest {
        every { preferencesManager.getUserRole() } returns flowOf("user")
        val vm = createViewModel()

        vm.onPowerDialogOpened()

        coVerify(exactly = 0) { getGamingModeActionsUseCase() }
        assertFalse(vm.gamingModeEndAvailable.value)
        clearViewModel(vm)
    }

    @Test
    fun `endGamingMode passes the plugin message on`() = runTest {
        coEvery { endGamingModeUseCase() } returns Result.Success("Gaming-Modus beendet")
        val vm = createViewModel()

        vm.snackbarEvent.test {
            vm.endGamingMode()

            assertEquals("Gaming-Modus beendet", awaitItem())
            expectNoEvents()
        }
        clearViewModel(vm)
    }

    @Test
    fun `endGamingMode leaves the desktop state untouched`() = runTest {
        // Die Beenden-Action fasst die Displays nicht an — anders als
        // startGamingMode() darf sie den bekannten Zustand also weder auf
        // RUNNING setzen noch auf UNKNOWN zurückwerfen. RUNNING wird vorher
        // gesetzt, damit die Assertion eine Nicht-Änderung beweist und nicht
        // bloß den UNKNOWN-Default trifft.
        coEvery { getDesktopStatusUseCase() } returns Result.Success(DesktopState.RUNNING)
        val vm = createViewModel()
        vm.onPowerDialogOpened()
        assertEquals(DesktopState.RUNNING, vm.desktopState.value)

        coEvery { endGamingModeUseCase() } returns Result.Success("Gaming-Modus beendet")

        vm.snackbarEvent.test {
            vm.endGamingMode()
            awaitItem()
        }

        assertEquals(DesktopState.RUNNING, vm.desktopState.value)
        clearViewModel(vm)
    }

    @Test
    fun `a refused endGamingMode reports the plugin message and keeps the desktop state`() = runTest {
        coEvery { getDesktopStatusUseCase() } returns Result.Success(DesktopState.RUNNING)
        val vm = createViewModel()
        vm.onPowerDialogOpened()
        assertEquals(DesktopState.RUNNING, vm.desktopState.value)

        coEvery { endGamingModeUseCase() } returns
            Result.Error(Exception("Es läuft noch ein Spiel"))

        vm.snackbarEvent.test {
            vm.endGamingMode()

            assertEquals("Es läuft noch ein Spiel", awaitItem())
        }
        // Auch der Fehlschlag sagt nichts über die Displays aus.
        assertEquals(DesktopState.RUNNING, vm.desktopState.value)
        clearViewModel(vm)
    }
```

- [ ] **Step 2: Tests laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*DashboardViewModelDesktopActionTest"`
Expected: Kompilierfehler, `Unresolved reference: endGamingModeUseCase` bzw. `gamingModeEndAvailable`.

- [ ] **Step 3: ViewModel erweitern**

Import bei den übrigen `plugin`-Imports (um Zeile 34) ergänzen:

```kotlin
import com.baluhost.android.domain.usecase.plugin.EndGamingModeUseCase
```

Konstruktor: nach `private val startGamingModeUseCase: StartGamingModeUseCase` ein Komma setzen und ergänzen:

```kotlin
    private val endGamingModeUseCase: EndGamingModeUseCase
```

Nach dem bestehenden `_gamingModeAvailable`-Paar (Zeilen 127–128) ergänzen:

```kotlin
    private val _gamingModeEndAvailable = MutableStateFlow(false)
    val gamingModeEndAvailable: StateFlow<Boolean> = _gamingModeEndAvailable.asStateFlow()
```

Die Zuweisung in `onPowerDialogOpened()` (aus Task 1, Step 6) ersetzen — ein Manifest-Aufruf, beide Flags, und beide Zweige weisen explizit zu, damit ein `false` eine Entscheidung ist und kein ausgelassener Wert:

```kotlin
            // gamingModeAvailable/gamingModeEndAvailable heißen "darf gezeigt
            // werden" — beide werden in beiden Zweigen explizit gesetzt, damit
            // ein false bei Nicht-Admins eine Entscheidung ist und kein durch
            // Auslassen stehengebliebener Wert. Die Plugin-Menü-Route ist
            // serverseitig admin-only, für andere könnte die Frage nur ein 403
            // ergeben. Ein Aufruf beantwortet beide Einträge.
            val gamingActions = if (_isAdmin.value) {
                getGamingModeActionsUseCase()
            } else {
                GetGamingModeActionsUseCase.GamingModeActions()
            }
            _gamingModeAvailable.value = gamingActions.canStart
            _gamingModeEndAvailable.value = gamingActions.canEnd
```

Nach `startGamingMode()` (endet Zeile 699) einfügen:

```kotlin
    fun endGamingMode() {
        viewModelScope.launch {
            _powerActionInProgress.value = true
            when (val result = endGamingModeUseCase()) {
                // desktopState bleibt in beiden Zweigen unangetastet: die Action
                // fasst die Displays nicht an, weil "Displays aus" ein eigener
                // Menüpunkt ist. Anders als bei startGamingMode() ist der zuvor
                // bekannte Zustand danach also weiterhin wahr — sowohl bei
                // Erfolg als auch bei jedem der vier Fehlerfälle.
                is Result.Success -> _snackbarEvent.emit(result.data)
                is Result.Error -> _snackbarEvent.emit(
                    result.exception.message ?: "Gaming-Modus beenden fehlgeschlagen"
                )
                else -> {}
            }
            _powerActionInProgress.value = false
        }
    }
```

- [ ] **Step 4: Tests laufen lassen und grün bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS, XML-Zählung > 0.

- [ ] **Step 5: Hilt-Graph prüfen**

Ein fehlendes Binding kompiliert sauber und fällt erst beim Zusammensetzen des Graphen auf; Unit-Tests bauen ihn nicht (siehe `di/CLAUDE.md`). `EndGamingModeUseCase` hat einen `@Inject`-Konstruktor und braucht daher kein Modul — bestätigt wird das trotzdem:

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt" "app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelDesktopActionTest.kt"
git commit -m "feat(dashboard): wire the end-gaming-mode action into the view model"
```

---

### Task 4: Menüeintrag im Power-Dialog und Doku

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt`
- Modify: `app/src/main/java/com/baluhost/android/domain/usecase/CLAUDE.md`

**Interfaces:**
- Consumes: `DashboardViewModel.gamingModeEndAvailable` und `DashboardViewModel.endGamingMode()` (Task 3); `PowerOptionButton(icon, label, description, color, onClick)`.
- Produces: nichts — Endpunkt der Kette.

Für diesen Task gibt es keinen automatisierten Test: es existiert kein `androidTest`-Sourceset und keine Compose-UI-Tests (siehe `app/src/test/CLAUDE.md`), die Prüfung ist der Build plus der Handtest in Step 5.

- [ ] **Step 1: Icon-Import ergänzen**

Bei den übrigen `androidx.compose.material.icons.filled.*`-Imports einsortieren (alphabetisch dort, wo die Datei es bereits tut):

```kotlin
import androidx.compose.material.icons.filled.Monitor
```

`Monitor` ist verfügbar, weil `app/build.gradle.kts:105` `androidx.compose.material:material-icons-extended` einbindet, und spiegelt das Icon, das der Server für denselben Eintrag angibt.

- [ ] **Step 2: Parameter an `ServerStatusStrip` durchreichen**

In der Signatur (ab Zeile 909) nach `gamingModeAvailable: Boolean,` ergänzen:

```kotlin
    gamingModeEndAvailable: Boolean,
```

und nach `onStartGamingMode: () -> Unit,`:

```kotlin
    onEndGamingMode: () -> Unit,
```

- [ ] **Step 3: Aufrufstelle versorgen**

Im Aufruf von `ServerStatusStrip` (um Zeile 265) nach `gamingModeAvailable = gamingModeAvailable,` ergänzen:

```kotlin
                        gamingModeEndAvailable = gamingModeEndAvailable,
```

und nach `onStartGamingMode = { viewModel.startGamingMode() },`:

```kotlin
                        onEndGamingMode = { viewModel.endGamingMode() },
```

Bei den `collectAsState()`-Zeilen (um Zeile 93) ergänzen:

```kotlin
    val gamingModeEndAvailable by viewModel.gamingModeEndAvailable.collectAsState()
```

- [ ] **Step 4: Menüeintrag rendern**

Direkt hinter den bestehenden `if (gamingModeAvailable) { … }`-Block (endet Zeile 1132), noch innerhalb desselben ONLINE-Zweigs:

```kotlin
                            // Eigenes Flag, kein when() über einen selbst
                            // geführten Zustand: welche Richtung gerade gilt,
                            // entscheidet seit BaluHost PR #500 der Server
                            // (Merker-Datei × Display-Zählung) und nennt genau
                            // eine davon im Manifest. Ob Big Picture läuft, ist
                            // von außen nicht messbar — die App könnte es also
                            // gar nicht nachrechnen und liest deshalb bloß ab.
                            // Zwei unabhängige Blöcke statt eines Umschalters
                            // halten sie zusätzlich gegen einen Serverstand
                            // zwischen #497 und #500 korrekt, der beide nennt.
                            // Kein Bestätigungsdialog — der Server bricht von
                            // sich aus ab, wenn ein Spiel läuft, und lässt die
                            // Displays unangetastet.
                            if (gamingModeEndAvailable) {
                                PowerOptionButton(
                                    icon = Icons.Default.Monitor,
                                    label = "Gaming-Modus beenden",
                                    description = "Big Picture schließen + Fenster minimieren",
                                    color = Sky400,
                                    onClick = {
                                        showPowerDialog = false
                                        onEndGamingMode()
                                    }
                                )
                            }
```

- [ ] **Step 5: Bauen und von Hand prüfen**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

Danach am Gerät gegen den echten Server (NAS online, als Admin). Der Durchlauf prüft vor allem, dass die Richtung mitwandert:

1. Power-Dialog öffnen, während der Gaming-Modus **nicht** läuft → **nur** „Gaming-Modus" steht da, „beenden" nicht.
2. „Gaming-Modus" klicken → Big Picture kommt hoch. Dialog erneut öffnen → jetzt steht dort **nur** „Gaming-Modus beenden". Das ist der Beweis, dass der Server-Merker greift und die App das Manifest wirklich bei jedem Öffnen neu holt.
3. „Gaming-Modus beenden" klicken (kein Spiel laufend) → Big Picture ist zu, Fenster minimiert, Snackbar „Gaming-Modus beendet". **Nicht** „Big Picture ist zu, aber die Fenster blieben offen" — dieser Fehlalarm war der Inhalt von PR #499 und darf nicht mehr auftreten.
4. Dialog erneut öffnen → wieder „Gaming-Modus".
5. Gegenprobe für den No-op: bei komplett geschlossenem Steam die Beenden-Action auslösen (sie erscheint dann normalerweise nicht mehr im Menü — falls doch nicht erreichbar, diesen Punkt als „nicht prüfbar" notieren statt ihn zu erzwingen) → Snackbar „Steam läuft nicht - nichts zu beenden", als Erfolg, **kein** Fehlerton.

Beobachtungen notieren; sie gehören zu denselben offenen Gerätetests wie der Sync-Status.

- [ ] **Step 6: Doku nachziehen**

In `app/src/main/java/com/baluhost/android/domain/usecase/CLAUDE.md`:

- Gesamtzahlen: `55 files total: 54 across ten subdirectories` → `56 files total: 55 across ten subdirectories`, und im selben Absatz `plus one top-level orchestrator` unverändert lassen.
- Der Absatz „**Bypassing the repository is common…**" nennt „Of the 55 files, roughly half" → auf `56` anpassen.
- Die Tabellenzeile `| plugin/ | 3 | Gaming-mode plugin availability check and launch, plus shared constants | PluginApi directly (2 use cases); GamingMode.kt is a constants object, not a use case |` ersetzen durch:
  `| plugin/ | 4 | Gaming-mode action discovery, launch and end, plus shared constants | PluginApi directly (3 use cases); GamingMode.kt is a constants object, not a use case |`
- Der Stichpunkt „**`IsGamingModeAvailableUseCase` returns `Boolean`, not `Result<Boolean>`, and swallows every exception.**" beschreibt eine Datei, die es nicht mehr gibt. Ersetzen durch:

```markdown
- **`GetGamingModeActionsUseCase` returns a plain `GamingModeActions`, not a `Result`, and swallows every exception.** This is deliberate and self-documented in its KDoc: it's a discovery call ("which of the plugin's menu actions does the server still offer?"), and the only sensible response to "cannot tell" is to hide the entries — there's no useful error message a `Result.Error` here could show the user. It answers both menu actions from one manifest call on purpose, because it runs on every opening of the power dialog.
```

- Der Absatz „**`plugin/`'s specific precedent is `system/GetEnergyDashboardUseCase`…**" nennt `IsGamingModeAvailableUseCase`/`StartGamingModeUseCase` — auf `GetGamingModeActionsUseCase`/`StartGamingModeUseCase`/`EndGamingModeUseCase` erweitern und „these three call sites" auf „these four call sites" korrigieren.
- Der Stichpunkt „**`plugin/GamingMode.kt`** … counting toward that directory's 3 files" → `4 files`.

`presentation/ui/CLAUDE.md` braucht keine Änderung: die Zeile zum `dashboard/`-Verzeichnis nennt „power actions (wake/sleep/suspend, desktop enable/disable, gaming mode)" und bleibt zutreffend.

- [ ] **Step 7: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardScreen.kt" "app/src/main/java/com/baluhost/android/domain/usecase/CLAUDE.md"
git commit -m "feat(dashboard): offer 'end gaming mode' in the power dialog"
```

---

## Verifikation zum Abschluss

- [ ] `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache` — grün, **und** die XML-Zählung aus `app/src/test/CLAUDE.md` bestätigt einen echten Lauf.
- [ ] `./gradlew assembleDebug` — grün (Hilt-Graph).
- [ ] Handtest aus Task 4, Step 5 durchgeführt und Ergebnis notiert.
- [ ] `./gradlew assembleRelease` bleibt **außerhalb** dieses Plans: der erste Release-Build dieses Repos ist ein eigener, noch offener Punkt (R8 + Retrofit ohne Keep-Regeln) und darf nicht an dieser Änderung festgemacht werden.
