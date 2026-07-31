# Display-Toggle und Gaming-Modus in der Android-App

**Datum:** 2026-07-31
**Status:** Design abgenommen

## Problem

Die BaluHost-Webapp bietet im Power-Menü sechs Aktionen: Restart, Shutdown, Sleep,
Standby, „Disable desktop" und „Gaming Mode". Die Android-App kann davon nur Soft
Sleep, Suspend und Wake. Die beiden Desktop-bezogenen Aktionen fehlen — obwohl der
Server sie längst anbietet und die App die nötige Berechtigung bereits vom Server
geliefert bekommt und verwirft.

Restart und Shutdown bleiben bewusst außen vor: sie sind nicht Teil dieses Projekts.

## Server-Kontrakt (bestehend, wird nur genutzt)

Am Server ist **nichts** zu ändern.

### Display-Toggle — Core-REST-API

Definiert in `backend/app/api/routes/desktop.py`, registriert unter dem Prefix
`/api/system/sleep/desktop`.

| Methode | Pfad | Auth | Antwort |
|---|---|---|---|
| `GET` | `/api/system/sleep/desktop/status` | jeder authentifizierte User | `{state, display_manager, detail}` |
| `POST` | `/api/system/sleep/desktop/disable` | Admin **oder** `can_toggle_desktop` | `{success, message}` |
| `POST` | `/api/system/sleep/desktop/enable` | Admin **oder** `can_toggle_desktop` | `{success, message, session_unlocked, unlock_message}` |

`state` ist `running`, `stopped` oder `unknown`.

`enable` versucht zusätzlich, den Sperrbildschirm zu entsperren. Das ist ein Add-on
mit eigener Permission (`can_unlock_session`) und der Anforderung, dass der Request
aus LAN/VPN kommt. Schlägt es fehl, ist die Aktion trotzdem erfolgreich — die
Displays sind an. Die Webapp zeigt in dem Fall den Zusatzhinweis
„Displays on – the session is still locked".

`unlock_message` ist ein englischer Debug-String und gehört laut Webapp-Kommentar
(#406) nicht in die UI. Die App zeigt ihn ebenfalls nicht an.

### Gaming-Modus — Plugin-Menü-Beitrag

Kein Core-Endpoint. Der Eintrag stammt aus dem gebundelten Plugin `steam_gaming`
(`backend/app/plugins/installed/steam_gaming/__init__.py`).

| Methode | Pfad | Auth | Antwort |
|---|---|---|---|
| `GET` | `/api/plugins/ui/manifest` | jeder authentifizierte User | `{plugins: [{name, menu_items, translations, …}]}` |
| `POST` | `/api/plugins/{name}/menu-actions/{action_id}` | **nur Admin** | `{ok, message_key, message_text}` |

Plugin-Name `steam_gaming`, Action-ID `gaming_mode`.

Serverseitiger Ablauf: Displays einschalten → Session entsperren (falls erlaubt) →
Steam Big Picture starten. Fehlschläge kommen als `ok: false` mit eigenem
`message_key` zurück, **nicht** als HTTP-Fehler.

Das Plugin liefert deutsche Übersetzungen für seine Message-Keys im Manifest mit
(`menu_steam_failed` → „Displays sind an, aber Steam startete nicht").

Die Menü-Action-Route nutzt `get_current_admin` — es gibt keine delegierbare
Permission für Plugin-Menü-Aktionen. Der Gaming-Modus ist damit in der App
zwingend Admin-only.

### Berechtigungen

`GET /api/system/sleep/my-permissions` (`routes/sleep.py:349`) liefert **bereits**
`can_toggle_desktop` und `can_unlock_session` mit. Die App verwirft beide Felder
heute in `MyPowerPermissionsDto`.

## Designentscheidungen

**Gaming-Modus wird hybrid angebunden.** Verfügbarkeit zur Laufzeit aus dem
UI-Manifest geprüft, Label/Icon/Beschreibung fest auf Deutsch in der App. Nicht die
volle generische Plugin-Menü-Maschinerie der Webapp (Icon-Mapping, `label_key`-
Auflösung, Tone-Paletten), denn `steam_gaming` ist ein gebundeltes Plugin — ein
fester Bestandteil von BaluHost. Der Verfügbarkeits-Check bleibt trotzdem nötig:
gebundelt heißt mitgeliefert, nicht unabschaltbar (`is_enabled`), und
`ui/manifest` listet nur aktive Plugins. Kommen später weitere Plugin-Aktionen
dazu, ist der Schritt zum Generischen klein.

**Display-Toggle nur bei `NasStatus.ONLINE`.** Im Soft-Sleep sind die Services
pausiert; ein Display-Toggle wäre dort bestenfalls wirkungslos.

**Keine Bestätigungsdialoge** für die beiden neuen Aktionen. Wie in der Webapp:
sofort feuern, Rückmeldung per Snackbar. Beides ist harmlos und sofort umkehrbar.
Soft Sleep, Suspend, WoL und Wake behalten ihre Bestätigung; das `PowerAction`-Enum
bleibt unverändert.

**Statuswechsel-Eintrag statt zwei Buttons.** Ein Slot, dessen Label und Icon vom
`desktop/status` abhängen — wie die Webapp es macht.

## Architektur

Die App folgt MVVM + Clean Architecture: API → DTO → Repository → UseCase →
ViewModel → Composable. DI über Hilt.

### Data-Layer

`data/remote/dto/PowerDto.kt`:

- `MyPowerPermissionsDto` erhält `can_toggle_desktop` und `can_unlock_session`.
- Neu: `DesktopStatusDto(state, display_manager, detail)`.
- Neu: `DesktopActionResponseDto(success, message, session_unlocked: Boolean?, unlock_message: String?)`.
  Deckt beide Routen ab — bei `disable` bleiben die zwei Zusatzfelder `null`.

`data/remote/dto/PluginConfigDto.kt` (bestehende Datei):

- `PluginUiManifestDto(plugins: List<PluginUiInfoDto>)`
- `PluginUiInfoDto(name, menu_items: List<PluginMenuItemDto>, translations: Map<String, Map<String, String>>?)`
- `PluginMenuItemDto(id)` — mehr braucht die App nicht; Gson ignoriert unbekannte Felder.
- `PluginMenuActionResultDto(ok, message_key, message_text)`

`translations` kommt mit, damit Fehlermeldungen des Plugins auf Deutsch angezeigt
werden können. Auflösung: `translations["de"][message_key]`, Fallback auf
`message_text`.

`data/remote/api/SleepApi.kt`:

```kotlin
@GET("system/sleep/desktop/status")
suspend fun getDesktopStatus(): DesktopStatusDto

@POST("system/sleep/desktop/disable")
suspend fun disableDesktop(): DesktopActionResponseDto

@POST("system/sleep/desktop/enable")
suspend fun enableDesktop(): DesktopActionResponseDto
```

`data/remote/api/PluginApi.kt`:

```kotlin
@GET("plugins/ui/manifest")
suspend fun getUiManifest(): PluginUiManifestDto

@POST("plugins/{name}/menu-actions/{actionId}")
suspend fun runMenuAction(
    @Path("name") name: String,
    @Path("actionId") actionId: String
): PluginMenuActionResultDto
```

`SleepApi` und `PluginApi` sind in `NetworkModule.kt` bereits provided
(Zeilen 204 bzw. 210) — keine DI-Änderung nötig.

### Domain-Layer

`domain/model/PowerPermissions.kt`:

```kotlin
data class PowerPermissions(
    val canSoftSleep: Boolean = false,
    val canWake: Boolean = false,
    val canSuspend: Boolean = false,
    val canWol: Boolean = false,
    val canToggleDesktop: Boolean = false,
    val canUnlockSession: Boolean = false
) {
    val hasAnyPermission: Boolean
        get() = canSoftSleep || canWake || canSuspend || canWol || canToggleDesktop
}
```

`canToggleDesktop` **muss** in `hasAnyPermission` einfließen — sonst bleibt der
Power-Button für einen User verborgen, der ausschließlich Desktop-Rechte hat.
`canUnlockSession` fließt bewusst nicht ein: es ist kein eigenständiges Recht,
sondern ein Add-on zu `canToggleDesktop`.

Neu `domain/model/DesktopState.kt`:

```kotlin
enum class DesktopState { RUNNING, STOPPED, UNKNOWN }
```

Neu `domain/model/DesktopActionResult.kt`:

```kotlin
data class DesktopActionResult(
    val message: String,
    val sessionUnlocked: Boolean?
)
```

`domain/repository/PowerRepository.kt` erhält:

```kotlin
suspend fun getDesktopStatus(): Result<DesktopState>
suspend fun enableDesktop(): Result<DesktopActionResult>
suspend fun disableDesktop(): Result<String>
```

Implementierung in `PowerRepositoryImpl` nach dem bestehenden Muster
(`HttpException` → spezifische Meldung, sonstige `Exception` → „Server nicht
erreichbar"). Unbekannte `state`-Strings mappen auf `UNKNOWN`.

Neue Use Cases in `domain/usecase/power/`:

- `GetDesktopStatusUseCase`
- `EnableDesktopUseCase`
- `DisableDesktopUseCase`

Neue Use Cases in `domain/usecase/plugin/`:

- `IsGamingModeAvailableUseCase` — holt das Manifest, sucht `steam_gaming` mit
  einem `menu_items`-Eintrag `gaming_mode`, legt die `de`-Übersetzungsmap im Cache
  ab und gibt `Boolean` zurück. Fehler → `false`.
- `StartGamingModeUseCase` — ruft die Menü-Action, löst `message_key` über den
  Cache auf und gibt `Result<String>` zurück (`ok: false` → `Result.Error` mit der
  aufgelösten Meldung).

Beide injizieren `PluginApi` direkt, ohne Repository — das ist das etablierte
Muster der App für Plugin-Zugriffe (`GetEnergyDashboardUseCase`,
`PowerDetailViewModel`).

Neu `data/local/PluginTranslationCache.kt` (`@Singleton`): hält die zuletzt aus dem
Manifest gelesene `de`-Map pro Plugin im Speicher, damit `StartGamingModeUseCase`
den `message_key` auflösen kann, ohne das Manifest erneut zu holen. Kein
persistenter Speicher — die Map wird bei jedem Öffnen des Power-Dialogs frisch
gefüllt.

### ViewModel

`DashboardViewModel` erhält:

- `desktopState: StateFlow<DesktopState>` (initial `UNKNOWN`)
- `gamingModeAvailable: StateFlow<Boolean>` (initial `false`)
- `onPowerDialogOpened()` — lädt `desktop/status` und die Gaming-Verfügbarkeit
- `enableDesktop()`, `disableDesktop()`, `startGamingMode()`

**Ladezeitpunkt:** beim Öffnen des Power-Dialogs, wie in der Webapp (`useEffect`
auf `isOpen`). Kein zusätzlicher Traffic im Polling-Loop. Alte Werte bleiben
während des Nachladens stehen, also kein Flackern des Eintrags.

`onPowerDialogOpened()` fragt die Gaming-Verfügbarkeit nur ab, wenn der User Admin
ist — für alle anderen ist die Aktion ohnehin gesperrt.

**Optimistische Statusaktualisierung:** Nach erfolgreichem `enableDesktop()` oder
`startGamingMode()` wird `desktopState` auf `RUNNING` gesetzt, nach erfolgreichem
`disableDesktop()` auf `STOPPED`. Der Server hat die Displays gerade geschaltet;
ein Nachfragen wäre eine überflüssige Runde.

**Snackbar — genau eine Emission pro Aktion.** `_snackbarEvent` ist ein
`MutableSharedFlow` mit `extraBufferCapacity = 1`; zwei schnell aufeinanderfolgende
Emissionen würden eine verschlucken. Deshalb wird der Sperrbildschirm-Hinweis in
die eine Meldung gefaltet:

> **Korrektur (2026-07-31, Review-Fixwelle):** Die obige Begründung ist falsch.
> `extraBufferCapacity = 1` hat als Default `BufferOverflow.SUSPEND`, nicht
> `DROP_OLDEST`, und der Code ruft `emit`, nicht `tryEmit` — eine zweite
> Emission würde also puffern und zugestellt, nicht verschluckt. Die
> Entscheidung, beide Aussagen in eine Meldung zu falten, bleibt richtig
> (eine Meldung statt zwei Toasts ist die bessere UX, und der
> Sperrbildschirm-Hinweis ist ein Qualifikator desselben Ergebnisses, kein
> eigenes Ereignis) — nur die hier genannte Begründung war es nicht. Siehe
> Kommentar in `DashboardViewModel.enableDesktop()`.

| Ergebnis | Meldung |
|---|---|
| `enable` ok, `session_unlocked == false` | „Displays an – Session ist noch gesperrt" |
| `enable` ok, sonst | „Displays aktiviert" |
| `disable` ok | „Displays deaktiviert" |
| Gaming ok | „Gaming-Modus gestartet" |
| Fehler | Fehlermeldung aus `Result.Error` |

**Admin-Fallback:** Schlägt `getMyPermissions()` fehl und der User ist Admin,
setzt die App heute alle Flags auf `true`. Die beiden neuen Flags müssen dort
ergänzt werden — sonst verschwindet der Display-Eintrag für Admins, sobald der
Permissions-Abruf scheitert.

### UI

`ServerStatusStrip` in `DashboardScreen.kt` erhält neue Parameter:
`desktopState`, `gamingModeAvailable`, `onPowerDialogOpened`, `onEnableDesktop`,
`onDisableDesktop`, `onStartGamingMode`.

Das Setzen von `showPowerDialog = true` ruft zusätzlich `onPowerDialogOpened()`.

Der `NasStatus.ONLINE`-Zweig des Power-Dialogs bekommt zwei Einträge unterhalb von
Soft Sleep und Suspend. Die Zweige `SLEEPING`, `OFFLINE` und `UNKNOWN` bleiben
unverändert.

```
NasStatus.ONLINE
┌────────────────────────────────────┐
│ 🛏  Soft Sleep            [confirm] │
│ ⏻  Suspend               [confirm] │
│ 🖥  Display deaktivieren            │  ← neu, statusabhängig
│ 🎮  Gaming-Modus                    │  ← neu, nur Admin
└────────────────────────────────────┘
```

Display-Eintrag, ein Slot mit zwei Zuständen:

| `desktopState` | Label | Beschreibung | Icon | Farbe |
|---|---|---|---|---|
| `RUNNING` | „Display deaktivieren" | „Displays ausschalten, spart GPU-Strom" | `Icons.Default.DesktopAccessDisabled` | `Sky400` |
| `STOPPED` | „Display aktivieren" | „Displays wieder einschalten" | `Icons.Default.DesktopWindows` | `Green500` |
| `UNKNOWN` | *nichts rendern* | | | |

Sichtbarkeit Display-Eintrag: `isAdmin || powerPermissions.canToggleDesktop`.

Gaming-Modus-Eintrag: `Icons.Default.SportsEsports`, Farbe `Violet500`
(`theme/Color.kt:41`), Label „Gaming-Modus", Beschreibung „Displays an + Big
Picture". Sichtbarkeit: `isAdmin && gamingModeAvailable`. Violett grenzt den
Eintrag deutlich vom `Sky400` des Display-Toggles ab, das direkt darüber steht.

Beide Einträge schließen den Dialog und rufen ihren Callback direkt auf — kein
Eintrag im `PowerAction`-Enum, keine Bestätigung.

Alle Icons stammen aus `material-icons-extended`, das in `app/build.gradle.kts:101`
bereits eingebunden ist.

## Fehlerverhalten

| Fall | Verhalten |
|---|---|
| `desktop/status` schlägt fehl | `UNKNOWN` → kein Display-Eintrag, keine Snackbar (stiller Discovery-Call) |
| `ui/manifest` schlägt fehl oder Plugin deaktiviert | `gamingModeAvailable = false` → kein Eintrag, stumm |
| `enable`/`disable` liefert HTTP 403 | Snackbar mit Fehlermeldung — die Permission wurde entzogen, seit die App sie geladen hat |
| `enable`/`disable` liefert `success: false` | Snackbar mit der Server-`message` |
| Gaming-Action liefert `ok: false` | Snackbar mit der auf Deutsch aufgelösten Plugin-Meldung |
| Gaming-Action liefert HTTP 403/404 | „Gaming-Modus nicht verfügbar" |
| Kein Netz | „Server nicht erreichbar" (bestehendes Muster in `PowerRepositoryImpl`) |

## Tests

Unit-Tests auf ViewModel-Ebene mit Fake-Use-Cases, nach dem Muster von
`app/src/test/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModelVpnActionTest.kt`:

1. Ein User mit ausschließlich `canToggleDesktop` bekommt `hasAnyPermission == true`,
   der Power-Button ist also sichtbar.
2. `onPowerDialogOpened()` setzt `desktopState` aus der Server-Antwort.
3. `onPowerDialogOpened()` fragt die Gaming-Verfügbarkeit nicht ab, wenn der User
   kein Admin ist.
4. `enableDesktop()` mit `session_unlocked = false` emittiert genau eine Snackbar
   mit dem Sperrbildschirm-Hinweis.
5. `enableDesktop()` mit `session_unlocked = true` emittiert „Displays aktiviert".
6. `enableDesktop()` / `disableDesktop()` setzen `desktopState` auf `RUNNING` bzw.
   `STOPPED`.
7. Manifest-Fehler → `gamingModeAvailable == false`, keine Snackbar.
8. `StartGamingModeUseCase` löst `message_key` über die deutsche Übersetzungsmap auf
   und fällt bei fehlendem Key auf `message_text` zurück.
9. `getMyPermissions()`-Fehler bei Admin → auch `canToggleDesktop` ist `true`.

## Bewusst nicht enthalten

- **Restart und Shutdown.** Die Webapp bietet sie an, die App nicht — das ist eine
  eigene Entscheidung und nicht Teil dieses Projekts.
- **Gaming-Modus für delegierte User.** Die Server-Route ist hart Admin-only.
  Das zu ändern hieße, eine Permission für Plugin-Menü-Aktionen einzuführen — eine
  Server-Änderung und damit ein separates Projekt.
- **Generische Plugin-Menü-Darstellung.** Siehe Designentscheidungen.
- **Ein eigener „Desktop"-Abschnitt mit Trennlinie** im Dialog, wie die Webapp ihn
  hat. Bei vier Einträgen lohnt die Gliederung nicht.
