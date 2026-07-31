# Repository Implementations

The 13 classes here implement the repository interfaces declared in `domain/repository/` — the interfaces are what ViewModels and use cases depend on, these are the concrete implementations that call `data/remote/api/` and `data/local/`. Each is bound to its interface with `@Binds` in `di/RepositoryModule.kt`. This is the layer that decides what a network or database failure means to the rest of the app.

## Core pattern

A repository method calls the relevant `*Api`, maps the DTO to a domain model, and wraps the outcome in `com.baluhost.android.util.Result<T>` (`Success` / `Error` / `Loading`). Nothing in this layer ever constructs `Result.Loading` — it exists purely so ViewModels can represent "request in flight" locally — but the compiler still treats `Result` as a three-case sealed class, so an exhaustive `when` over it needs an `is Result.Loading -> {}` (or `else -> {}`) branch even though a repository will never actually produce one.

This is the dominant pattern, not a universal one — know the exceptions before assuming a method follows it:
- `FileRepository.getFiles()` and `OfflineQueueRepositoryImpl.getPendingOperations()/getPendingCount()` return `Flow<T>` directly, not `Result<T>` — these are continuous cache reads, not one-shot calls, so there's no single outcome to wrap.
- `DeviceRepositoryImpl.deleteDevice()` throws instead of returning `Result` — see `domain/repository/DeviceRepository.kt`'s `@throws Exception` contract. Callers need a `try/catch`, not a `when`.
- `PowerRepositoryImpl.checkNasStatus()` returns the custom sealed type `NasStatusResult`, not `Result<T>`, because a Fritz!Box check has outcomes (`FritzBoxNotConfigured`, `FritzBoxAuthError`, `FritzBoxUnreachable`, `Resolved(NasStatus)`) that don't fit the generic success/error shape.
- `VpnRepositoryImpl.getCachedVpnConfig()` returns `VpnConfig?` directly — a local cache read, same reasoning as the `Flow` cases above.

## Conventions

- **Error mapping distinguishes "server answered no" from "server unreachable".** The normal pattern is `catch (e: HttpException) { /* specific German message */ }` followed by `catch (e: Exception) { Result.Error(Exception("Server nicht erreichbar")) }`. Collapsing these into one branch removes information the UI needs — it can't tell the user whether waiting and retrying will help versus whether the request was rejected on principle (e.g. missing permission).
- **`HttpException.message()` is the HTTP reason phrase, not the response body — and is empty on HTTP/2** (many servers, including this one over a reverse proxy, negotiate HTTP/2). Code that wants the server's actual error text must read `e.response()?.errorBody()?.string()` instead. `DeviceRepositoryImpl` and `SleepConfigRepositoryImpl` do this; anywhere `e.message()` is used directly in a user-facing string, that string is really just "HTTP 4xx", not what the server said.
- **`PowerRepositoryImpl.sendSuspend()` treats an `IOException` as success**, not failure. The server carries out the suspend and disappears mid-request, so the POST dies on the wire — that dead connection *is* the expected outcome of a successful suspend, not a network problem to report. The call also waits at most 5 seconds (`SUSPEND_ACK_TIMEOUT_MS`) via `withTimeoutOrNull`, rather than sitting on OkHttp's 120-second read timeout, so the caller isn't left hanging for two minutes to learn something it could know in five seconds.
- **Twelve files are named `*RepositoryImpl.kt`; `FileRepository.kt` is not.** `FileRepository` also isn't bound behind a `domain/repository` interface at all — it's a concrete `@Singleton` class injected directly, providing a cache-first `Flow<List<FileItem>>` (Room cache, refreshed from `FilesApi` when stale) alongside `Result`-returning `refreshFiles()`/`deleteFile()`. It coexists with `FilesRepositoryImpl`/`FilesRepository`, which is the "normal" `@Binds`-bound implementation — the two are not interchangeable, and the naming similarity is easy to misread as a typo. New repositories follow the `Impl`-behind-an-interface pattern, not this one.

## Files

| File | Responsibility |
|---|---|
| `ActivityRepositoryImpl.kt` | Recent files, activity feed; buffers client-side activity for offline sync |
| `AuthRepositoryImpl.kt` | Login, token refresh |
| `DeviceRepositoryImpl.kt` | Delete the current mobile device registration (throws, see above) |
| `FileRepository.kt` | Cache-first file listing (Room + `FilesApi`), not interface-bound (see above) |
| `FilesRepositoryImpl.kt` | `FilesRepository` implementation: file/folder CRUD, permissions |
| `MonitoringRepositoryImpl.kt` | CPU/memory history, energy dashboard, uptime current + history |
| `NotificationRepositoryImpl.kt` | Notification list, unread count, read/dismiss/snooze, preferences |
| `OfflineQueueRepositoryImpl.kt` | Room-backed queue of operations pending sync while offline |
| `PowerRepositoryImpl.kt` | Wake-on-LAN, soft sleep/suspend/wake, desktop enable/disable, NAS status check |
| `SleepConfigRepositoryImpl.kt` | Always-awake override (get/set) |
| `SyncRepositoryImpl.kt` | Sync folder CRUD, trigger, status; DTO-to-domain mapping for sync |
| `SystemRepositoryImpl.kt` | System info, RAID status/disks; DTO-to-domain mapping for system data |
| `VpnRepositoryImpl.kt` | VPN config fetch/generate/save/cache, client management |

## Adding a new repository

1. Declare the interface in `domain/repository/`, returning `Result<T>` for one-shot calls (or `Flow<T>` only for continuous cache reads).
2. Implement it here as `<Feature>RepositoryImpl.kt`, injecting the relevant `*Api` from `data/remote/api/` and any local sources it needs.
3. Map `HttpException` to a specific message and every other `Exception` to `"Server nicht erreichbar"` (or the appropriate German equivalent) — don't collapse the two.
4. Bind it in `di/RepositoryModule.kt` with `@Binds @Singleton`.
