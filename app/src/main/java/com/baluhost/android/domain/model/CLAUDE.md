# Domain Models

The 26 types the rest of the app builds on: plain Kotlin `data class`es and `enum class`es representing files, power state, sync configuration, VPN config, notifications, and system telemetry. Repositories map server DTOs into these on the way in (see `data/repository/CLAUDE.md`'s "Core pattern"). The intent is that ViewModels and use cases only ever see these, never the DTOs underneath; in practice 11 use cases import `data.remote.dto` directly (`GetNotificationPreferencesUseCase` returns a raw `NotificationPreferencesDto` straight to its ViewModel), and `AuthRepository`/`NotificationRepository` themselves declare DTO types in their domain-layer signatures — see `domain/usecase/CLAUDE.md` for the breakdown. The point is to keep the domain layer testable without a device or a real server — a plain JUnit test can construct any of these with `MyModel(...)`.

## Core concepts

- **Model vs. DTO.** DTOs (`data/remote/dto/`) mirror the server's JSON exactly, including its naming and its optionality quirks; domain models normalize that into whatever shape the app actually needs. The conversion function is usually a `toDomain()` extension on the DTO, defined in the repository that owns the mapping — `Notification.kt` is the one file in this package that defines its own `NotificationDto.toDomain()` instead (see Conventions).
- **Enums parse server strings defensively.** Several enums (`DesktopState`, `RaidStatus`, `RaidDeviceState`, `SyncType`, `SyncStatus`, `ConflictResolution`, `ScheduleType`, `SyncHistoryStatus`, `UploadStatus`) have a `fromApi`/`fromString` companion function that maps an unrecognized value to a safe fallback (usually the "unknown" or most conservative case) instead of throwing. A server adding a new status string should degrade the UI, not crash it.
- **`sealed class` for outcomes richer than success/failure.** `NasStatusResult` is the clearest example: a NAS status check can resolve to a status, or fail in three distinguishable ways (`FritzBoxUnreachable`, `FritzBoxAuthError`, `FritzBoxNotConfigured`), and a `when` over the sealed class forces every caller to handle all of them.
- **`domain/model/sync/` is its own sub-package**, holding the 5 files specific to folder synchronization (schedules, history, conflict/upload state, the WebDAV-style backend models). Everything else lives flat in `domain/model/`.

## Conventions

- **Nullability carries meaning, and it is not always the same meaning.** A `null` in these types is a deliberate encoding, not "value not fetched yet" by default — get it wrong and the UI shows the wrong state:
  - **`AlwaysAwake.until == null` means permanent**, not "not set" — the same encoding the server uses, so the mapping in the repository is a straight passthrough.
  - **`DesktopActionResult.sessionUnlocked` is three-valued**: `true`, `false` (the server explicitly refused to unlock the session), and `null` (the server said nothing about the lock screen at all). Collapsing `null` into `false` would tell the user "unlock was denied" when actually nothing was asked.
  - **`DesktopState.UNKNOWN` means "we don't know"**, not "off." It's also what a failed status lookup maps to (see `fromApi`), and the UI's response to it is to show no desktop entry at all — offering either `RUNNING` or `STOPPED` behavior would be a guess, and `UNKNOWN` exists specifically so the UI doesn't have to guess.
  - **In `SyncFolderUpdateConfig`, every nullable field means "leave unchanged," not "clear this value."** This is a PATCH-style partial update: `remotePath: String? = null` doesn't mean "clear the remote path," it means "don't touch it." A caller that wants to actually clear a field has no way to express that with this type.
  - **In `SyncSchedule`, `timeOfDay`/`dayOfWeek`/`dayOfMonth` are conditionally meaningful, not independently optional.** Which one is populated depends on `scheduleType` (`DAILY` uses `timeOfDay`, `WEEKLY` uses `dayOfWeek`, `MONTHLY` uses `dayOfMonth`); a `null` in the "wrong" field for the current type isn't missing data, it's simply not applicable.
  - **`AppNotification.deletedAt == null` means active**, not "unknown" or "not yet loaded" — it's the server timestamp of the move to trash, so its absence is the normal state for anything currently in the inbox. A non-null value is what puts a row in the trash tab; there is no separate boolean for this (the older `isDismissed` field this replaced conflated "dismissed" with "trashed" — `deletedAt` is the one source of truth now).
- **`PowerPermissions.hasAnyPermission` deliberately excludes `canUnlockSession`.** It's not an independent permission a user can act on — it's an add-on the server applies while `canToggleDesktop` turns the displays back on. Including it in the "is the power button worth showing" check would let a user with only `canUnlockSession` see a power button that does nothing.
- **Almost nothing here imports a framework type — with one documented exception.** `domain/model/sync/SyncModels.kt` imports `android.net.Uri` and stores it directly on `SyncFolderConfig.localUri` and `SyncFolderCreateConfig.localUri` (both SAF content URIs). Every other model in this package is free of `android.*`, Retrofit, and Room, so a new model should default to that — but if a field genuinely needs to survive as a SAF URI (permission grants don't survive a round-trip through `String`), this file is the precedent for keeping the platform type rather than a lossy stand-in.
- **`Notification.kt` is the one file that reaches into the data layer.** It imports `com.baluhost.android.data.remote.dto.NotificationDto` and defines `NotificationDto.toDomain()` right next to the model, which is the reverse of the usual direction (repositories depend on models, not the other way round). New conversions should follow the repository-side pattern described in `data/repository/CLAUDE.md` rather than repeat this one.

## Files

### Files
| File | Contents |
|---|---|
| `FileItem.kt` | `FileItem` — a listed file/folder, with `displaySize`/`extension` helpers |
| `RecentFile.kt` | `RecentFile`, `FileAction` — activity-feed entry and its action enum |
| `FileMetadataCRDT.kt` | `FileMetadataCRDT`, `CRDTOperation`, `VectorClock` — LWW conflict resolution for offline edits |
| `PendingOperation.kt` | `PendingOperation`, `OperationType`, `OperationStatus` — offline operation queue entry |
| `ShareInfo.kt` | `ShareStatistics`, `FileShareInfo`, `SharedWithMeInfo` — file sharing |

### Power
| File | Contents |
|---|---|
| `AlwaysAwake.kt` | `AlwaysAwake` — always-awake override (`until == null` ⇒ permanent) |
| `DesktopActionResult.kt` | `DesktopActionResult` — outcome of turning displays back on (`sessionUnlocked` tri-state) |
| `DesktopState.kt` | `DesktopState` enum — `RUNNING`/`STOPPED`/`UNKNOWN` |
| `PowerPermissions.kt` | `PowerPermissions` — per-user power action flags, `hasAnyPermission` |
| `NasStatus.kt` | `NasStatus` enum — `ONLINE`/`SLEEPING`/`OFFLINE`/`UNKNOWN` |
| `NasStatusResult.kt` | `NasStatusResult` sealed class — resolved status or a specific Fritz!Box failure |
| `WolAvailability.kt` | `WolAvailability` enum — Wake-on-LAN reachability state |
| `EnergyDashboard.kt` | `EnergyDashboard` — current/today/month power draw for the dashboard panel |

### Sync (`sync/`)
| File | Contents |
|---|---|
| `sync/SyncModels.kt` | `SyncFolderConfig`, `SyncType`, `SyncStatus`, `ConflictResolution`, create/update configs, `UploadQueueItem`, `FileConflict`, `SyncResult`, `RemoteFileInfo`, sync preflight — the bulk of the sync domain model |
| `sync/SyncBackendModels.kt` | `FolderStat`, `FileEntry`, `OperationResult`, `MigrationPlan`/`MigrationHandle`/`MigrationCheckpoint`/`MigrationProgress`, `SyncMetrics`, `FolderSize` — sync-engine-internal models |
| `sync/SyncHistory.kt` | `SyncHistory`, `SyncHistoryStatus`, `SyncHistorySummary` — completed-sync log |
| `sync/SyncSchedule.kt` | `SyncSchedule`, `ScheduleType` — scheduled sync configuration |
| `sync/SyncTrigger.kt` | `SyncTrigger` enum — `AUTO`/`MANUAL`, used as a request header value |

### VPN
| File | Contents |
|---|---|
| `VpnConfig.kt` | `VpnConfig`, `VpnClient` — WireGuard config and client registration |

### Notifications
| File | Contents |
|---|---|
| `Notification.kt` | `AppNotification`, `NotificationType`, `NotificationCategory`, and `NotificationDto.toDomain()` (see Conventions) |

### System
| File | Contents |
|---|---|
| `SystemInfo.kt` | `SystemInfo`, `CpuStats`, `MemoryStats`, `DiskStats`, `RaidArray`/`RaidDevice`/`RaidStatus`/`RaidDeviceState`, `StorageDisk` |
| `SmartDevice.kt` | `SmartStatusInfo`, `SmartDeviceInfo`, `SmartSelfTest`, `SmartAttribute` — S.M.A.R.T. disk health |
| `MonitoringHistory.kt` | `CpuSample`/`CpuHistory`, `MemorySample`/`MemoryHistory`, `PowerHourlySample`/`EnergyDashboardFull`, `UptimeSample`/`SleepEvent`/`CurrentUptime`/`UptimeHistory` |
| `MobileDevice.kt` | `MobileDevice` — a registered mobile device |
| `AuthResult.kt` | `AuthResult` — tokens plus `User`/`MobileDevice` returned by login/registration |
| `User.kt` | `User` — the logged-in account |

## Adding a new model

1. Pick the location: `domain/model/` for anything cross-cutting, `domain/model/sync/` only if it's specific to folder synchronization.
2. Define it as a `data class` or `enum class` using only Kotlin stdlib and `java.time` types — no `android.*`, Retrofit, or Room annotations, unless the field genuinely can't survive without a platform type (see the `android.net.Uri` exception above, and justify it the same way).
3. If any field's `null` means something other than "not available" (permanent, "leave unchanged," conditionally-applicable, tri-state, etc.), say so in a KDoc comment on the property or class — follow the pattern in `AlwaysAwake.kt` or `DesktopActionResult.kt`. This is the detail a caller cannot infer from the type signature.
4. If the model is parsed from a server string with a fixed set of known values, add a `fromApi`/`fromString` companion function that falls back to a safe default for unrecognized values rather than throwing.
5. Write the `<Dto>.toDomain()` mapping in the repository that owns it (`data/repository/`), not in this package — `Notification.kt` is a legacy exception, not the pattern to copy.
