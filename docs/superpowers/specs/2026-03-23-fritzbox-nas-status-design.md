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

**Return type:** Reuses existing `WolResult` sealed class (Success = active, AuthError/Unreachable/Error map to their existing semantics). The method returns a Boolean-like result parsed from `NewActive`:

```kotlin
suspend fun checkHostActive(host, port, username, password, macAddress): WolResult
// WolResult.Success → host is active (NewActive=1)
// WolResult.Error("inactive") → host is inactive (NewActive=0)
// WolResult.AuthError → 401
// WolResult.Unreachable → IOException
```

This avoids introducing a redundant sealed class alongside `WolResult`.

### 2. PowerRepository — new `checkNasStatus()` method

Loads Fritz!Box config from `PreferencesManager`, calls `checkHostActive`, maps the result.

```kotlin
suspend fun checkNasStatus(): NasStatus
```

**NasStatus enum** (in `domain/model/NasStatus.kt`):
```kotlin
enum class NasStatus { ONLINE, SLEEPING, OFFLINE, UNKNOWN }
```

- Fritz!Box not configured → `UNKNOWN`
- `WolResult.Success` → `ONLINE`
- `WolResult.Error("inactive")` → `SLEEPING`
- `WolResult.AuthError` / `WolResult.Unreachable` / other `WolResult.Error` → `OFFLINE`

**Note:** `SLEEPING` is a best-effort inference — "the NAS is not visible on the Fritz!Box network, assumed to be in sleep/off state." The Fritz!Box cannot distinguish between sleeping, powered off, or unplugged. This is acceptable for the WoL use case since WoL works in all these cases (if hardware supports it).

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

**This fallback logic applies to both `loadDashboardData()` and `loadTelemetryData()`** (the 30-second polling loop). Extract the fallback into a shared private method like `updateNasStatus()` called from both paths.

**Remove** `_isFritzBoxConfigured` StateFlow and its collection in `loadUserRole()`. Remove corresponding `collectAsState()` in `DashboardScreen.kt`. The Fritz!Box config check is now internal to the fallback logic.

### 4. Post-WoL Behavior

After a successful WoL send:
1. Set `nasStatus = NasStatus.UNKNOWN` immediately — this shows "Server Offline" (neutral) instead of the WoL button, preventing repeated taps
2. The existing 30-second telemetry poll will naturally detect the NAS coming online
3. The snackbar "WoL-Signal gesendet" already provides user feedback

No special "STARTING" state needed — the NAS boot takes 30-90 seconds, and the telemetry poll will pick it up. Showing "Server Offline" during boot is accurate (it IS offline until it finishes booting).

### 5. ServerStatusStrip — Three States

| NasStatus | Dot Color | Text | Power Options (admin) |
|-----------|-----------|------|-----------------------|
| ONLINE | Green | "Server Online" + Uptime | Sleep, Suspend |
| SLEEPING | Orange | "NAS schläft" | WoL |
| OFFLINE | Red | "Server Offline" | — |
| UNKNOWN | Red | "Server Offline" | — (same as OFFLINE) |

The `isOnline` parameter is replaced by `nasStatus: NasStatus`. The `isFritzBoxConfigured` parameter is removed (WoL availability is implicit in SLEEPING state).

---

## Data Flow

```
Dashboard opens / 30s poll
  → getSystemTelemetry() (existing periodic call)
    → Success: nasStatus = ONLINE, show telemetry
    → Failure:
        → isFritzBoxConfigured?
            → Yes: checkNasStatus() via Fritz!Box TR-064
                → SLEEPING: orange dot, "NAS schläft", WoL button
                → ONLINE: green dot, "Server Online" (API issue)
                → OFFLINE: red dot, "Server Offline"
            → No: red dot, "Server Offline"

User taps WoL
  → sendWol() → success snackbar
  → nasStatus = UNKNOWN (hides WoL button)
  → Next 30s poll picks up NAS coming online
```

## Error Handling

| Error | Behavior |
|-------|----------|
| Fritz!Box unreachable | Fall back to OFFLINE status |
| Fritz!Box auth failure (401) | Fall back to OFFLINE status, log warning |
| SOAP fault | Fall back to OFFLINE status |
| MAC not found on Fritz!Box | Fall back to OFFLINE status |

No user-facing errors for status checks — they silently degrade to OFFLINE.

## Bugfix: SharedFlow buffer

**Problem:** `MutableSharedFlow<String>()` with default `extraBufferCapacity = 0` drops events when the collector is busy (e.g., showing a previous snackbar).

**Fix:** Change to `MutableSharedFlow<String>(extraBufferCapacity = 1)` in both:
- `FritzBoxSettingsViewModel.kt`
- `DashboardViewModel.kt`

## Changes to Existing Code

| File | Change |
|------|--------|
| `FritzBoxTR064Client.kt` | Add `checkHostActive()` method (reuses `WolResult`) |
| `PowerRepository.kt` | Add `checkNasStatus(): NasStatus` to interface |
| `PowerRepositoryImpl.kt` | Implement `checkNasStatus()` |
| `DashboardViewModel.kt` | Add `nasStatus` StateFlow, fallback logic in both `loadDashboardData()` and `loadTelemetryData()`, remove `_isFritzBoxConfigured`, fix SharedFlow buffer |
| `DashboardScreen.kt` | Replace `isOnline`/`isFritzBoxConfigured` with `nasStatus` in ServerStatusStrip, remove `isFritzBoxConfigured` collection |
| `FritzBoxSettingsViewModel.kt` | Fix SharedFlow buffer |

## New Files

| File | Purpose |
|------|---------|
| `domain/model/NasStatus.kt` | `NasStatus` enum |
