# BaluHost Android App

Native Android mobile client for the BaluHost NAS management system.

The server lives in its own repository. This app talks to its FastAPI backend and
mirrors much of what the BaluHost web app can do — file access, power management,
VPN, and sync.

## Technology Stack

- **Language:** Kotlin 2.0.0
- **UI Framework:** Jetpack Compose with Material 3
- **Architecture:** MVVM, layered data / domain / presentation
- **Dependency Injection:** Hilt 2.51.1
- **Networking:** Retrofit + OkHttp + Gson
- **Local Storage:** Room + DataStore
- **VPN:** WireGuard Android Library
- **Build:** AGP 8.5.2, Gradle 8.9, Java 21
- **Min SDK:** 26 (Android 8.0) · **Target/Compile SDK:** 35

## Features

- 📱 QR code device registration with ML Kit barcode scanning
- 🔐 JWT authentication with automatic token refresh
- 🔒 WireGuard VPN integration for remote access
- 📂 File browsing with upload, download and delete
- ⚡ Power management: wake, soft sleep, suspend, display toggle, gaming mode
- 🌙 Sleep configuration including an admin-settable always-awake override
- 🔄 Folder sync with schedules, plus an offline queue for actions taken while away
- 📊 Monitoring and energy dashboards
- 🔏 Optional app lock with PIN or biometrics

## Project Structure

```
app/src/main/java/com/baluhost/android/
├── BaluHostApplication.kt          # Application class with Hilt
├── di/                             # Hilt modules
├── data/
│   ├── local/                      # Room, DataStore, encrypted storage
│   ├── remote/                     # Retrofit APIs, DTOs, interceptors
│   ├── repository/                 # Repository implementations
│   ├── sync/                       # Sync orchestration
│   ├── worker/                     # WorkManager workers
│   └── notification/               # FCM and WebSocket notifications
├── domain/
│   ├── model/                      # Domain types
│   ├── repository/                 # Repository interfaces
│   └── usecase/                    # Use cases
├── presentation/
│   ├── ui/                         # Screens, ViewModels, components, theme
│   └── navigation/                 # Routes and NavGraph
├── service/, services/             # Android services (VPN, FCM)
└── util/                           # Cross-cutting helpers
```

Most of these directories carry their own `CLAUDE.md` describing the conventions
that apply there. Start from the root [`CLAUDE.md`](CLAUDE.md).

## Setup

### Prerequisites

- An Android Studio recent enough for AGP 8.5.2
- **JDK 21** — the build sets `sourceCompatibility`/`targetCompatibility` to 21
  and will not run on 17
- Android SDK with API 35
- Gradle comes from the wrapper; no separate install needed

### Building

1. Clone the repository.
2. **Provide `app/google-services.json`.** It is gitignored and the
   `com.google.gms.google-services` plugin fails the build without it, so a
   fresh clone does not compile until you supply one. CI reconstructs it from a
   repository secret.
3. Open the project in Android Studio and sync Gradle.
4. Build and run on a device or emulator.

You do **not** need to edit `BASE_URL` in `app/build.gradle.kts`. It is only a
fallback — `DynamicBaseUrlInterceptor` rewrites the host at runtime from the
server address stored during device registration.

### Development

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests — see the warning below before trusting a green run
./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache
```

> **The build cache can fake a passing test run.** `gradle.properties` sets
> `org.gradle.caching=true`, so a bare `./gradlew test` may report
> `BUILD SUCCESSFUL` with `FROM-CACHE` without executing anything, and Gradle
> prints no test count when everything passes. Always use the command above, and
> see [`app/src/test/CLAUDE.md`](app/src/test/CLAUDE.md) for how to confirm the
> run actually happened.

There is no `androidTest` source set — no instrumented or Compose UI tests exist,
and there is no coverage plugin configured. Anything needing a device is verified
by hand.

## Status

The app is in active use against a live BaluHost server. Authentication, VPN,
file access, power management, sleep configuration, sync and monitoring all work
on-device.

Known gaps:

- The `DocumentsProvider` for Android Files app integration is written but
  commented out in `AndroidManifest.xml` — it needs finishing before it can be
  enabled.
- There is no automatic camera or photo backup.
- `presentation/ui/screens/WebDavScreen.kt` exists but is not wired into
  navigation, so it cannot currently be reached.

## Documentation

- **Architecture and conventions:** [`CLAUDE.md`](CLAUDE.md) and the per-directory
  `CLAUDE.md` files it indexes
- **Design specs and implementation plans:** `docs/superpowers/`
- **Changelog:** [`CHANGELOG.md`](CHANGELOG.md)

## Backend API

The app connects to the BaluHost FastAPI backend. Key endpoints:

- `POST /api/mobile/token/generate?include_vpn=true` — generate QR (desktop)
- `POST /api/mobile/register` — register device
- `POST /api/auth/refresh` — refresh access token
- `GET /api/files/list?path=<path>` — list files
- `POST /api/files/upload` — upload file
- `GET /api/files/download?path=<path>` — download file
- `POST /api/vpn/generate-config` — generate VPN config

The full set is visible in `data/remote/api/` — one Retrofit interface per
feature area.

## Releasing

Releases are cut from a tag, not from a push to `main`. The tag is the source of
truth for the version: `versionName` comes from it, and `versionCode` is derived
as `major * 10000 + minor * 100 + patch`.

1. Collect changes under `## [Unreleased]` in `CHANGELOG.md` as you go.
2. To release, rename that section to `## [1.2.3] - YYYY-MM-DD` and put a fresh
   empty `## [Unreleased]` above it.
3. Merge the commit into `main`, then switch to it locally:

```bash
git checkout main
git pull
```

   The release workflow checks that the tagged commit is reachable from
   `main`. Tagging straight off `development` (the branch you likely worked
   on) fails that check.
4. Tag and push:

```bash
git tag v1.2.3
git push origin v1.2.3
```

The release workflow refuses to build if the tag is malformed, if the tagged
commit is not reachable from `main`, if `CHANGELOG.md` has no section for that
version, or if the tests fail. The extracted changelog section becomes the
release notes.

Minor and patch must stay below 100, or the `versionCode` formula loses its
monotonicity. `v0.0.0` is rejected outright, since it would produce
`versionCode = 0`.

### If a release run fails partway

The release is created as a draft and only published after the APK uploads
successfully, so a failed run should leave nothing visible — but to clean up
and retry from scratch:

1. Delete the remote tag: `git push --delete origin v1.2.3`
2. Delete any release that got created (draft or published) for that tag, on
   the GitHub Releases page or via `gh release delete v1.2.3`.
3. Fix the underlying problem, then re-tag and push as above.

## Security

- Access and refresh tokens are held in `EncryptedSharedPreferences` via
  `data/local/security/SecurePreferencesManager.kt`.
- The app can be locked behind a PIN or biometric prompt (`AppLockManager`,
  `PinManager`, `BiometricAuthManager`).
- **Cleartext HTTP is permitted deliberately.** `AndroidManifest.xml` sets
  `usesCleartextTraffic="true"` and `res/xml/network_security_config.xml` allows
  it for the whole base config, because a NAS on the local network is usually
  reached by hostname or IP where a valid certificate is impractical. A
  self-signed BaluHost certificate is trusted for `baluhost.local`, `baluhost`
  and one hardcoded LAN address. There is **no certificate pinning**, and the
  base config also trusts user-installed CAs. Remote access is meant to go
  through the WireGuard tunnel rather than over the open internet.
- The `cached_files` Room table is a plain, unencrypted table holding file
  metadata and the local path of any downloaded content.
