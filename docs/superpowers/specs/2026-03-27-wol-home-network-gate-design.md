# WoL-Button mit Heimnetz-Gate

**Datum:** 2026-03-27
**Status:** Approved

## Zusammenfassung

Wenn der Server nicht erreichbar ist (OFFLINE/UNKNOWN) und der User per BSSID im Heimnetz ist, wird der Fritz!Box-Hoststatus geprüft. Je nach Ergebnis zeigt der Power-Dialog entweder den WoL-Button oder einen Hinweis mit Link zu den Fritz!Box-Settings.

## Anforderungen

1. Server OFFLINE + Heimnetz (nur BSSID-Match, kein VPN) → Fritz!Box-Status prüfen
2. Fritz!Box meldet Host "inactive" → WoL-Button im Power-Dialog anzeigen
3. Fritz!Box-Prüfung schlägt fehl (unreachable, auth error, nicht konfiguriert) → Hinweis + Link zu Fritz!Box-Settings
4. Nicht im Heimnetz → wie bisher ("Server nicht erreichbar")
5. Server ONLINE oder SLEEPING → bestehende Logik unverändert

## Design

### Neuer State: WolAvailability

```kotlin
enum class WolAvailability {
    AVAILABLE,           // Fritz!Box meldet Host "inactive" -> WoL-Button zeigen
    NOT_ON_HOME_NETWORK, // Kein BSSID-Match -> "Server nicht erreichbar"
    FRITZ_BOX_ERROR,     // Heimnetz, aber Fritz!Box-Pruefung fehlgeschlagen
    CHECKING,            // Pruefung laeuft
    NOT_NEEDED           // Server ist ONLINE oder SLEEPING (bestehende Logik)
}
```

Neuer `_wolAvailability: StateFlow<WolAvailability>` im `DashboardViewModel`.

### BSSID-only Check in NetworkStateManager

Neue Methode, die nur BSSID vergleicht (kein VPN, kein Subnet-Fallback):

```kotlin
fun isOnHomeNetworkByBssid(): Boolean {
    val current = bssidReader.getCurrentBssid() ?: return false
    val stored = cachedHomeBssid ?: return false
    return current == stored
}
```

### Detailliertes NAS-Status-Ergebnis

Neuer sealed class als Return-Type fuer `checkNasStatus()`:

```kotlin
sealed class NasStatusResult {
    data class Resolved(val status: NasStatus) : NasStatusResult()
    object FritzBoxUnreachable : NasStatusResult()
    object FritzBoxAuthError : NasStatusResult()
    object FritzBoxNotConfigured : NasStatusResult()
}
```

### PowerRepositoryImpl.checkNasStatus() Mapping

| WolResult | NasStatusResult |
|---|---|
| `Success` | `Resolved(ONLINE)` |
| `Error("inactive")` | `Resolved(SLEEPING)` |
| `Error(other)` | `Resolved(OFFLINE)` |
| `AuthError` | `FritzBoxAuthError` |
| `Unreachable` | `FritzBoxUnreachable` |
| MAC-Adresse leer | `FritzBoxNotConfigured` |

### Logik-Flow in DashboardViewModel.updateNasStatus()

1. Telemetry OK → `NasStatus.ONLINE`, `wolAvailability = NOT_NEEDED`
2. Telemetry fehlgeschlagen → `checkNasStatusUseCase()`:
   - `Resolved(SLEEPING)` → `NasStatus.SLEEPING`, `wolAvailability = NOT_NEEDED`
   - `Resolved(ONLINE)` → `NasStatus.ONLINE`, `wolAvailability = NOT_NEEDED`
   - `Resolved(OFFLINE)` → `NasStatus.OFFLINE` + BSSID-Check:
     - Im Heimnetz → `wolAvailability = AVAILABLE` (Fritz!Box erreichbar, Host wirklich offline)
     - Nicht im Heimnetz → `wolAvailability = NOT_ON_HOME_NETWORK`
   - `FritzBoxUnreachable` / `FritzBoxAuthError` / `FritzBoxNotConfigured`:
     - `NasStatus.OFFLINE` + BSSID-Check:
       - Im Heimnetz → `wolAvailability = FRITZ_BOX_ERROR`
       - Nicht im Heimnetz → `wolAvailability = NOT_ON_HOME_NETWORK`

### UI-Aenderungen im Power-Dialog

`ServerStatusStrip` bekommt neue Parameter:
- `wolAvailability: WolAvailability`
- `onNavigateToFritzBoxSettings: () -> Unit`

Der `else`-Branch (OFFLINE/UNKNOWN) wird kontextabhaengig:

```
when (wolAvailability) {
    AVAILABLE -> WoL-Button (Icon: WbSunny, "NAS aufwecken", "Wake-on-LAN ueber Fritz!Box")
    FRITZ_BOX_ERROR -> Text "Fritz!Box nicht erreichbar" + TextButton "Konfiguration pruefen"
    CHECKING -> CircularProgressIndicator
    NOT_ON_HOME_NETWORK -> Text "Server nicht erreichbar" (wie bisher)
    NOT_NEEDED -> nicht erreichbar (ONLINE/SLEEPING-Branch greift vorher)
}
```

## Betroffene Dateien

| Datei | Aenderung |
|---|---|
| `util/NetworkStateManager.kt` | Neue Methode `isOnHomeNetworkByBssid()` |
| `domain/repository/PowerRepository.kt` | Return-Type `checkNasStatus()` -> `NasStatusResult` |
| `data/repository/PowerRepositoryImpl.kt` | Detaillierteres Mapping der WolResult-Werte |
| `domain/usecase/power/CheckNasStatusUseCase.kt` | Return-Type anpassen auf `NasStatusResult` |
| `domain/model/NasStatusResult.kt` | Neuer sealed class (oder in bestehendes Model-File) |
| `presentation/ui/screens/dashboard/DashboardViewModel.kt` | Neuer `wolAvailability` State + erweiterte `updateNasStatus()` |
| `presentation/ui/screens/dashboard/DashboardScreen.kt` | Power-Dialog: WoL bei Offline + Fritz!Box-Settings-Link |

## Was sich NICHT aendert

- `FritzBoxTR064Client` — unveraendert
- `SendWolUseCase` — unveraendert
- `BssidReader` — unveraendert
- `PreferencesManager` — unveraendert
- SLEEPING-Branch im Power-Dialog — unveraendert
- ONLINE-Branch im Power-Dialog — unveraendert
