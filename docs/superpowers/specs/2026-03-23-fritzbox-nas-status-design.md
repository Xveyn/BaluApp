# Fritz!Box NAS Status Check — Design Spec

## Goal

Use Fritz!Box TR-064 to detect whether the NAS is online or sleeping, so the Dashboard can show a dynamic WoL button when the NAS sleeps — without depending on the BaluHost backend.

## Architecture

When the BaluHost API (telemetry) fails, the app falls back to querying the Fritz!Box via TR-064 `GetSpecificHostEntry`. This returns whether the NAS's MAC address is active on the network. The result drives a three-state `ServerStatusStrip`.

**Tech:** Existing `FritzBoxTR064Client` (OkHttp + Digest Auth), existing Fritz!Box config from `PreferencesManager`.

---

## Components

### 1. FritzBoxTR064Client — new `checkHostActive` method

Sends a SOAP request using the `Hosts:1` service, action `GetSpecificHostEntry`, with the configured MAC address. Parses `NewActive` from the response.

**TR-064 Details:**
- Service Type: `urn:dslforum-org:service:Hosts:1`
- Action: `GetSpecificHostEntry`
- Control URL: `/upnp/control/hosts` (same as WoL)
- Parameter: `NewMACAddress`
- Response field: `NewActive` (1 = online, 0 = offline)

**SOAP Envelope:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetSpecificHostEntry xmlns:u="urn:dslforum-org:service:Hosts:1">
      <NewMACAddress>AA:BB:CC:DD:EE:FF</NewMACAddress>
    </u:GetSpecificHostEntry>
  </s:Body>
</s:Envelope>
```

**Headers:**
```
Content-Type: text/xml; charset="utf-8"
SOAPAction: "urn:dslforum-org:service:Hosts:1#GetSpecificHostEntry"
```

**Return type:**
```kotlin
sealed class HostStatus {
    object Active : HostStatus()
    object Inactive : HostStatus()
    object Unreachable : HostStatus()
    data class Error(val message: String) : HostStatus()
}
```

### 2. PowerRepository — new `checkNasStatus()` method

Loads Fritz!Box config from `PreferencesManager`, calls `checkHostActive`, maps the result.

```kotlin
suspend fun checkNasStatus(): NasStatus
```

**NasStatus enum:**
```kotlin
enum class NasStatus { ONLINE, SLEEPING, OFFLINE, UNKNOWN }
```

- Fritz!Box not configured → `UNKNOWN`
- `HostStatus.Active` → `ONLINE`
- `HostStatus.Inactive` → `SLEEPING`
- `HostStatus.Unreachable` / `HostStatus.Error` → `OFFLINE`

### 3. DashboardViewModel — Fallback Logic

**Current flow:** Telemetry request → success = online, failure = offline.

**New flow:**
```
Telemetry request
  → Success: nasStatus = ONLINE (show telemetry as before)
  → Failure + Fritz!Box configured:
      → checkNasStatus()
          → SLEEPING: nasStatus = SLEEPING
          → ONLINE: nasStatus = ONLINE (API down but NAS is up — unusual)
          → OFFLINE: nasStatus = OFFLINE
  → Failure + Fritz!Box not configured:
      → nasStatus = OFFLINE (as before)
```

New state: `_nasStatus: MutableStateFlow<NasStatus>` replaces the implicit `isOnline` derived from `uiState.telemetry != null`.

### 4. ServerStatusStrip — Three States

| NasStatus | Dot Color | Text | Power Options (admin) |
|-----------|-----------|------|-----------------------|
| ONLINE | Green | "Server Online" + Uptime | Sleep, Suspend |
| SLEEPING | Orange | "NAS schläft" | WoL |
| OFFLINE | Red | "Server Offline" | — |
| UNKNOWN | Red | "Server Offline" | — (same as OFFLINE) |

The `isOnline` parameter is replaced by `nasStatus: NasStatus`. The `isFritzBoxConfigured` parameter is no longer needed (WoL availability is implicit in SLEEPING state).

---

## Data Flow

```
Dashboard opens
  → getSystemTelemetry() (existing periodic call)
    → Success: nasStatus = ONLINE, show telemetry
    → Failure:
        → isFritzBoxConfigured?
            → Yes: checkNasStatus() via Fritz!Box TR-064
                → SLEEPING: orange dot, "NAS schläft", WoL button
                → ONLINE: green dot, "Server Online" (API issue)
                → OFFLINE: red dot, "Server Offline"
            → No: red dot, "Server Offline"
```

## Error Handling

| Error | Behavior |
|-------|----------|
| Fritz!Box unreachable | Fall back to OFFLINE status |
| Fritz!Box auth failure | Fall back to OFFLINE status, log warning |
| SOAP fault | Fall back to OFFLINE status |
| MAC not found on Fritz!Box | Fall back to OFFLINE status |

No user-facing errors for status checks — they silently degrade to OFFLINE.

## Bugfix: Snackbar in FritzBoxSettingsViewModel

**Problem:** `MutableSharedFlow<String>()` with default `extraBufferCapacity = 0` drops events when the collector is busy (e.g., showing a previous snackbar).

**Fix:** Change to `MutableSharedFlow<String>(extraBufferCapacity = 1)` so events are buffered and not lost.

## Changes to Existing Code

| File | Change |
|------|--------|
| `FritzBoxTR064Client.kt` | Add `checkHostActive()` method and `HostStatus` sealed class |
| `PowerRepository.kt` | Add `checkNasStatus(): NasStatus` to interface |
| `PowerRepositoryImpl.kt` | Implement `checkNasStatus()` |
| `DashboardViewModel.kt` | Add `nasStatus` StateFlow, fallback logic on telemetry failure |
| `DashboardScreen.kt` | Replace `isOnline`/`isFritzBoxConfigured` with `nasStatus` in ServerStatusStrip |
| `FritzBoxSettingsViewModel.kt` | Fix SharedFlow buffer |

## New Types

| Type | Location |
|------|----------|
| `HostStatus` sealed class | `FritzBoxTR064Client.kt` |
| `NasStatus` enum | `PowerRepository.kt` (or own file) |
