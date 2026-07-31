# Presentation / UI

Everything Jetpack Compose renders lives here: 18 items under `screens/` (17 feature directories plus one loose file), 13 shared building blocks under `components/`, and the 3-file dark theme under `theme/`. `presentation/navigation/` (`Screen.kt`, `NavGraph.kt`) has no `CLAUDE.md` of its own — it is small enough, and coupled tightly enough to how screens get wired up, to document here instead.

## Core concepts

- **Screen + ViewModel live together.** A feature directory under `screens/` normally holds `<Feature>Screen.kt` next to `<Feature>ViewModel.kt` — e.g. `screens/dashboard/DashboardScreen.kt` and `DashboardViewModel.kt`. `presentation/viewmodel/WebDavViewModel.kt` is the one legacy exception: a single file left behind outside any feature directory. New ViewModels belong next to their screen, not there.
  - This pairing is the default, not a law: `screens/main/MainScreen.kt`, `screens/media/MediaViewerScreen.kt`, and `screens/storage/StorageOverviewScreen.kt` have no ViewModel at all (see below), and `WebDavScreen.kt` sits loose directly under `screens/` (not in its own subdirectory) while its ViewModel is the legacy `presentation/viewmodel/WebDavViewModel.kt` file — the one screen in the codebase that doesn't follow either half of the rule.
- **Not every screen has a ViewModel.** `MainScreen` is a bottom-nav container that composes other screens (see Navigation below) and needs no state of its own beyond `rememberNavController()`. `MediaViewerScreen` and `StorageOverviewScreen` manage their local state with plain `remember`/`mutableStateOf`. Don't assume `hiltViewModel()` is present just because a file is under `screens/`.
- **`WebDavScreen.kt` is presently unreachable.** It has no `Screen.kt` route and no `NavGraph.kt`/`MainScreen.kt` composable entry — nothing navigates to it. Its ViewModel is still live, though: `FolderSyncScreen.kt` instantiates `WebDavViewModel` directly (`hiltViewModel()`) to drive an embedded WebDAV account section, so don't delete the ViewModel while cleaning up the orphaned screen.

## Navigation

Reaching a new screen from the UI touches up to three files, depending on where the screen sits in the nav hierarchy:

1. **`presentation/navigation/Screen.kt`** — a route `object` on the `sealed class Screen(val route: String)`. Screens that take arguments (only `MediaViewer` does today) add a `createRoute(...)` helper here instead of a bare route string.
2. **`presentation/navigation/NavGraph.kt`** — a `composable(Screen.X.route) { ... }` entry in the top-level `NavHost`, wiring up the screen's navigation callbacks (`onNavigateBack`, etc.) to `navController`.
3. **`presentation/ui/screens/main/MainScreen.kt`** — only for screens that live *inside* the bottom-navigation tabs (currently `Dashboard`, `Files`, `Sync`, `Settings`, plus `Permissions`). `MainScreen` runs its own nested `NavHost` with its own `navController`; screens reachable only from within that nested graph (e.g. tapping a card on `Dashboard`) need a `composable(...)` entry here too, and any callback that needs to leave the tab area (e.g. `Dashboard → CpuDetail`) is wired to the *parent* `navController` passed into `MainScreen` as `parentNavController`, not the nested one.

A screen missing step 1 or 2 fails to compile (nothing references the route or the composable). A screen missing step 3 — when it belongs inside the bottom-nav tabs — compiles fine and is simply unreachable: this is exactly `WebDavScreen.kt`'s situation today (see above), except `WebDavScreen` is missing steps 1 and 2 as well, not just 3. Route objects that exist in `Screen.kt` but are handled entirely inside `MainScreen`'s nested graph (`Dashboard`, `Files`, `Sync`, `Settings`) intentionally have no matching entry in the top-level `NavGraph.kt` — that's not a bug, it's the split between the two navigation graphs.

## Conventions

- **User-facing text is German string literals in the code, not resources.** There is no i18n framework wired up: `stringResource` is imported in `screens/qrscanner/QrScannerScreen.kt` but never actually called there, and `res/values/strings.xml` holds a set of (mostly English) strings that no Compose screen reads — both are unused scaffolding, not a real second path for text. Every screen just writes the German string inline (e.g. `Text("Berechtigungen verwalten")`), so a copy change means editing the `.kt` file directly, and grepping for a UI string only ever needs to search `.kt` files.
- **ViewModel state is `StateFlow`.** Every `*ViewModel.kt` under `screens/` exposes its UI state as `StateFlow`, usually a single `data class UiState` — that's the one truly universal part of the pattern, because Compose's `collectAsState()` needs a hot, replayable stream.
- **Error/status surfacing is not one shape, or even two — count before copying one.** A `MutableSharedFlow<String>` event channel appears in 5 of 24 ViewModels: `DashboardViewModel`, `PowerDetailViewModel`, `AlwaysAwakeViewModel`, `FritzBoxSettingsViewModel` use `extraBufferCapacity = 1` for one-shot snackbar events, while `FolderSyncViewModel` uses a bufferless `MutableSharedFlow<String>()` for a running stream of sync-progress messages instead. A literal `error: String?` field on the `UiState` is the closest thing to a majority (14 files, e.g. `FilesViewModel`, `SettingsViewModel`, `VpnViewModel`) — but it overlaps the SharedFlow group rather than replacing it: `DashboardViewModel`, `PowerDetailViewModel`, and `FolderSyncViewModel` carry both, for different purposes. Everyone else does something else again: `AlwaysAwakeViewModel` names its field `loadError`; `PendingOperationsViewModel` reuses one `message: String?` for both success and failure text; `PermissionsViewModel` and `SyncScheduleViewModel` keep the error in a separate `MutableStateFlow<String?>` outside the main state object; `QrScannerViewModel` encodes it as a sealed `QrScannerState.Error(message)` variant; `SyncViewModel` marks failure per-folder via a `SyncStatus.ERROR` enum with no message text; and `OnboardingViewModel`, `SplashViewModel`, `WebDavViewModel` surface no error to the UI at all. Check the actual ViewModel before assuming which shape a given screen follows.
  - Where the `MutableSharedFlow` pattern *is* used, one user action produces exactly one emission — see the comment on `DashboardViewModel.enableDesktop()` for why: a second snackbar for the same action is bad UX, and with `extraBufferCapacity = 1` plus the default `BufferOverflow.SUSPEND`, a second `emit` would also block until the first is collected.
- **The background comes from `BaluBackground`** (defined in `components/GlassCard.kt`, alongside `GlassCard`/`GradientGlassCard`/`GlassSurface`), paired with `Scaffold(containerColor = Color.Transparent)` so the gradient shows through. This is dominant but not universal — 14 screens call it directly (`Dashboard`, all 5 `detail/` screens, `Notifications`, `NotificationPreferences`, `AlwaysAwake`, `FritzBoxSettings`, `Settings`, `Shares`, `Sync`, `Vpn`); screens reached before login (`Splash`, `Onboarding`, `QrScanner`, `Storage`), utility/dialog-style screens (`Lock`, `PendingOperations`, `Permissions`, `MediaViewer`), and — worth flagging specifically — `FilesScreen`, a bottom-nav peer of `Dashboard`/`Settings`/`Sync` that does *not* call it despite those three doing so, all skip it. When adding a screen that sits alongside ones using `BaluBackground`, follow the neighbor that actually uses it, not just any neighbor.
- **Snackbars need explicit colors.** `BaluHostTheme` (`theme/Theme.kt`) always applies `DarkColorScheme` regardless of system setting, and that scheme's `inverseSurface` is `Slate100` (light) — Material 3's default `Snackbar` paints its background from `inverseSurface`, so an un-styled snackbar on this theme renders as a near-white bar on a dark screen. Screens that show snackbars supply their own `Snackbar(containerColor = Slate800, contentColor = Color.White, ...)` inside `snackbarHost = { SnackbarHost(...) { data -> Snackbar(...) } }`; see `screens/settings/FritzBoxSettingsScreen.kt` for the reference implementation.
- **A `Row` does not shrink its children to fit.** Several `OutlinedButton`s side by side overflow a narrow (360dp) device instead of wrapping or shrinking. The established fix is `.horizontalScroll(rememberScrollState())` on the `Row`'s modifier — see `screens/detail/PowerDetailScreen.kt`, which wraps its action-button row this way.

## Files

### `screens/` (18 items: 17 directories + 1 loose file)

| Directory | Contents |
|---|---|
| `dashboard/` | `DashboardScreen`/`DashboardViewModel` — home screen: power actions (wake/sleep/suspend, desktop enable/disable, gaming mode), telemetry cards, navigation hub to most detail screens |
| `detail/` | Five screen/ViewModel pairs (`Cpu`, `Memory`, `Power`, `Storage`, `Uptime`) — per-metric history and detail views pushed from the dashboard |
| `files/` | `FilesScreen`/`FilesViewModel` — file/folder browser, upload/download, offline-aware listing |
| `lock/` | `LockScreen`/`LockScreenViewModel` — PIN-gate screen shown when the app is locked |
| `main/` | `MainScreen` — bottom-nav container hosting `Dashboard`/`Files`/`Sync`/`Settings` in a nested `NavHost`; no ViewModel |
| `media/` | `MediaViewerScreen` — pinch-to-zoom image viewer and full video player for files opened from `Files`; no ViewModel |
| `notifications/` | `NotificationsScreen`/`NotificationsViewModel` and `NotificationPreferencesScreen`/`NotificationPreferencesViewModel` — notification list plus per-type preference toggles |
| `onboarding/` | `OnboardingScreen`/`OnboardingViewModel` — first-run flow before device registration |
| `pending/` | `PendingOperationsScreen`/`PendingOperationsViewModel` — queued offline operations awaiting sync, with cancel/retry |
| `permissions/` | `PermissionsScreen`/`PermissionsViewModel` — manage per-user file/folder permissions |
| `qrscanner/` | `QrScannerScreen`/`QrScannerViewModel` — camera QR scan for device registration |
| `settings/` | `SettingsScreen`/`SettingsViewModel` (app settings, logout, cache), `FritzBoxSettingsScreen`/`FritzBoxSettingsViewModel` (Fritz!Box Wake-on-LAN config), `AlwaysAwakeScreen`/`AlwaysAwakeViewModel` (always-awake override), plus `PinSetupDialog` |
| `shares/` | `SharesScreen`/`SharesViewModel` — create/manage file shares |
| `splash/` | `SplashScreen`/`SplashViewModel` — auth-status check and initial routing |
| `storage/` | `StorageOverviewScreen` — post-QR-scan storage summary before entering the main app; no ViewModel |
| `sync/` | `FolderSyncScreen`/`FolderSyncViewModel` (folder sync setup, conflict resolution, embeds `WebDavViewModel`), `SyncScheduleScreen`/`SyncScheduleViewModel` (sync scheduling), `SyncScreen`/`SyncViewModel` (sync status, bottom-nav tab) |
| `vpn/` | `VpnScreen`/`VpnViewModel` — VPN connect/disconnect, config import |
| *(loose file)* | `WebDavScreen.kt` — WebDAV credential test/browse UI; currently unreachable from navigation (see above) |

### `components/` (13 files)

| File | Provides |
|---|---|
| `AddSyncFolderDialog.kt` | Dialog to add a new sync folder, with NAS directory browsing |
| `BottomNavBar.kt` | Bottom navigation bar for `MainScreen`'s tabs |
| `ConflictResolutionDialog.kt` | Dialog for resolving sync conflicts |
| `FolderPickerDialog.kt` | SAF-based local folder picker with persistent URI permission |
| `GlassCard.kt` | `GlassCard`, `GradientGlassCard`, `GlassSurface`, and `BaluBackground` — the glassmorphism surface family plus the app-wide background |
| `GlassTextField.kt` | Glass-styled `OutlinedTextField` variant |
| `GradientButton.kt` | Button with a gradient background |
| `GradientText.kt` | Text with a gradient color effect |
| `NotificationBadge.kt` | `NotificationBell` — bell icon with unread-count badge (file name and composable name differ) |
| `OfflineBanner.kt` | Banner shown when the device is offline |
| `SyncFolderConfigDialog.kt` | Dialog for editing an existing sync folder's configuration |
| `TelemetryChart.kt` | Vico-based line chart for CPU/memory/power telemetry history |
| `VpnStatusBanner.kt` | Dismissible banner prompting VPN connection when outside the home network |

### `theme/` (3 files)

| File | Contents |
|---|---|
| `Color.kt` | Named color constants (`Sky400`, `Slate950`, etc.) |
| `Theme.kt` | `BaluHostTheme`, `DarkColorScheme`/`LightColorScheme` — always applies `DarkColorScheme` regardless of system setting |
| `Type.kt` | `Typography` definition |

## Adding a new screen

1. Create `screens/<feature>/<Feature>Screen.kt`. If it needs state, add `<Feature>ViewModel.kt` in the same directory (`class <Feature>ViewModel @Inject constructor(...) : ViewModel()`, `StateFlow` for state).
2. Add a route object to `presentation/navigation/Screen.kt`: `object <Feature> : Screen("<feature_route>")`. Add a `createRoute(...)` helper only if the screen takes navigation arguments (see `Screen.MediaViewer`).
3. Decide where the screen is reachable from:
   - If it's pushed on top of everything (typical for detail/settings-style screens), add `composable(Screen.<Feature>.route) { <Feature>Screen(onNavigateBack = { navController.popBackStack() }, ...) }` to `presentation/navigation/NavGraph.kt`, and wire the triggering callback from whichever screen navigates to it.
   - If it belongs inside the bottom-nav tabs, add the `composable(...)` entry to the nested `NavHost` in `screens/main/MainScreen.kt` instead, and use `parentNavController` (not the nested `navController`) for any callback that needs to leave the tab area.
4. Wrap the screen body in `BaluBackground { ... }` inside a `Scaffold(containerColor = Color.Transparent)` if it should match the standard glassmorphism look (see Conventions above for the screens that don't).
5. If the screen shows snackbars, supply the custom `Snackbar(containerColor = Slate800, contentColor = Color.White, ...)` — the default renders wrong against `DarkColorScheme` (see Conventions).
6. If a `Row` can hold more content than fits a narrow device, add `.horizontalScroll(rememberScrollState())` to it.
7. Verify the screen is actually reachable — no route object, no `NavGraph`/`MainScreen` composable entry, and no wired-up callback all compile silently. `WebDavScreen.kt` in this codebase is a live example of what skipping this step produces.
