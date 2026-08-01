# Local Data Source

Everything the app persists or caches on-device: the Room database, DataStore preferences, encrypted secrets, and two in-memory caches. Nothing here talks to the network — `data/remote/` and `data/repository/` own that; this layer only stores and retrieves.

## Structure

Four areas, 21 files total:

- `database/` — Room. `BaluHostDatabase` (version 5), 5 DAOs (`dao/`), 5 entities (`entities/`), one `Converters` (`converters/`), and one mapper (`mappers/PendingOperationMapper.kt`).
- `datastore/PreferencesManager.kt` — non-sensitive app preferences via Jetpack DataStore.
- `security/` — 5 files holding everything sensitive.
- `cache/CachedFileDao.kt` and `PluginTranslationCache.kt` — two standalone caches that don't fit the other two buckets.

## Conventions

- **Booleans in DataStore are stored as `"true"`/`"false"` strings via `stringPreferencesKey`, not `booleanPreferencesKey`.** This is a deliberate, code-documented convention (see the comment above `autoVpnOnExternalKey` in `PreferencesManager.kt`) — every boolean flag in the file follows it (`devModeKey`, `onboardingCompletedKey`, `isAutoVpnForSync`, `isAutoVpnOnExternal`, …). Reading one back always goes through `?.toBoolean() ?: false`; adding a `booleanPreferencesKey` for a new flag would work in isolation but would break the pattern every other flag in the file follows, and gives future code two different key types to check when looking something up.
- **`PreferencesManager` exposes state as `Flow`s, not suspend getters**, so callers observe changes rather than polling. The cost of this is on the test side: a ViewModel that calls `.first()` on one of these flows in its `init` block needs that exact flow stubbed in the corresponding unit test, or the ViewModel hangs waiting on an unstubbed `Flow` that never emits — see `app/src/test/CLAUDE.md` for the stubbing pattern.
- **`security/` holds everything sensitive; `datastore/` does not.** `SecurePreferencesManager` wraps `EncryptedSharedPreferences` (AES256-GCM via Android Keystore) and is where `PreferencesManager` itself delegates for access/refresh tokens and the Fritz!Box password — `PreferencesManager` is not a safe place for credentials even though it's the more commonly injected class. `PinManager` (SHA-256 + salt, constant-time comparison) and `BiometricAuthManager` build on top of it for app-lock authentication; `AppLockManager` tracks background/foreground timestamps in plain DataStore (not sensitive by itself) but checks `SecurePreferencesManager` to decide whether a lock method is configured at all.
- **`SecureStorage.kt` is provided by Hilt (`di/DatabaseModule.kt`) but nothing else in the app injects it.** It duplicates `SecurePreferencesManager`'s job (same `EncryptedSharedPreferences` file name `baluhost_secure_prefs`, overlapping access/refresh-token keys) but is a separate, unrelated instance. Treat it as dead code rather than a second place to store secrets — new credential storage belongs in `SecurePreferencesManager`, not here, and this duplication is worth cleaning up rather than extending.
- **`PluginTranslationCache` is an in-memory `@Singleton`, nothing is persisted.** It's refilled every time the power dialog opens (plugin menu strings come from the UI manifest, which is fetched fresh each time) and falls back to the English `message_text` from the manifest if a translation lookup misses — there's no persistence layer to fall back to on a cold start.

## Files

### `database/`
| File | Role |
|---|---|
| `BaluHostDatabase.kt` | Room database definition, version 5, registers all entities/DAOs/converters; also holds `MIGRATION_4_5` (top-level `val` in the same file) |
| `dao/FileDao.kt` | CRUD for cached file listings |
| `dao/FileActivityDao.kt` | CRUD for the buffered file-activity feed |
| `dao/PendingOperationDao.kt` | CRUD for the offline operation queue |
| `dao/UserDao.kt` | CRUD for cached user info |
| `dao/NotificationDao.kt` | CRUD + observation for the local notification cache, scoped by `owner_user_id` |
| `entities/FileEntity.kt` | Room entity for cached files |
| `entities/FileActivityEntity.kt` | Room entity for buffered activity entries |
| `entities/PendingOperationEntity.kt` | Room entity for queued offline operations |
| `entities/UserEntity.kt` | Room entity for cached user info |
| `entities/NotificationEntity.kt` | Room entity for the local notification cache, PK `(owner_user_id, id)`; the four `local*` timestamp columns double as the offline outbox — see the doc comment on the class |
| `converters/Converters.kt` | Room `@TypeConverters` for the database, including `fromStringAnyMap`/`toStringAnyMap` (Gson-backed `Map<String, Any>?` ↔ `String?` for `NotificationEntity.metadata`) |
| `mappers/PendingOperationMapper.kt` | `PendingOperationEntity` ↔ `PendingOperation` domain model |

### `datastore/`
| File | Role |
|---|---|
| `datastore/PreferencesManager.kt` | Non-sensitive preferences (server URL, VPN config, sync history/schedules cache, Fritz!Box host/port, etc.) as `Flow`s |

### `security/`
| File | Role |
|---|---|
| `security/SecurePreferencesManager.kt` | `EncryptedSharedPreferences`-backed storage for tokens, PIN hash/salt, adapter/Fritz!Box credentials |
| `security/PinManager.kt` | PIN setup/verification (SHA-256 + salt, constant-time compare) |
| `security/BiometricAuthManager.kt` | Biometric availability checks and prompt handling |
| `security/AppLockManager.kt` | Background/foreground timestamps and auto-lock timeout logic |
| `security/SecureStorage.kt` | Unused duplicate of `SecurePreferencesManager` (see above) — do not extend |

### `cache/` and top-level
| File | Role |
|---|---|
| `cache/CachedFileDao.kt` | Room DAO + entity for offline file-browser metadata (separate from `database/`'s Room setup) |
| `PluginTranslationCache.kt` | In-memory plugin string translations, refilled per power-dialog open |

## Adding a new local store

1. Non-sensitive, structured data with change observation → add keys/methods to `datastore/PreferencesManager.kt`; store booleans as `"true"`/`"false"` strings via `stringPreferencesKey`, matching the rest of the file.
2. Sensitive data (tokens, credentials, secrets) → add to `security/SecurePreferencesManager.kt`, never to `datastore/PreferencesManager.kt` directly or to `security/SecureStorage.kt`.
3. Structured, queryable data → add an entity + DAO under `database/`, register the entity in `BaluHostDatabase.kt`, and bump `version`.
4. Provide the new class in the relevant Hilt module (`di/DatabaseModule.kt` for most of `data/local/`) so it can be injected.
