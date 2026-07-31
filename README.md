# BaluHost Android App

Native Android mobile client for BaluHost NAS management system.

## Technology Stack

- **Language:** Kotlin 1.9+
- **UI Framework:** Jetpack Compose with Material 3
- **Architecture:** Clean Architecture + MVVM
- **Dependency Injection:** Hilt
- **Networking:** Retrofit + OkHttp
- **Local Storage:** Room + DataStore
- **VPN:** WireGuard Android Library
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)

## Features

- 📱 QR code device registration with ML Kit barcode scanning
- 🔐 Secure JWT authentication with automatic token refresh
- 🔒 WireGuard VPN integration for secure remote access
- 📂 File management with upload/download/delete operations
- 📁 Android Files app integration via DocumentsProvider
- 📸 Automatic camera backup with WorkManager
- ⚙️ Background sync with configurable settings
- 🌙 Material 3 design with dark mode support

## Project Structure

```
app/src/main/java/com/baluhost/android/
├── BaluHostApplication.kt          # Application class with Hilt
├── di/                             # Dependency Injection modules
├── data/                           # Data layer (API, Database, Repository)
│   ├── local/
│   ├── remote/
│   └── repository/
├── domain/                         # Domain layer (Models, UseCases)
│   ├── model/
│   ├── repository/
│   └── usecase/
├── presentation/                   # Presentation layer (UI, ViewModels)
│   ├── ui/
│   └── navigation/
└── service/                        # Android Services (VPN, Sync, Provider)
```

## Setup

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK with API 34
- Gradle 8.1+

### Building

1. Clone the repository
2. Open project in Android Studio
3. Sync Gradle files
4. Update `BASE_URL` in `app/build.gradle.kts` with your server address
5. Build and run on emulator or device

### Development

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest

# Generate test coverage report
./gradlew jacocoTestReport
```

## Implementation Progress

### Phase 1: Authentication + QR (2 weeks) - 🚧 In Progress
- [ ] Project setup and dependencies
- [ ] QR scanner with ML Kit
- [ ] Device registration flow
- [ ] Token management with DataStore
- [ ] Secure storage with EncryptedSharedPreferences

### Phase 2: VPN + Files (2 weeks) - ⏳ Pending
- [ ] WireGuard VPN service
- [ ] VPN connection UI
- [ ] File browser with Compose
- [ ] Upload/download with progress
- [ ] File operations (delete, move, rename)

### Phase 3: Advanced Features (2 weeks) - ⏳ Pending
- [ ] Camera backup with WorkManager
- [ ] DocumentsProvider for Files app
- [ ] Background sync configuration
- [ ] Settings screen
- [ ] Offline mode

### Phase 4: Testing + Polish (1 week) - ⏳ Pending
- [ ] Unit tests for all use cases
- [ ] UI tests with Compose Test
- [ ] Integration tests with MockWebServer
- [ ] Performance optimization
- [ ] Accessibility improvements

## Documentation

- **Full Implementation Guide:** `/docs/ANDROID_APP_GUIDE.md`
- **Backend API Reference:** `/docs/api/API_REFERENCE.md`
- **Architecture Overview:** `/docs/ARCHITECTURE.md`

## Backend API

The app connects to the BaluHost FastAPI backend. Key endpoints:

- `POST /api/mobile/token/generate?include_vpn=true` - Generate QR (Desktop)
- `POST /api/mobile/register` - Register device
- `POST /api/auth/refresh` - Refresh access token
- `GET /api/files/list?path=<path>` - List files
- `POST /api/files/upload` - Upload file
- `GET /api/files/download?path=<path>` - Download file
- `POST /api/vpn/generate-config` - Generate VPN config

## Testing

Run the complete test suite:

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires emulator/device)
./gradlew connectedAndroidTest

# Coverage report
./gradlew jacocoTestReport
```

## Releasing

Releases are cut from a tag, not from a push to `main`. The tag is the source of
truth for the version: `versionName` comes from it, and `versionCode` is derived
as `major * 10000 + minor * 100 + patch`.

1. Collect changes under `## [Unreleased]` in `CHANGELOG.md` as you go.
2. To release, rename that section to `## [1.2.3] - YYYY-MM-DD` and put a fresh
   empty `## [Unreleased]` above it.
3. Get the commit onto `main`.
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
monotonicity.

## Security

- JWT tokens stored in EncryptedSharedPreferences
- Network communication over HTTPS with certificate pinning
- VPN credentials encrypted at rest
- File data never cached unencrypted

## License

See [LICENSE](../LICENSE) in root directory.

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines.
