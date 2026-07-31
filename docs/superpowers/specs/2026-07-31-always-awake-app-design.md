# Always-Awake in der Android-App

**Datum:** 2026-07-31
**Status:** Design abgenommen

## Problem

Die BaluHost-Webapp bietet unter „Sleep Mode" ein Always-Awake-Panel: einen
Hauptschalter, der jede automatische Schlafautomatik übersteuert, wahlweise
befristet oder dauerhaft. Die Android-App kennt das Feature nicht — sie hat
überhaupt keine Sleep- oder Power-Einstellungen. Wer den Server für einen Abend
wachhalten will, braucht dafür bisher einen Rechner.

Das Feature ist **Admin-only**, und zwar hart: die Konfigurationsrouten des
Servers hängen an `get_current_admin`. Anders als beim Display-Toggle gibt es
keine delegierbare Permission.

## Server-Kontrakt (bestehend, wird nur genutzt)

Am Server ist **nichts** zu ändern. Alle Angaben unten wurden gegen
`D:\Programme (x86)\Baluhost` geprüft, nicht aus der Webapp abgeleitet.

| Methode | Pfad | Auth | Zweck |
|---|---|---|---|
| `GET` | `/api/system/sleep/config` | **nur Admin** (`get_current_admin`) | liefert `SleepConfigResponse` |
| `PUT` | `/api/system/sleep/config` | **nur Admin** (`get_current_admin`) | Teilaktualisierung |
| `GET` | `/api/system/sleep/status` | jeder eingeloggte User | enthält `always_awake` |

Belegstellen: `backend/app/api/routes/sleep.py:211-252`,
`backend/app/schemas/sleep.py:87-93, 205-207, 236-258`,
`backend/app/services/power/sleep.py:1339-1369`,
`backend/app/models/sleep.py:54-58`.

### Felder

`SleepConfigResponse` und `SleepConfigUpdate` führen beide:

- `always_awake_enabled: bool`
- `always_awake_until: datetime | null` — UTC-Ablaufzeit, `null` bedeutet
  **permanent**

`SleepStatusResponse.always_awake` ist ein `AlwaysAwakeStatus` mit `enabled`,
`until` und zusätzlich `expires_in_seconds` für einen Countdown. Diese Route
braucht keine Admin-Rechte — für dieses Projekt wird sie nicht genutzt, siehe
„Bewusst nicht enthalten".

### Serverseitige Prüfung

`SleepConfigUpdate._validate_until_future` prüft genau zwei Dinge:

1. `v <= now` wird abgelehnt („must be in the future (UTC)")
2. `v > now + 7 Tage` wird abgelehnt („at most 7 days in the future")

**Eine Fünf-Minuten-Untergrenze existiert am Server nicht.** Das
`MIN_HORIZON_MS = 5 * 60 * 1000` in `AlwaysAwakePanel.tsx:27` ist eine reine
Bedienkonvention der Webapp. Die App übernimmt sie — aber als bewusste
Bedienentscheidung, nicht als Serveranforderung: eine Ablaufzeit, die beim
Antippen schon fast erreicht ist, hilft niemandem.

### Zwei Eigenheiten, die den Ausschlag geben

**Explizites `null` ist nicht dasselbe wie „nicht gesendet".**
`update_config` behandelt das Feld gesondert:

```python
# Special-case: always_awake_until accepts explicit None to clear.
if "always_awake_until" in update_data:
    config.always_awake_until = update_data.pop("always_awake_until")
```

Da `update_data` aus `model_dump(exclude_unset=True)` stammt, ist der Schlüssel
nur vorhanden, wenn der Client ihn tatsächlich geschickt hat. Für „permanent"
muss also `"always_awake_until": null` **im JSON stehen**. Fehlt es, bleibt die
alte Ablaufzeit erhalten — stillschweigend.

**Ausschalten räumt selbst auf.** `if update_data.get("always_awake_enabled") is
False: config.always_awake_until = None`. Beim Deaktivieren genügt also ein
einziges Feld.

### Datumsformat

Die Spalte ist `DateTime(timezone=True)`, aber der Validator normalisiert
ausdrücklich zeitzonenlose Werte:

```python
if v.tzinfo is None:
    v = v.replace(tzinfo=timezone.utc)
```

Dass diese Zeile existiert, heißt: naive Zeitstempel kommen vor. Die App darf
deshalb **nicht** annehmen, dass die Antwort ein `Z` oder einen Offset trägt.
Gelesen wird beides, ein Wert ohne Zeitzone gilt als UTC — genau wie am Server.
Gesendet wird ISO-8601 mit `Z`, wie es die Webapp tut
(`new Date(...).toISOString()`).

## Designentscheidungen

**Eigener Bildschirm statt Abschnitt.** `SettingsScreen.kt` hat bereits 914
Zeilen und vier Abschnitte. Für Substanzielles gibt es im Repo den etablierten
Weg über einen eigenen Bildschirm — `FritzBoxSettingsScreen` wird genauso aus
den Einstellungen heraus aufgerufen. Das hält die große Datei klein und gibt
Presets und Restlaufzeit Platz.

**Volle Parität zur Webapp**, inklusive freiem Zeitpunkt. Presets decken den
Alltag ab, aber wer den Server über ein Wochenende wachhalten will, soll das
nicht am Rechner erledigen müssen.

**Der Request-Body wird von Hand gebaut.** Nicht aus einem Daten-DTO
serialisiert, sondern als `JsonObject`, das über `.toString()` in einen
`RequestBody` geht. Grund ist die Null-Eigenheit oben: Gsons reflektive
Serialisierung lässt Null-Felder weg (`serializeNulls` ist standardmäßig
`false`), und auch ein `JsonNull` in einem `JsonObject` wird von dem
JsonWriter übersprungen, den `GsonConverterFactory` erzeugt.
`JsonElement.toString()` dagegen schreibt Nulls mit. Vier Zeilen, keine
Änderung an der DI, und im Test überprüfbar.

**Eigenes Repository statt `PowerRepository`.** Letzteres bündelt Power-
*Aktionen* (Sleep, Suspend, Wake, Display-Toggle). Always-Awake ist
Konfiguration und bekommt ein eigenes, kleines `SleepConfigRepository`.

## Architektur

Die App folgt MVVM + Clean Architecture: API → DTO → Repository → UseCase →
ViewModel → Composable. DI über Hilt.

### Data-Layer

`data/remote/api/SleepApi.kt` erhält:

```kotlin
@GET("system/sleep/config")
suspend fun getSleepConfig(): SleepConfigDto

@PUT("system/sleep/config")
suspend fun updateSleepConfig(@Body body: RequestBody): SleepConfigDto
```

Der `RequestBody` statt eines DTOs ist Absicht — siehe Null-Eigenheit.

Neu `data/remote/dto/SleepConfigDto.kt`:

```kotlin
data class SleepConfigDto(
    @SerializedName("always_awake_enabled")
    val alwaysAwakeEnabled: Boolean = false,
    @SerializedName("always_awake_until")
    val alwaysAwakeUntil: String? = null
)
```

Mehr braucht die App nicht; Gson ignoriert die übrigen Felder der Antwort.

### Domain-Layer

Neu `domain/model/AlwaysAwake.kt`:

```kotlin
data class AlwaysAwake(
    val enabled: Boolean,
    val until: Instant?
)
```

`until == null` bei `enabled == true` heißt permanent.

Neu `domain/repository/SleepConfigRepository.kt`:

```kotlin
suspend fun getAlwaysAwake(): Result<AlwaysAwake>
suspend fun setAlwaysAwake(enabled: Boolean, until: Instant?): Result<AlwaysAwake>
```

`SleepConfigRepositoryImpl` baut den Body:

- ausschalten → `{"always_awake_enabled": false}` (der Server räumt die
  Ablaufzeit selbst weg)
- permanent → `{"always_awake_enabled": true, "always_awake_until": null}`
- befristet → `{"always_awake_enabled": true, "always_awake_until": "<ISO-8601 Z>"}`

Fehlerbehandlung nach bestehendem Muster in `PowerRepositoryImpl`:
`HttpException` → spezifische deutsche Meldung, sonstige `Exception` → „Server
nicht erreichbar".

Use Cases in `domain/usecase/power/`: `GetAlwaysAwakeUseCase`,
`SetAlwaysAwakeUseCase`.

**Uhr als Abhängigkeit.** Presets und Grenzprüfungen rechnen gegen „jetzt". Wird
dafür `Instant.now()` fest verdrahtet, hängen die Tests an der Systemzeit und
die Grenzfälle (genau 5 Minuten, genau 7 Tage) sind nicht prüfbar. Das
`AlwaysAwakeViewModel` bekommt deshalb eine Zeitquelle injiziert:

```kotlin
fun interface Clock {
    fun now(): Instant
}
```

Produktionsbindung in `AppModule` nach dem Muster von `provideNetworkMonitor`:
`@Provides @Singleton fun provideClock(): Clock = Clock { Instant.now() }`.

### Presentation-Layer

Neu `presentation/ui/screens/settings/AlwaysAwakeScreen.kt` und
`AlwaysAwakeViewModel.kt`. Route `Screen.AlwaysAwake` in `Screen.kt`, Eintrag in
`NavGraph.kt` — beides nach dem Muster von `Screen.FritzBoxSettings`.

In `SettingsScreen.kt` kommt ein neuer Abschnitt **„Server"** mit einem Eintrag
„Always-Awake", der **nur bei `isAdmin`** gerendert wird. `SettingsViewModel`
führt das Flag bereits (`SettingsViewModel.kt:44-45, 69-70`).

Auf dem Bildschirm:

- Hauptschalter (an/aus)
- vier Presets: 1 h, 4 h, 8 h, permanent
- freier Zeitpunkt über Material-`DatePicker` und `TimePicker`
- Statuszeile: „noch 3h 12m" bzw. „bis 21:30" bei kurzer Restlaufzeit, „bis
  02.08. 21:30" wenn der Zeitpunkt nicht heute liegt; bei permanent „dauerhaft
  aktiv"
- Hinweis, dass Always-Awake Vorrang vor der Schlafautomatik hat

Die Restlaufzeit wird clientseitig aus `until` berechnet und im Sekundentakt
aktualisiert, solange der Bildschirm sichtbar ist. `expires_in_seconds` aus der
Status-Route wird nicht benötigt.

## Fehlerverhalten

| Fall | Verhalten |
|---|---|
| Laden schlägt fehl | Fehlerzustand mit „Erneut versuchen", keine stillen Defaults |
| 403 | „Nur Admins dürfen das ändern" — sollte nicht auftreten, der Eintrag ist gated |
| 422 vom Validator | Servermeldung anzeigen; wäre ein Loch in der Clientprüfung |
| Zeitpunkt in der Vergangenheit | clientseitig abgefangen: „Der Zeitpunkt muss in der Zukunft liegen" |
| Zeitpunkt < 5 Minuten entfernt | clientseitig abgefangen: „Mindestens 5 Minuten in der Zukunft" |
| Zeitpunkt > 7 Tage entfernt | clientseitig abgefangen: „Höchstens 7 Tage im Voraus" |
| Kein Netz | „Server nicht erreichbar" |

Nach einem fehlgeschlagenen Schreibvorgang wird der zuletzt bekannte Zustand
wiederhergestellt, damit die Oberfläche nichts behauptet, was der Server nicht
übernommen hat.

## Tests

Der wichtigste Test prüft den **tatsächlich erzeugten JSON-String**: bei
„permanent" muss er `"always_awake_until":null` enthalten. Ein reiner
Mock-Test auf der Repository-Grenze würde die Null-Eigenheit durchwinken, und
der Fehler fiele erst am Server auf — als „die Ablaufzeit bleibt einfach
stehen".

Weiter:

- Ausschalten sendet **nur** `always_awake_enabled` und keine Ablaufzeit
- Befristet sendet ISO-8601 mit `Z`
- Antwort ohne Zeitzone (`2026-08-01T21:30:00`) wird als UTC gelesen
- Antwort mit `Z` wird identisch gelesen
- `HttpException` → `Result.Error`, sonstige Exception → „Server nicht erreichbar"
- ViewModel: Preset 1h/4h/8h berechnet `until` korrekt aus der aktuellen Zeit
- ViewModel: die drei Grenzfälle (Vergangenheit, < 5 Minuten, > 7 Tage) werden
  abgewiesen, ohne dass ein Request rausgeht
- ViewModel: nach fehlgeschlagenem Schreibvorgang steht der alte Zustand wieder

Die Zeitberechnungen brauchen eine injizierbare Uhr, sonst sind die Tests von
der Systemzeit abhängig.

## Bewusst nicht enthalten

- **Anzeige auf dem Dashboard.** Die Webapp hat dafür ein Status-Pill
  (`AlwaysAwakePill`). Sinnvoll, aber ein eigenes Thema — `GET /status` liefert
  den Zustand sogar ohne Admin-Rechte, das ließe sich später separat ergänzen.
- **Die übrigen Sleep-Einstellungen.** `SleepConfigResponse` trägt Dutzende
  Felder (Idle-Schwellen, Zeitpläne, Core-Uptime, Presence). Dieses Projekt
  fasst ausschließlich die zwei Always-Awake-Felder an.
- **Eine delegierbare Berechtigung.** Der Server kennt für diese Routen keine;
  das zu ändern wäre eine Server-Änderung und ein eigenes Projekt.
- **`expires_in_seconds` aus der Status-Route.** Die Restlaufzeit lässt sich aus
  `until` berechnen; ein zweiter Endpunkt dafür wäre unnötig.
