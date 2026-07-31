# Remote Data Source

Everything that talks HTTP to the BaluHost server: Retrofit interfaces (`api/`), the JSON payloads they move (`dto/`, `dto/sync/`), and the OkHttp interceptors that sit in front of every call (`interceptors/`). This layer never touches the domain models directly. The intent is that repositories in `data/repository/` are the only callers, responsible for mapping DTOs into domain types; in practice 21 of the 55 use cases in `domain/usecase/` import `data.remote.api` directly instead, and 11 import `data.remote.dto` directly — see `domain/usecase/CLAUDE.md` for the breakdown.

## Structure

- `api/` — 14 files, 13 Retrofit interfaces, one per server feature area (`MobileApiFactory.kt` is the one file that isn't — see the table below). Interface names mirror the server's route groups (`ActivityApi` ↔ `/activity/*`, `VpnApi` ↔ `/vpn/*`), so the fastest way to find where an endpoint is called is to search the interface whose name matches the route prefix.
- `dto/` — 19 files of request/response payloads, plus the `dto/sync/` subfolder (`ChunkedUploadDto.kt`, `SyncDto.kt`, `SyncPreflightDto.kt`, `SyncScheduleDto.kt`) for the sync/upload-queue payloads specifically. DTOs are grouped by feature, not 1:1 with API files — e.g. the types `SleepApi` returns (`PowerActionResponse`, `MyPowerPermissionsDto`, `DesktopStatusDto`, `DesktopActionResponseDto`) live in `PowerDto.kt`; there is no separate sleep-specific DTO file, so don't assume an API interface's name tells you which DTO file to open.
- `interceptors/` — 5 OkHttp interceptors wired into the client in `di/NetworkModule.kt`.

## Conventions

- **DTO fields carry `@SerializedName("snake_case")`** because the server's JSON is snake_case and Kotlin properties are camelCase — without the mapping, Gson silently binds nothing and every field reads as its default.
- **Many DTO fields declare a default value** (`SleepConfigDto`, `PowerSummaryDto`, `MyPowerPermissionsDto`, …), not all of them (`LoginResponse`, `ActivityEntryDto`, … have none). Defaults exist where a DTO deliberately only declares the subset of a much larger server response the app actually reads (see the doc comment on `SleepConfigDto`) — Gson drops fields that aren't declared and, for the ones that are, falls back to the Kotlin default when the JSON omits them. That fallback is silent: a broken contract shows up as "the field is `false`", not as a crash or a logged error. Declare only what the app reads, and give it a default only when a missing key is an expected, harmless case — not as a blanket habit.
- **`DynamicBaseUrlInterceptor` rewrites the request host at runtime** from the server URL stored in `PreferencesManager` (`data/local/datastore/PreferencesManager.kt`), because the user pairs with whatever NAS they configured, not a fixed address. `BuildConfig.BASE_URL` (wired in `di/NetworkModule.kt`) is only the fallback used when no server URL is stored yet — the value baked in at build time is routinely stale and that's fine, since it's essentially never used past first setup.
- **`ErrorInterceptor` logs every non-2xx response** with the URL, HTTP method, and the parsed error body, then rethrows the underlying `IOException` unchanged. Repositories therefore don't need their own HTTP-error logging — by the time a repository's `catch` block runs, the interceptor has already recorded what happened.
- **`MobileApiFactory` exists because device pairing targets a server that's only known from a scanned QR code**, not from `BuildConfig.BASE_URL`. The injected `MobileApi` is wired to the build-time base URL and is the wrong client for pairing; `MobileApiFactory.create(baseUrl, tokenProvider)` builds a throwaway Retrofit/OkHttp client pointed at the QR code's server so the registration flow can be unit-tested without a real network call.
- **`SleepApi.updateSleepConfig(@Body body: RequestBody)` takes a pre-serialized body, not a DTO.** Setting `SleepConfigDto.alwaysAwakeUntil` to permanent requires sending the JSON key present with an explicit `null`; Gson's normal reflective serialization drops null fields entirely, so a DTO-typed `@Body` here can't express "permanent". Don't replace this with a DTO — it would silently break the permanent-override case.

## `api/` files

| File | Server prefix | Purpose |
|---|---|---|
| `ActivityApi.kt` | `/activity` | Recent files, activity feed, batched activity reporting |
| `AuthApi.kt` | `/auth` | Login, token refresh |
| `EnergyApi.kt` | `/smart-devices`, `/energy` | Smart-plug list, per-device energy stats and dashboard |
| `FilesApi.kt` | `/files` | Listing, upload/download, folder ops, permissions |
| `MobileApi.kt` | `/mobile` | Device registration, device list, push-token registration |
| `MobileApiFactory.kt` | — | Builds a `MobileApi` for a server known only at pairing time (see above); not a Retrofit interface itself |
| `MonitoringApi.kt` | `/monitoring` | CPU/memory history, uptime current + history |
| `NotificationsApi.kt` | `/notifications` | List, unread count, read/dismiss/snooze, preferences, WebSocket token |
| `PluginApi.kt` | `/plugins` | Plugin config, UI manifest, menu-action invocation |
| `SharesApi.kt` | `/shares` | Share statistics, user shares, shared-with-me |
| `SleepApi.kt` | `/system/sleep` | Soft sleep/suspend/wake, desktop enable/disable, always-awake config |
| `SyncApi.kt` | `/mobile/sync`, `/files/upload`, `/sync` | Folder sync CRUD, chunked upload, upload queue, sync schedules and preflight |
| `SystemApi.kt` | `/system` | System info, RAID status/disks, telemetry history, storage, SMART status |
| `VpnApi.kt` | `/vpn` | VPN config generation, client CRUD, server config/status |

## Adding a new API module

1. Add the Retrofit interface in `api/`, named after the server route group it covers.
2. Add its request/response DTOs in `dto/` (or `dto/sync/` if it's a sync/upload payload), with `@SerializedName` on every field and a default only where a missing key is expected and harmless.
3. Provide the interface in `di/NetworkModule.kt` so Hilt can inject it into a repository.
