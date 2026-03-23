# Direct Fritz!Box WoL from App — Design Spec

## Goal

Replace the server-proxied WoL (`App → BaluHost → Fritz!Box`) with a direct connection (`App → Fritz!Box`) via TR-064 SOAP, so WoL works even when the NAS is offline/sleeping.

## Architecture

The app communicates directly with the Fritz!Box using TR-064 SOAP over HTTP with Digest Authentication. Fritz!Box credentials are configured in a new admin-only settings screen and stored securely on-device.

**Tech:** OkHttp (already in project) for HTTP + Digest Auth, `EncryptedSharedPreferences` for password storage, raw XML for SOAP envelope.

---

## Components

### 1. FritzBoxTR064Client

New service class that handles the TR-064 SOAP communication.

**Responsibilities:**
- Build SOAP envelope for `X_AVM-DE_WakeOnLANByMACAddress`
- Send HTTP POST to `http://{host}:{port}/upnp/control/hosts` with Digest Auth
- Parse SOAP faults from XML response
- Test connection via `GET http://{host}:{port}/hostsSCPD.xml`

**TR-064 Details:**
- Service Type: `urn:dslforum-org:service:Hosts:1`
- Action: `X_AVM-DE_WakeOnLANByMACAddress`
- Control URL: `/upnp/control/hosts`
- SCPD URL: `/hostsSCPD.xml` (for connection test)
- Auth: HTTP Digest Auth (RFC 2617)
- Port: 49000 (default)

**SOAP Envelope:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:X_AVM-DE_WakeOnLANByMACAddress xmlns:u="urn:dslforum-org:service:Hosts:1">
      <NewMACAddress>AA:BB:CC:DD:EE:FF</NewMACAddress>
    </u:X_AVM-DE_WakeOnLANByMACAddress>
  </s:Body>
</s:Envelope>
```

**Headers:**
```
Content-Type: text/xml; charset="utf-8"
SOAPAction: "urn:dslforum-org:service:Hosts:1#X_AVM-DE_WakeOnLANByMACAddress"
```

### 2. FritzBoxConfigStore

Handles persisting and retrieving Fritz!Box credentials.

**Storage:**
- Host, port, username, MAC address → `PreferencesManager` (DataStore)
- Password → `EncryptedSharedPreferences` (AES256, Android Keystore backed)

**Data model:**
```kotlin
data class FritzBoxLocalConfig(
    val host: String = "192.168.178.1",
    val port: Int = 49000,
    val username: String = "",
    val macAddress: String = "",
    val hasPassword: Boolean = false
)
```

### 3. Fritz!Box Settings Screen

New Compose screen accessible from the existing Settings screen. Admin-only.

**Fields:**
- Host (TextField, default "192.168.178.1")
- Port (TextField, numeric, default "49000")
- Username (TextField)
- Password (TextField, password visibility toggle)
- NAS MAC-Adresse (TextField, format XX:XX:XX:XX:XX:XX)
- "Verbindung testen" Button
- "Speichern" Button

**Validation:**
- MAC format: `^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$`
- Port: 1-65535
- Host: non-empty

### 4. Modified PowerRepository

`sendWol()` changes from calling `FritzBoxApi` (Retrofit/backend) to using `FritzBoxTR064Client` directly.

---

## Data Flow

```
User taps "NAS aufwecken"
  -> Confirmation Dialog
  -> DashboardViewModel.sendWol()
  -> PowerRepository.sendWol()
  -> Load FritzBox config from FritzBoxConfigStore
  -> FritzBoxTR064Client.sendWol(host, port, username, password, mac)
  -> HTTP POST with Digest Auth to Fritz!Box
  -> Parse response (200 + no SOAP fault = success)
  -> Snackbar feedback
```

## Error Handling

| Error | User Message |
|-------|-------------|
| Fritz!Box not configured | WoL button disabled, hint "Fritz!Box in Einstellungen konfigurieren" |
| Connection timeout / unreachable | "Fritz!Box nicht erreichbar. VPN aktiv?" |
| HTTP 401 (auth failure) | "Fritz!Box Zugangsdaten ungültig" |
| SOAP fault in response | Show fault message |
| Invalid MAC format | Validation error in settings |

## Changes to Existing Code

| File | Change |
|------|--------|
| `PowerRepositoryImpl` | `sendWol()` uses `FritzBoxTR064Client` instead of `FritzBoxApi` |
| `FritzBoxApi.kt` | Remove (or keep for future Fritz!Box features — only `sendWol()` was used) |
| `NetworkModule.kt` | Remove `provideFritzBoxApi` if `FritzBoxApi` is removed |
| `ServerStatusStrip` | WoL button checks Fritz!Box config, disables if not configured |
| `DashboardViewModel` | Load Fritz!Box config status for UI gating |
| `SettingsScreen` | Add "Fritz!Box" menu entry (admin-only) |
| `NavGraph.kt` / `Screen.kt` | Add FritzBoxSettings route |
| `PreferencesManager` | Add Fritz!Box config fields |
| `build.gradle.kts` | Add `androidx.security:security-crypto` dependency |

## New Files

| File | Purpose |
|------|---------|
| `data/network/FritzBoxTR064Client.kt` | TR-064 SOAP client |
| `data/local/FritzBoxConfigStore.kt` | Secure credential storage |
| `presentation/ui/screens/settings/FritzBoxSettingsScreen.kt` | Settings UI |
| `presentation/ui/screens/settings/FritzBoxSettingsViewModel.kt` | Settings ViewModel |

## Security

- Password stored in `EncryptedSharedPreferences` (AES256-SIV for keys, AES256-GCM for values, backed by Android Keystore)
- Password never logged or exposed in UI state
- Fritz!Box settings only visible/accessible to admin users
- TR-064 uses Digest Auth (password not sent in cleartext)
