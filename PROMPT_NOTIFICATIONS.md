# Prompt: Notification-System in BaluApp implementieren

Du arbeitest im BaluApp Android-Projekt (`D:\Programme (x86)\BaluApp`). Implementiere ein vollständiges Notification-System, das sich an das bestehende BaluHost-Backend anbindet. Das Backend hat bereits eine fertige Notification-API (REST + WebSocket + FCM Push). Die App muss diese konsumieren.

---

## Architektur-Kontext (STRIKT einhalten)

Die App nutzt **Clean Architecture + MVVM** mit diesen Schichten:

- **data/** — Retrofit APIs, DTOs, Room DAOs, Repository-Implementierungen, DataStore
- **domain/** — Repository-Interfaces, Use Cases (`operator fun invoke()`), Domain Models
- **presentation/** — Jetpack Compose Screens, `@HiltViewModel` ViewModels mit `StateFlow<UiState>`
- **di/** — Hilt `@Module`s mit `@InstallIn(SingletonComponent::class)`

**Patterns die du exakt einhalten musst:**
- DI: **Hilt** (`@Singleton`, `@HiltViewModel`, `@Provides`, `@Binds`)
- Networking: **Retrofit 2.9.0 + OkHttp 4.12.0 + Gson** — API-Interfaces als `@Singleton` in `NetworkModule.kt`
- State: **`StateFlow<UiState>`** — `MutableStateFlow` privat, `StateFlow` public via `asStateFlow()`, `UiState` ist eine `data class` mit Defaults
- Result: **`util/Result.kt`** sealed class (`Success`, `Error`, `Loading`) — alle Use Cases returnen `Result<T>`
- Navigation: **Jetpack Compose Navigation 2.8.3** — Routes definiert in `Screen.kt` als sealed class
- UI: **Jetpack Compose + Material 3**, Dark Theme only, Glassmorphism Design (nutze `GlassCard`, `GlassSurface`, etc.)
- Strings: **Hardcoded auf Deutsch** inline (kein `strings.xml` für Notifications — das ist das bestehende Pattern)
- Room DB: **Version 4** in `BaluHostDatabase.kt` — erhöhe auf Version 5 wenn du eine neue Entity brauchst

**Bestehende relevante Dateien:**
- `di/NetworkModule.kt` — hier neue API-Interfaces registrieren
- `di/DatabaseModule.kt` — Room + DataStore
- `di/RepositoryModule.kt` — `@Binds` für Repository Interface→Impl
- `data/remote/interceptors/AuthInterceptor.kt` — fügt `Authorization: Bearer` + `X-Device-ID` automatisch hinzu
- `data/local/datastore/PreferencesManager.kt` — hat bereits `fcmToken` und `deviceId`
- `data/remote/api/MobileApi.kt` — hat bereits `registerPushToken(deviceId, body)` Endpoint
- `services/BaluFirebaseMessagingService.kt` — FCM Service, speichert Token nur lokal (TODO: Backend-Sync)
- `data/notification/SyncNotificationManager.kt` — bestehende Sync-Notifications
- `data/notification/ServerConnectionMonitor.kt` — Polling alle 30s, `StateFlow<ConnectionState>`
- `presentation/navigation/Screen.kt` — sealed class mit allen Routes
- `presentation/navigation/NavGraph.kt` — NavHost
- `presentation/ui/screens/dashboard/DashboardViewModel.kt` — Referenz-ViewModel für Pattern
- `presentation/ui/components/GlassCard.kt` — Basis-UI-Komponente
- `presentation/ui/theme/Color.kt` — Design Tokens (Sky400, Slate900, etc.)

---

## Was implementiert werden muss

### Phase 1: Data Layer

#### 1.1 DTOs (`data/remote/dto/NotificationDto.kt`)

```kotlin
data class NotificationDto(
    val id: Int,
    val created_at: String,           // ISO 8601 UTC
    val user_id: Int?,
    val notification_type: String,    // "info" | "warning" | "critical"
    val category: String,             // "raid"|"smart"|"backup"|"scheduler"|"system"|"security"|"sync"|"vpn"
    val title: String,
    val message: String,
    val action_url: String?,
    val is_read: Boolean,
    val is_dismissed: Boolean,
    val priority: Int,                // 0-3
    val metadata: Map<String, Any>?,
    val time_ago: String?,            // null bei WebSocket-Nachrichten
    val snoozed_until: String?
)

data class NotificationListResponse(
    val notifications: List<NotificationDto>,
    val total: Int,
    val unread_count: Int,
    val page: Int,
    val page_size: Int
)

data class UnreadCountResponse(
    val count: Int,
    val by_category: Map<String, Int>?
)

data class MarkReadResponse(
    val success: Boolean,
    val count: Int
)

data class NotificationPreferencesDto(
    val id: Int,
    val user_id: Int,
    val email_enabled: Boolean,
    val push_enabled: Boolean,
    val in_app_enabled: Boolean,
    val quiet_hours_enabled: Boolean,
    val quiet_hours_start: String?,   // "HH:MM" oder "HH:MM:SS"
    val quiet_hours_end: String?,
    val min_priority: Int,
    val category_preferences: Map<String, CategoryPreference>?
)

data class CategoryPreference(
    val email: Boolean,
    val push: Boolean,
    val in_app: Boolean
)

data class NotificationPreferencesUpdate(
    val email_enabled: Boolean? = null,
    val push_enabled: Boolean? = null,
    val in_app_enabled: Boolean? = null,
    val quiet_hours_enabled: Boolean? = null,
    val quiet_hours_start: String? = null,
    val quiet_hours_end: String? = null,
    val min_priority: Int? = null,
    val category_preferences: Map<String, CategoryPreference>? = null
)

data class WsTokenResponse(
    val token: String
)

data class MarkReadRequest(
    val category: String? = null
)
```

#### 1.2 Retrofit API Interface (`data/remote/api/NotificationsApi.kt`)

```kotlin
interface NotificationsApi {
    @GET("notifications")
    suspend fun getNotifications(
        @Query("unread_only") unreadOnly: Boolean = false,
        @Query("include_dismissed") includeDismissed: Boolean = false,
        @Query("category") category: String? = null,
        @Query("notification_type") notificationType: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50
    ): NotificationListResponse

    @GET("notifications/unread-count")
    suspend fun getUnreadCount(): UnreadCountResponse

    @POST("notifications/{id}/read")
    suspend fun markAsRead(@Path("id") id: Int): NotificationDto

    @POST("notifications/read-all")
    suspend fun markAllAsRead(@Body request: MarkReadRequest = MarkReadRequest()): MarkReadResponse

    @POST("notifications/{id}/dismiss")
    suspend fun dismiss(@Path("id") id: Int): NotificationDto

    @POST("notifications/{id}/snooze")
    suspend fun snooze(@Path("id") id: Int, @Query("duration_hours") durationHours: Int): NotificationDto

    @GET("notifications/preferences")
    suspend fun getPreferences(): NotificationPreferencesDto

    @PUT("notifications/preferences")
    suspend fun updatePreferences(@Body update: NotificationPreferencesUpdate): NotificationPreferencesDto

    @POST("notifications/ws-token")
    suspend fun getWsToken(): WsTokenResponse
}
```

Registriere dieses Interface in `NetworkModule.kt` als `@Provides @Singleton` (gleiches Pattern wie alle anderen APIs dort).

#### 1.3 Domain Models (`domain/model/Notification.kt`)

Erstelle Domain Models die von DTOs gemappt werden. Verwende enums statt Strings:

```kotlin
enum class NotificationType { INFO, WARNING, CRITICAL }
enum class NotificationCategory { RAID, SMART, BACKUP, SCHEDULER, SYSTEM, SECURITY, SYNC, VPN }

data class AppNotification(
    val id: Int,
    val createdAt: String,
    val userId: Int?,
    val type: NotificationType,
    val category: NotificationCategory,
    val title: String,
    val message: String,
    val actionUrl: String?,
    val isRead: Boolean,
    val isDismissed: Boolean,
    val priority: Int,
    val metadata: Map<String, Any>?,
    val timeAgo: String?,
    val snoozedUntil: String?
)
```

Nenne das Domain Model `AppNotification` (NICHT `Notification`), um Konflikte mit `android.app.Notification` zu vermeiden.

#### 1.4 Repository

Interface in `domain/repository/NotificationRepository.kt`, Impl in `data/repository/NotificationRepositoryImpl.kt`. Registriere in `RepositoryModule.kt` via `@Binds`.

Methoden:
- `suspend fun getNotifications(unreadOnly, category, page, pageSize): Result<NotificationListResponse>`
- `suspend fun getUnreadCount(): Result<UnreadCountResponse>`
- `suspend fun markAsRead(id): Result<AppNotification>`
- `suspend fun markAllAsRead(category?): Result<Int>`
- `suspend fun dismiss(id): Result<AppNotification>`
- `suspend fun snooze(id, hours): Result<AppNotification>`
- `suspend fun getPreferences(): Result<NotificationPreferencesDto>`
- `suspend fun updatePreferences(update): Result<NotificationPreferencesDto>`

#### 1.5 WebSocket Manager (`data/notification/NotificationWebSocketManager.kt`)

Nutze **OkHttp WebSocket** (bereits als Dependency vorhanden). KEIN extra Library.

```kotlin
@Singleton
class NotificationWebSocketManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val notificationsApi: NotificationsApi,
    private val preferencesManager: PreferencesManager
) {
    // StateFlows
    val connected: StateFlow<Boolean>
    val unreadCount: StateFlow<Int>
    val latestNotification: SharedFlow<NotificationDto>  // SharedFlow für Events

    suspend fun connect()     // 1. WS-Token holen, 2. WebSocket öffnen
    fun disconnect()          // Code 1000 senden
    fun sendMarkRead(id: Int) // {"type":"mark_read","payload":{"notification_id":<id>}}
}
```

**WebSocket-Protokoll (exakt einhalten):**

- **URL:** `ws://<serverUrl>/api/notifications/ws?token=<wsToken>`
  - `serverUrl` aus `PreferencesManager` lesen (ohne `/api/` Suffix!)
  - WS-Token via `POST /api/notifications/ws-token` holen (gültig 60 Sekunden)
- **Server→Client Nachrichten** (JSON):
  - `{"type":"unread_count","payload":{"count":5}}` — kommt sofort nach Connect + nach jedem mark_read
  - `{"type":"notification","payload":{...NotificationDto ohne time_ago...}}` — neue Notification
  - `{"type":"pong","payload":{}}` — Antwort auf Ping
- **Client→Server Nachrichten:**
  - `{"type":"ping"}` — alle 30 Sekunden senden
  - `{"type":"mark_read","payload":{"notification_id":42}}` — Notification als gelesen markieren
- **Reconnection:**
  - Bei Close-Code != 1000: reconnecten mit Backoff (3s × Versuch, max 5 Versuche)
  - Vor jedem Reconnect **neuen** WS-Token holen (der alte ist nach 60s abgelaufen)
  - Bei Erfolg: Versuchszähler zurücksetzen
  - Kein zweites WebSocket öffnen wenn eins schon OPEN/CONNECTING ist

**Wichtig:** Die `serverUrl` in PreferencesManager enthält den vollen API-Pfad (z.B. `http://192.168.178.21:8000/api/`). Für den WebSocket musst du den Pfad anpassen: ersetze `http://` mit `ws://`, `https://` mit `wss://`, und hänge `notifications/ws?token=...` an die Base-URL (ohne `/api/`).

#### 1.6 FCM Token Sync fixen

In `BaluFirebaseMessagingService.onNewToken()` — nach dem lokalen Speichern auch an Backend senden:

```kotlin
override fun onNewToken(token: String) {
    super.onNewToken(token)
    preferencesManager.saveFcmToken(token)

    // Token an Backend senden (wenn deviceId vorhanden)
    CoroutineScope(Dispatchers.IO).launch {
        val deviceId = preferencesManager.deviceId.first()
        if (!deviceId.isNullOrEmpty()) {
            try {
                mobileApi.registerPushToken(deviceId, mapOf("token" to token))
            } catch (e: Exception) {
                // Silently fail — wird beim nächsten App-Start erneut versucht
            }
        }
    }
}
```

Dafür muss `MobileApi` in den FCM Service injected werden (bereits `@AndroidEntryPoint`).

Zusätzlich: Nach erfolgreicher Device-Registration (in `QrScannerViewModel` oder wo die Registration passiert) den gespeicherten FCM-Token sofort an Backend senden.

#### 1.7 FCM Push Handler erweitern

In `BaluFirebaseMessagingService.onMessageReceived()` den neuen Typ `"notification"` (allgemeine Backend-Notifications) hinzufügen:

```kotlin
"notification" -> {
    val notificationId = data["notification_id"]?.toIntOrNull() ?: 0
    val category = data["category"] ?: "system"
    val priority = data["priority"]?.toIntOrNull() ?: 0
    val actionUrl = data["action_url"]
    val title = remoteMessage.notification?.title ?: "BaluHost"
    val body = remoteMessage.notification?.body ?: ""

    // Channel basierend auf notification_type wählen:
    // critical → alerts_critical, warning → alerts_warning, info → alerts_info
    val channelId = when {
        priority >= 3 -> "alerts_critical"
        priority >= 2 -> "alerts_warning"
        else -> "alerts_info"
    }

    // System-Notification anzeigen mit Deep-Link basierend auf action_url
}
```

Erstelle die drei neuen Notification Channels `alerts_critical` (HIGH), `alerts_warning` (DEFAULT), `alerts_info` (LOW) — in der Application-Klasse oder im `SyncNotificationManager`.

**WICHTIG:** Behebe den Notification-ID-Konflikt! `BaluFirebaseMessagingService` nutzt 1001/1002, `SyncNotificationManager` ebenfalls 1001-1004. Ändere die FCM-IDs auf 2001+ oder definiere einen zentralen `NotificationIds` object:

```kotlin
object NotificationIds {
    // Sync
    const val SYNC_PROGRESS = 1001
    const val SYNC_COMPLETE = 1002
    const val SYNC_ERROR = 1003
    const val SYNC_CONFLICTS = 1004

    // Connection
    const val CONNECTION = 1010

    // FCM Push
    const val DEVICE_EXPIRATION = 2001
    const val DEVICE_STATUS = 2002

    // Backend Notifications (dynamisch)
    fun forNotification(id: Int) = 3000 + id
}
```

---

### Phase 2: Presentation Layer

#### 2.1 Navigation erweitern

In `Screen.kt` neue Routes hinzufügen:

```kotlin
object Notifications : Screen("notifications")
object NotificationPreferences : Screen("notification_preferences")
```

In `NavGraph.kt` die Composables registrieren.

#### 2.2 NotificationViewModel (`presentation/ui/screens/notifications/NotificationsViewModel.kt`)

```kotlin
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val webSocketManager: NotificationWebSocketManager
) : ViewModel() {

    data class UiState(
        val notifications: List<AppNotification> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val selectedCategory: NotificationCategory? = null,
        val unreadOnly: Boolean = false,
        val totalCount: Int = 0,
        val currentPage: Int = 1,
        val hasMore: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Unread Count vom WebSocket für Badge in BottomNav/TopBar
    val unreadCount: StateFlow<Int> = webSocketManager.unreadCount

    init {
        loadNotifications()
        observeWebSocket()
    }
}
```

#### 2.3 Notifications Screen (`presentation/ui/screens/notifications/NotificationsScreen.kt`)

Design-Anforderungen (Glassmorphism, konsistent mit der restlichen App):

- **Header:** Titel "Benachrichtigungen" mit Unread-Badge, "Alle gelesen" Button
- **Filter-Chips:** Horizontal scrollbar — Alle, RAID, System, Sicherheit, Backup, Sync, VPN, SMART, Scheduler
- **Toggle:** "Nur ungelesene" Switch
- **Notification-Liste:** `LazyColumn` mit Notification-Cards
  - Jede Card: `GlassCard` mit:
    - Icon links (basierend auf Category + Type, farblich: `critical`=Red400, `warning`=Amber400, `info`=Sky400)
    - Titel + Nachricht + `time_ago`
    - Ungelesen-Indikator (kleiner farbiger Punkt links)
    - Swipe-to-dismiss oder Long-Press-Menü (Gelesen, Verwerfen, Snooze)
  - Prioritäts-Markierung: kritische Notifications haben einen farbigen linken Rand (Red400)
- **Empty State:** Illustration + "Keine Benachrichtigungen"
- **Pull-to-Refresh**
- **Infinite Scroll** für Pagination (page_size=20)

Category Icons (nutze Material Icons Extended die in der App schon drin sind):
- RAID: `Icons.Default.Storage`
- SMART: `Icons.Default.HealthAndSafety`
- Backup: `Icons.Default.Backup`
- Scheduler: `Icons.Default.Schedule`
- System: `Icons.Default.Computer`
- Security: `Icons.Default.Security`
- Sync: `Icons.Default.Sync`
- VPN: `Icons.Default.VpnKey`

#### 2.4 Notification Preferences Screen (`presentation/ui/screens/notifications/NotificationPreferencesScreen.kt`)

- **Push-Benachrichtigungen** Toggle (global)
- **In-App-Benachrichtigungen** Toggle
- **Ruhezeiten** Toggle + Start/Ende Zeitpicker (HH:MM)
- **Mindest-Priorität** Slider/Dropdown (0-3: Alle, Mittel+, Hoch+, Nur kritisch)
- **Kategorie-Einstellungen** — Expandable Sections für jede Kategorie mit Push/In-App Toggles

#### 2.5 Notification Bell Badge (Global)

Füge einen Unread-Badge zum bestehenden UI hinzu. Optionen:
- In der `BottomNavBar` oder `TopAppBar` ein Glocken-Icon mit Badge-Count
- Der Count kommt vom `NotificationWebSocketManager.unreadCount` StateFlow
- Tap navigiert zu `Screen.Notifications`

Schaue dir an, wie die bestehende Navigation in `MainScreen.kt` / `BottomNavBar.kt` aufgebaut ist und füge das Icon dort ein.

#### 2.6 Notification-Eingang in Settings verlinken

Im bestehenden Settings-Screen einen Eintrag "Benachrichtigungen" hinzufügen, der zu `Screen.NotificationPreferences` navigiert.

---

### Phase 3: WebSocket Lifecycle

#### 3.1 WebSocket starten/stoppen

Der WebSocket soll:
- **Starten** wenn der User eingeloggt ist (Token vorhanden) — z.B. in `MainActivity` nach Auth-Check oder im `DashboardViewModel`
- **Stoppen** bei Logout oder App-Destroy
- **Pausieren** optional wenn App im Hintergrund (FCM Push übernimmt dann)

Die einfachste Integration: Im `NotificationWebSocketManager` eine `connect()`/`disconnect()` Methode. In `MainActivity` oder einem dedizierten `NotificationLifecycleObserver` den Lifecycle beobachten.

#### 3.2 WebSocket + FCM Zusammenspiel

- WebSocket aktiv → In-App-Notification anzeigen (kein System-Notification nötig da App im Vordergrund)
- WebSocket inaktiv (App im Hintergrund) → FCM Push zeigt System-Notification
- Beim Öffnen der App: WebSocket reconnecten + `getUnreadCount()` aufrufen um State zu synchronisieren

---

### Phase 4: Integration & Fixes

#### 4.1 Notification-ID Kollision beheben

Erstelle `util/NotificationIds.kt` (siehe oben) und refactore `BaluFirebaseMessagingService` und `SyncNotificationManager` um diese zentralen IDs zu verwenden.

#### 4.2 Notification Channels konsolidieren

Erstelle alle Channels zentral in `BaluHostApplication.onCreate()` oder in einem `NotificationChannelManager`:
- Bestehende: `sync_progress`, `sync_complete`, `sync_error`, `sync_conflicts`, `server_connection`, `device_expiration`, `device_status`
- Neue: `alerts_critical`, `alerts_warning`, `alerts_info`

#### 4.3 Deep Links für Notifications

Wenn eine Push-Notification getippt wird, soll die App zur passenden Seite navigieren basierend auf `action_url`:
- `/raid` → `Screen.Dashboard` (oder ein zukünftiger RAID-Screen)
- `/files` → `Screen.Main` (Files Tab)
- `/sync` → `Screen.Main` (Sync Tab)
- Fallback → `Screen.Notifications`

Nutze das bestehende Pattern in `MainActivity.handleIntent()` mit `notification_type` Extra.

---

## Zusammenfassung der zu erstellenden/ändernden Dateien

### Neue Dateien:
1. `data/remote/dto/NotificationDto.kt` — alle DTOs
2. `data/remote/api/NotificationsApi.kt` — Retrofit Interface
3. `data/notification/NotificationWebSocketManager.kt` — WebSocket Client
4. `data/repository/NotificationRepositoryImpl.kt` — Repository Impl
5. `domain/model/Notification.kt` — Domain Models + Enums + Mapper
6. `domain/repository/NotificationRepository.kt` — Repository Interface
7. `domain/usecase/notification/GetNotificationsUseCase.kt`
8. `domain/usecase/notification/GetUnreadCountUseCase.kt`
9. `domain/usecase/notification/MarkNotificationReadUseCase.kt`
10. `domain/usecase/notification/MarkAllReadUseCase.kt`
11. `domain/usecase/notification/DismissNotificationUseCase.kt`
12. `domain/usecase/notification/SnoozeNotificationUseCase.kt`
13. `domain/usecase/notification/GetNotificationPreferencesUseCase.kt`
14. `domain/usecase/notification/UpdateNotificationPreferencesUseCase.kt`
15. `presentation/ui/screens/notifications/NotificationsViewModel.kt`
16. `presentation/ui/screens/notifications/NotificationsScreen.kt`
17. `presentation/ui/screens/notifications/NotificationPreferencesViewModel.kt`
18. `presentation/ui/screens/notifications/NotificationPreferencesScreen.kt`
19. `presentation/ui/components/NotificationBadge.kt` — Bell-Icon + Badge Composable
20. `util/NotificationIds.kt` — Zentrale Notification-ID Konstanten

### Zu ändernde Dateien:
1. `di/NetworkModule.kt` — `NotificationsApi` Provider hinzufügen
2. `di/RepositoryModule.kt` — `NotificationRepository` Binding hinzufügen
3. `services/BaluFirebaseMessagingService.kt` — FCM Token Sync + neuer "notification" Type Handler
4. `data/notification/SyncNotificationManager.kt` — IDs aus `NotificationIds` verwenden
5. `presentation/navigation/Screen.kt` — 2 neue Routes
6. `presentation/navigation/NavGraph.kt` — 2 neue Composables
7. `presentation/MainActivity.kt` — WebSocket Lifecycle + Deep-Link Handling erweitern
8. `presentation/ui/screens/settings/SettingsScreen.kt` — Link zu Notification-Preferences
9. `presentation/ui/components/BottomNavBar.kt` (oder TopBar) — Notification-Bell mit Badge
10. `BaluHostApplication.kt` — Neue Notification Channels erstellen

---

## Reihenfolge der Implementierung

1. **DTOs + API Interface** — Grundlage für alles
2. **Domain Models + Repository** — Datenschicht komplett
3. **NotificationWebSocketManager** — Real-time Verbindung
4. **FCM Fixes** (Token-Sync, neuer Handler, ID-Kollision) — Push-Kanal reparieren
5. **NotificationsViewModel + Screen** — Hauptseite
6. **Navigation + Badge** — Erreichbarkeit + Sichtbarkeit
7. **Preferences Screen** — Einstellungen
8. **Lifecycle Integration** — WebSocket Start/Stop, Deep Links

## Hinweise

- Schreibe KEINEN Code der nicht direkt benötigt wird. Kein Over-Engineering.
- Nutze `@SerializedName` nur wenn der JSON-Key vom Kotlin-Property-Namen abweicht (hier nicht nötig da Backend snake_case liefert und Gson mit `setFieldNamingPolicy` konfiguriert ist — **prüfe ob `GsonConverterFactory` in `NetworkModule` einen FieldNamingPolicy hat**, falls nicht, nutze `@SerializedName` für snake_case Felder).
- Teste mit dem Dev-Server unter `http://192.168.178.21:8000/api/`.
- Der WebSocket benötigt einen separaten OkHttpClient ohne die Auth-Interceptor-Chain (da Auth über Query-Parameter läuft). Erstelle in `NetworkModule` einen zweiten `@Named("websocket") OkHttpClient` nur mit Timeouts, ohne Interceptors.
- Alle Texte auf Deutsch.
