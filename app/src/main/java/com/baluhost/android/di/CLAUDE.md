# Dependency Injection (Hilt)

Seven Hilt modules, all `@InstallIn(SingletonComponent::class)` — everything the app builds is a single app-wide graph, there is no per-Activity or per-screen Hilt scope in this codebase. This is where object construction and interface binding happen; it's the layer a newcomer checks when something fails to compile with "cannot be provided without an `@Provides`-annotated method" or, worse, only fails at `assembleDebug`.

## Core concepts

- **`@Provides` vs `@Binds`, and why both exist.** `AppModule` is a Kotlin `object` using `@Provides`: a `@Provides` function has a body, so it's for dependencies that need actual construction logic (`NetworkMonitor` → `NetworkMonitorImpl(context)`, `Base64Decoder` → `AndroidBase64Decoder()`, `Clock` → a lambda, `MobileApiFactory` → `RetrofitMobileApiFactory()`). `RepositoryModule` is an `abstract class` using `@Binds`: a `@Binds` function is just an abstract method with an interface return type and an implementation parameter — no body — telling Hilt "when someone asks for `AuthRepository`, give them the injected `AuthRepositoryImpl`." `@Binds` is cheaper (no generated factory invocation, just a graph edge) and cannot express any actual construction, which is exactly why repository interface→impl bindings use it and constructed objects don't.
- **Use cases need no module at all.** Every `domain/usecase/**/*UseCase.kt` is `class XUseCase @Inject constructor(...)`; Hilt resolves the `@Inject` constructor automatically. Adding a use case only touches `di/` if it also introduces a brand-new repository interface that needs binding.
- **`NetworkModule` provides Retrofit itself and every API interface in `data/remote/api/`** — all 13 of them (`ActivityApi`, `AuthApi`, `EnergyApi`, `FilesApi`, `MobileApi`, `MonitoringApi`, `NotificationsApi`, `PluginApi`, `SharesApi`, `SleepApi`, `SyncApi`, `SystemApi`, `VpnApi`), each via `retrofit.create(XApi::class.java)`. `MobileApiFactory` is the one exception worth knowing about: it's a factory *interface*, not a Retrofit API, and it's provided in `AppModule`, not here. A new `*Api` interface needs its own `@Provides fun provideXApi(retrofit: Retrofit): XApi` in this file — miss it, and nothing fails until `assembleDebug` (or a unit test that happens to instantiate the graph), because the Hilt graph is only resolved when the APK is actually assembled, not when `XApi` alone compiles.

## Conventions

- **Module names describe what they own, not a generic "core"/"app" bucket.** `DatabaseModule` owns Room/DataStore/SecureStorage, `NetworkModule` owns Retrofit/OkHttp/API interfaces, `SyncModule` owns the storage-adapter family (SAF/SMB/WebDAV) plus `LocalStorageRepository`, `WorkerModule` owns `WorkManager` plus `LocalFolderScanner`, `ImageLoaderModule` owns Coil's `ImageLoader`. Adding a dependency to the wrong module (e.g. a new DAO into `NetworkModule`) works at compile time but makes the module boundary meaningless — put it where the next reader would look first.
- **`ImageLoaderModule` reaches into `NetworkModule`'s output on purpose.** `provideImageLoader` takes an injected `OkHttpClient` (comment: "Injected from NetworkModule") so Coil's image requests reuse the same `AuthInterceptor`-equipped client rather than hitting the NAS unauthenticated. Cross-module dependencies like this are fine — Hilt resolves them by type, module boundaries are just organizational — but keep the comment when you add one, since it's the only thing telling a reader the coupling is intentional.
- **`NetworkModule` provides two differently-configured `OkHttpClient`s.** The default one (interceptor chain: base URL → error → auth → sync-trigger → logging) is for normal REST calls; a second one, qualified `@Named("websocket")`, drops all the REST interceptors and sets `readTimeout` to `0` (no timeout) because a WebSocket connection is expected to sit open indefinitely. Reach for the `@Named` one only for actual long-lived socket work — REST calls should stay on the default client so they keep getting auth/error handling.
- **Repository bindings are always `@Singleton`, matching their implementation's lifecycle.** Every `@Binds` in `RepositoryModule` (and every `@Provides` for a Room DAO/OkHttpClient/Retrofit instance) carries `@Singleton` — these are all meant to be constructed once and shared for the process lifetime, not recreated per injection site.

## Files

| Module | Style | Provides |
|---|---|---|
| `AppModule.kt` | `@Provides` (`object`) | Application `Context`, `NetworkMonitor`, `Base64Decoder`, `MobileApiFactory`, `Clock` |
| `DatabaseModule.kt` | `@Provides` (`object`) | DataStore, `PreferencesManager`, `SecureStorage`, `BaluHostDatabase` (Room), its DAOs (`FileDao`, `UserDao`, `PendingOperationDao`, `FileActivityDao`) |
| `ImageLoaderModule.kt` | `@Provides` (`object`) | Coil `ImageLoader`, reusing `NetworkModule`'s `OkHttpClient` |
| `NetworkModule.kt` | `@Provides` (`object`) | OkHttp interceptors, `OkHttpClient` (default + `@Named("websocket")`), `Retrofit`, all 13 `*Api` interfaces, `NetworkStateManager` |
| `RepositoryModule.kt` | `@Binds` (`abstract class`) | Binds all 12 `domain/repository/*Repository` interfaces to their `data/repository/*RepositoryImpl` |
| `SyncModule.kt` | `@Provides` (`object`) | `ExternalStorageHelper`, `LocalStorageRepository`, `SAFStorageAdapter`, `WebDavAdapter`, `WebDavAdapterFactory`, `WebDavAccountManager`, `SmbAdapter` |
| `WorkerModule.kt` | `@Provides` (`object`) | `WorkManager`, `LocalFolderScanner` |

## Adding a new dependency

1. **Interface with an existing implementation** (a new repository, or any interface→impl pairing): add an abstract `@Binds @Singleton fun bind<Name>(impl: <Name>Impl): <Name>` to `RepositoryModule.kt` (or a new `abstract class` module if it's not a repository).
2. **Something that needs actual construction** (wraps a `Context`, calls a constructor with logic, builds a client): add a `@Provides @Singleton fun provide<Name>(...): <Name>` to the module that already owns that concern (see Conventions above for how modules are split), or to `AppModule.kt` if none fits.
3. **A new Retrofit API interface**: add both the interface under `data/remote/api/` and a `@Provides @Singleton fun provide<Name>Api(retrofit: Retrofit): <Name>Api` in `NetworkModule.kt`. Forgetting this compiles cleanly and only breaks at `assembleDebug` (or when Hilt actually assembles the graph), not at the unit-test level — don't rely on unit tests to catch a missing binding here.
4. **A use case**: no module change needed — `@Inject constructor` on the class is enough, provided every parameter it takes is itself already resolvable somewhere in the graph.
