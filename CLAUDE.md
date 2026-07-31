# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Side findings

When a change, review, or piece of research turns up something that does **not**
belong to the current task — a pre-existing bug, a piece of legacy debt, a latent
risk, an out-of-scope improvement, or sensible follow-up work — do not quietly fix
it along the way and do not stay silent about it either:

1. **Name it briefly**: the problem, the location as `file:line`, and why it is out
   of scope for what is currently being worked on.
2. **Ask whether a GitHub issue should be created** (`gh issue create`) before
   creating one.
3. **On approval**, create it with a clear title, a description of the problem, the
   location, and a proposed fix — then report the issue number back.

The point is that these land in the issue tracker where they can be found again,
not in a chat message that scrolls away. When in doubt, ask rather than skip.

## Project Overview

BaluApp is the Android client for the self-hosted BaluHost home server
([`Xveyn/BaluHost`](https://github.com/Xveyn/BaluHost)). Kotlin with Jetpack Compose
(Material 3), MVVM, Hilt for dependency injection, Retrofit/OkHttp for HTTP, Room
plus DataStore for local state, WorkManager for background sync, and the WireGuard
Android library for VPN. `minSdk` 26, `compileSdk`/`targetSdk` 35.

The server lives in its own repository. **Changes to API contracts belong there**,
not here — this repo only consumes them. Anything that requires a new or changed
endpoint has to be done on the server side first.

## Architecture

The intended layering is the conventional one — `presentation` depends on `domain`,
`domain` is depended upon by `data` — and roughly the top half of the codebase
follows it. **It is not followed uniformly, and assuming otherwise is the fastest
way to write code that looks out of place.** Roughly half of the 55 use cases skip
the repository layer entirely and inject an `*Api`, a DAO, or a manager directly,
in several cases even though a matching `domain/repository/*Repository` interface
exists and simply goes unused (`auth/`, `files/`). Those use cases then own the HTTP
error mapping and DTO→domain conversion that a repository would otherwise do.
`domain/usecase/CLAUDE.md` has the verified per-directory breakdown; check a use
case's constructor rather than inferring its shape from its subdirectory.

```
app/src/main/java/com/baluhost/android/
├── BaluHostApplication.kt      # @HiltAndroidApp entry point
├── di/                         # 7 Hilt modules, all SingletonComponent — see di/CLAUDE.md
├── data/
│   ├── local/                  # Room, DataStore, encrypted secrets, in-memory caches (19)
│   ├── remote/                 # Retrofit interfaces, DTOs, OkHttp interceptors (42)
│   ├── repository/             # 13 repository implementations; the layer that decides
│   │                           #   what a failure means to the rest of the app
│   ├── sync/                   # Sync orchestration: storage adapters (SAF/SMB/WebDAV),
│   │                           #   migration manager, WebDAV account handling (10)
│   ├── worker/                 # WorkManager workers and their schedulers: folder sync,
│   │                           #   scheduled sync, offline-queue retry, cache cleanup (9)
│   ├── notification/           # FCM/WebSocket notification plumbing and the sync
│   │                           #   notification surface (5)
│   └── network/                # Fritz!Box TR-064 client, network monitor, server
│                               #   connectivity check (3)
├── domain/
│   ├── model/                  # 26 plain Kotlin models — no android.*, one documented
│   │                           #   exception for SAF URIs
│   ├── repository/             # 12 repository interfaces, bound in di/RepositoryModule.kt
│   ├── repo/                   # LocalStorageRepository.kt only — legacy, see below (1)
│   ├── usecase/                # 55 files in ten feature subdirectories, plus
│   │                           #   OfflineQueueManager.kt
│   ├── adapter/                # CloudAdapter.kt — the storage-adapter interface (1)
│   └── service/                # ConflictDetectionService.kt — sync conflict detection (1)
├── presentation/
│   ├── ui/                     # All Compose: screens/, components/, theme/ (67)
│   ├── navigation/             # Screen.kt (routes) and NavGraph.kt — documented inside
│   │                           #   presentation/ui/CLAUDE.md, not separately (2)
│   └── viewmodel/              # WebDavViewModel.kt only — legacy, see below (1)
├── service/vpn/                # VpnConnectionManager.kt — WireGuard tunnel lifecycle (1)
├── services/                   # BaluFirebaseMessagingService.kt — FCM receiver (1)
└── util/                       # Cross-cutting helpers: Clock, Base64Decoder,
                                #   NetworkStateManager, Result, Logger, formatters (14)
```

Counts are `.kt` files, recursive.

## Building and testing

**Read `app/src/test/CLAUDE.md` before running or writing tests.** It documents the
pitfalls this codebase keeps producing — relaxed mocks that return empty flows,
unbounded ViewModel polling loops that make `runTest` never finish, `android.util.*`
returning `null` under plain JUnit. They are not repeated here.

**The one thing that must not be missed: a green test run here can mean nothing.**
`gradle.properties` sets `org.gradle.caching=true`, so a bare

```
./gradlew testDebugUnitTest
```

can report `BUILD SUCCESSFUL in 1s` with `FROM-CACHE` or `UP-TO-DATE` **without
executing a single test**. Gradle also prints **no test count when everything
passes** — only on failure — so there is nothing in a successful run's output that
distinguishes "23 test classes passed" from "nothing ran." Always verify with:

```
./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache
```

Both CI workflows pass `--no-build-cache` for exactly this reason. To confirm a run
actually happened, count the XML results — the command is in `app/src/test/CLAUDE.md`.

There is **no `androidTest` source set**: no instrumented or Compose UI tests exist,
and no coverage report is configured, regardless of what `README.md` still says.
Anything needing a device is verified by hand.

```
./gradlew assembleDebug
```

builds the app. Run it after touching anything Hilt-related: **a missing binding
compiles cleanly and only fails when the graph is actually assembled**, which unit
tests do not do (see `di/CLAUDE.md`).

```
./gradlew assembleRelease
```

catches a further class of problems that neither unit tests nor `assembleDebug` see,
because the release build type enables R8 (`isMinifyEnabled = true`) with the
ProGuard rules in `app/proguard-rules.pro`. Reflection-dependent code — Gson DTOs
above all — can work perfectly in debug and break only here.

`app/google-services.json` is required by the `com.google.gms.google-services`
plugin and is gitignored, so a fresh clone does not build until it is supplied. CI
reconstructs it from a repository secret.

## Releasing

Releases are cut from a **tag**, not from a push to `main`; the tag is the source of
truth for `versionName`, and `versionCode` is derived from it. The workflow refuses
to build unless the tagged commit is reachable from `main` and `CHANGELOG.md`
contains a section for that version.

The full procedure — including how to recover from a run that fails partway — is in
the **"Releasing"** section of `README.md` and is deliberately not duplicated here.

## Known structural inconsistencies

These are real, currently present, and none of them is the pattern to follow. They
are listed because a description that hides its exceptions is a trap for whoever
creates the next file: someone who sees `domain/repo/` and reasonably concludes it
is where repositories go has been misled by the directory listing, not by their own
carelessness.

- **`service/vpn/` and `services/` both exist.** `service/vpn/VpnConnectionManager.kt`
  is the WireGuard tunnel lifecycle; `services/BaluFirebaseMessagingService.kt` is
  the FCM receiver. One file each, near-identical directory names, no distinction in
  purpose that justifies the split.
- **`domain/repo/LocalStorageRepository.kt` sits next to `domain/repository/`, which
  holds twelve interfaces.** **New repository interfaces go in `domain/repository/`.**
  `domain/repo/` holds exactly this one file and should not grow.
- **`presentation/viewmodel/` contains a single file** (`WebDavViewModel.kt`), while
  every other ViewModel lives next to its screen under `presentation/ui/screens/`.
  **New ViewModels go next to their screen.** Note that this one is still live —
  `FolderSyncScreen.kt` instantiates it — so it cannot simply be deleted; see
  `presentation/ui/CLAUDE.md`.

## Directory guides

Each documented area has its own `CLAUDE.md` with the structure, conventions, and
reasoning specific to it. **Keep them in sync when adding or removing files, or when
changing a pattern they describe** — a guide that has quietly gone stale is worse
than no guide, because it is still trusted.

### Data layer (`app/src/main/java/com/baluhost/android/data/`)
- `remote/CLAUDE.md` — Retrofit interfaces per server route group, DTO/`@SerializedName`
  conventions and the silent-default trap, the five OkHttp interceptors
- `repository/CLAUDE.md` — the `Result<T>` pattern and its four documented exceptions,
  error mapping that distinguishes "rejected" from "unreachable"
- `local/CLAUDE.md` — Room, DataStore's string-encoded booleans, and why credentials
  belong in `security/` and nowhere else

### Domain layer (`app/src/main/java/com/baluhost/android/domain/`)
- `model/CLAUDE.md` — the 26 models and, per model, what a `null` actually means
- `usecase/CLAUDE.md` — the two dependency shapes, which subdirectories bypass
  repositories, and which repository interfaces go unused

### Presentation layer (`app/src/main/java/com/baluhost/android/presentation/`)
- `ui/CLAUDE.md` — screens, components, theme, the three-file navigation wiring, and
  the several different shapes ViewModels use to surface errors

### Wiring and tests
- `app/src/main/java/com/baluhost/android/di/CLAUDE.md` — the seven Hilt modules,
  `@Provides` vs `@Binds`, and what a forgotten binding costs
- `app/src/test/CLAUDE.md` — how to run the tests so they actually run, and every
  pitfall that has already cost someone an afternoon
