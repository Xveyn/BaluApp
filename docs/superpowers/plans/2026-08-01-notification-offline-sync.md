# Notifications offline persistieren und zweiseitig abgleichen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Notifications bleiben ohne Serververbindung sichtbar und benutzbar, gespeist aus REST, WebSocket und FCM, und offline getroffene Entscheidungen gleichen sich bei Verbindung mit dem Server ab.

**Architecture:** Eine neue Room-Tabelle hält den Bestand pro Konto. Die lokale Absicht wird nicht als Operationslog geführt, sondern als vier nullable Zeitstempel direkt an der Zeile (`localReadAt`, `localTrashedAt`, `localRestoredAt`, `localSnoozedUntil`) — idempotent, ohne Reihenfolgeproblem, absturzsicher. Die Zusammenführungsregeln liegen in einer reinen Kotlin-Klasse ohne Room-, Android- und Retrofit-Bezug und sind damit vollständig unit-testbar; das Repository wird cache-first und liefert `Flow`s aus dem DAO.

**Tech Stack:** Kotlin, Room, Jetpack Compose (Material 3), Hilt, Retrofit/Gson, Firebase Messaging, JUnit4 + MockK + Turbine + `kotlinx-coroutines-test`.

## Global Constraints

- **Der Server wird nicht verändert.** Dieses Repo konsumiert den Kontrakt nur. Alle benötigten Endpunkte existieren bereits.
- **Übergangsregel bis [BaluHost #504](https://github.com/Xveyn/BaluHost/issues/504): Beim Papierkorb-Zustand gewinnt der Server.** Ein verlorenes Wegklicken ist harmlos; eine wieder auftauchende gelöschte Notification ist sichtbar falsch.
- **`isRead` wird per ODER zusammengeführt** — gelesen auf einer Seite ⇒ gelesen. Es gibt serverseitig kein „ungelesen machen", das Feld ist monoton. **Kein LWW, kein Zeitstempelvergleich.**
- **Eigentümer-Schlüssel ist die id des angemeldeten Kontos** (`PreferencesManager.getUserId()`), **nicht** das `user_id`-Feld der Notification — letzteres ist bei Admin-Broadcasts `null`.
- **Kategorien sind ein offener Satz** (Kern-Kategorien, `lifecycle`, Plugin-Namen). Der rohe String wird gespeichert und gefiltert; `NotificationCategory` dient nur der Darstellung.
- **Obergrenze 500 Zeilen pro Konto**, älteste nach `createdAt` zuerst. Zeilen mit noch nicht gepushter lokaler Absicht sind von der Verdrängung **ausgenommen**.
- Nutzertexte sind deutsche String-Literale direkt im Code; es gibt kein i18n-Framework.
- Code-Kommentare in diesem Projekt sind **englisch**. Deutsche Kommentare nicht neu einführen.
- `Result` ist in `data/repository/NotificationRepositoryImpl` und `domain/repository/NotificationRepository` der **Kotlin-Standard-`kotlin.Result`**, nicht `com.baluhost.android.util.Result`. Das ist bestehende Konvention dieser beiden Dateien (siehe `data/repository/CLAUDE.md`) und wird beibehalten. Die Use Cases konvertieren per `fold` nach `util.Result`.
- Testlauf ausschließlich mit `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`, danach XML-Zählung laut `app/src/test/CLAUDE.md`. Ein blanker Testlauf kann `BUILD SUCCESSFUL` aus dem Cache melden, ohne einen Test auszuführen.
- Nach jeder Task mit DI- oder Room-Bezug zusätzlich `./gradlew assembleDebug` — ein fehlendes Hilt-Binding kompiliert sauber und fällt erst beim Zusammensetzen des Graphen auf.
- Windows/PowerShell: Befehle **nie** mit `&&` verketten, sondern `;` bzw. `if ($?) { … }`.

---

### Task 1: Kontrakt geradeziehen (`deleted_at` statt `is_dismissed`)

Der Server liefert `deleted_at` (nullable Zeitstempel), kein `is_dismissed`. Gson lässt das falsch benannte Feld still auf `false` — die App kann den Papierkorb-Zustand heute gar nicht darstellen. Ohne diese Korrektur ist alles Weitere sinnlos.

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/remote/dto/NotificationDto.kt`
- Modify: `app/src/main/java/com/baluhost/android/domain/model/Notification.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/remote/api/NotificationsApi.kt`
- Test: `app/src/test/java/com/baluhost/android/domain/model/NotificationMappingTest.kt` (neu)

**Interfaces:**
- Produces: `NotificationDto.deletedAt: String?`, `NotificationPreferencesDto.trashRetentionDays: Int`, `AppNotification.deletedAt: String?` (ersetzt `isDismissed`). Alle Folge-Tasks nutzen diese Namen.

- [ ] **Step 1: Failing Test schreiben**

Neue Datei `app/src/test/java/com/baluhost/android/domain/model/NotificationMappingTest.kt`:

```kotlin
package com.baluhost.android.domain.model

import com.baluhost.android.data.remote.dto.NotificationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationMappingTest {

    private fun dto(
        deletedAt: String? = null,
        category: String = "raid"
    ) = NotificationDto(
        id = 7,
        createdAt = "2026-08-01T10:00:00Z",
        userId = null,
        notificationType = "warning",
        category = category,
        title = "RAID degraded",
        message = "Disk 2 missing",
        actionUrl = "/storage",
        isRead = false,
        deletedAt = deletedAt,
        priority = 2,
        metadata = null,
        timeAgo = "5m ago",
        snoozedUntil = null
    )

    @Test
    fun `an active notification maps to a null deletedAt`() {
        assertNull(dto().toDomain().deletedAt)
    }

    @Test
    fun `a trashed notification keeps the server timestamp`() {
        val mapped = dto(deletedAt = "2026-08-01T11:00:00Z").toDomain()

        assertEquals("2026-08-01T11:00:00Z", mapped.deletedAt)
    }

    @Test
    fun `the raw category string survives the mapping`() {
        // The server's category set is open: core categories, "lifecycle", and
        // plugin names. The enum is display-only and must not be the carrier.
        val mapped = dto(category = "steam_gaming").toDomain()

        assertEquals("steam_gaming", mapped.rawCategory)
        assertEquals(NotificationCategory.SYSTEM, mapped.category)
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*NotificationMappingTest"`
Expected: Kompilierfehler — `NotificationDto` kennt kein `deletedAt`, `AppNotification` kein `deletedAt`/`rawCategory`.

- [ ] **Step 3: DTO korrigieren**

In `NotificationDto.kt` das Feld ersetzen:

```kotlin
    @SerializedName("deleted_at")
    val deletedAt: String? = null,
```

(ersetzt `@SerializedName("is_dismissed") val isDismissed: Boolean`)

In `NotificationPreferencesDto` ergänzen:

```kotlin
    @SerializedName("trash_retention_days")
    val trashRetentionDays: Int = 7,
```

In `NotificationPreferencesUpdate` ergänzen:

```kotlin
    @SerializedName("trash_retention_days")
    val trashRetentionDays: Int? = null,
```

- [ ] **Step 4: Domain-Modell korrigieren**

In `Notification.kt` das Feld `isDismissed: Boolean` durch `deletedAt: String?` ersetzen und `rawCategory: String` ergänzen; im `toDomain()` entsprechend:

```kotlin
data class AppNotification(
    val id: Int,
    val createdAt: String,
    val userId: Int?,
    val type: NotificationType,
    /** Raw server category. Open set: core categories, "lifecycle", plugin names. */
    val rawCategory: String,
    /** Display-only mapping of [rawCategory]; unknown values fall back to SYSTEM. */
    val category: NotificationCategory,
    val title: String,
    val message: String,
    val actionUrl: String?,
    val isRead: Boolean,
    /** Server timestamp of the move to trash; null means active. */
    val deletedAt: String?,
    val priority: Int,
    val metadata: Map<String, Any>?,
    val timeAgo: String?,
    val snoozedUntil: String?
)

fun NotificationDto.toDomain() = AppNotification(
    id = id,
    createdAt = createdAt,
    userId = userId,
    type = NotificationType.entries.find {
        it.name.equals(notificationType, ignoreCase = true)
    } ?: NotificationType.INFO,
    rawCategory = category,
    category = NotificationCategory.entries.find {
        it.name.equals(category, ignoreCase = true)
    } ?: NotificationCategory.SYSTEM,
    title = title,
    message = message,
    actionUrl = actionUrl,
    isRead = isRead,
    deletedAt = deletedAt,
    priority = priority,
    metadata = metadata,
    timeAgo = timeAgo,
    snoozedUntil = snoozedUntil
)
```

- [ ] **Step 5: Toten Query-Parameter entfernen**

In `NotificationsApi.kt` die Zeile `@Query("include_dismissed") includeDismissed: Boolean = false,` **löschen**. Der Server kennt diesen Parameter nicht (`routes/notifications.py`), FastAPI ignoriert ihn stillschweigend.

- [ ] **Step 6: Tests laufen lassen und grün bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS. XML-Zählung > 0 bestätigen.

Falls andere Dateien wegen des entfernten `isDismissed` nicht mehr kompilieren: es gibt zum Zeitpunkt dieses Plans **keine lesende Verwendung** von `AppNotification.isDismissed` im Projekt (nur Deklaration und Mapping). Treffer bei `VpnStatusBanner`/`FilesScreen`/`DashboardScreen` betreffen einen gleichnamigen, unabhängigen Parameter und dürfen **nicht** angefasst werden.

- [ ] **Step 7: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/data/remote" "app/src/main/java/com/baluhost/android/domain/model/Notification.kt" "app/src/test/java/com/baluhost/android/domain/model/NotificationMappingTest.kt"
git commit -m "fix(notifications): read deleted_at instead of the non-existent is_dismissed"
```

---

### Task 2: Room-Tabelle, DAO und nicht-destruktive Migration

**Files:**
- Create: `app/src/main/java/com/baluhost/android/data/local/database/entities/NotificationEntity.kt`
- Create: `app/src/main/java/com/baluhost/android/data/local/database/dao/NotificationDao.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/local/database/converters/Converters.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/local/database/BaluHostDatabase.kt`
- Modify: `app/src/main/java/com/baluhost/android/di/DatabaseModule.kt`
- Test: `app/src/test/java/com/baluhost/android/data/local/database/converters/ConvertersTest.kt` (neu)

**Interfaces:**
- Produces: `NotificationEntity` (Tabelle `notifications`, zusammengesetzter PK `(ownerUserId, id)`), `NotificationDao`, `Converters.fromStringAnyMap`/`toStringAnyMap`, `MIGRATION_4_5`. Alle Folge-Tasks nutzen diese Namen.

**Wichtig:** `DatabaseModule.kt:60` ruft `.fallbackToDestructiveMigration()`. Ein Versionssprung ohne echte Migration würde die **gesamte** Datenbank löschen — inklusive `PendingOperationEntity`, also der noch nicht ausgeführten Offline-Operationen echter Nutzer. Deshalb wird hier eine richtige `Migration` geschrieben und registriert. Der Fallback bleibt für andere Pfade stehen; ihn zu entfernen ist nicht Teil dieses Plans.

- [ ] **Step 1: Failing Test für den Converter schreiben**

Neue Datei `app/src/test/java/com/baluhost/android/data/local/database/converters/ConvertersTest.kt`:

```kotlin
package com.baluhost.android.data.local.database.converters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `a null metadata map round-trips as null`() {
        assertNull(converters.fromStringAnyMap(null))
        assertNull(converters.toStringAnyMap(null))
    }

    @Test
    fun `a metadata map survives the round trip`() {
        val original = mapOf("disk" to "sda2", "count" to 3.0)

        val restored = converters.toStringAnyMap(converters.fromStringAnyMap(original))

        assertEquals(original, restored)
    }

    @Test
    fun `unparseable stored metadata yields null instead of throwing`() {
        // A row written by an older build, or a truncated value. Losing the
        // metadata is acceptable; crashing the query is not.
        assertNull(converters.toStringAnyMap("{not json"))
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*ConvertersTest"`
Expected: Kompilierfehler — `fromStringAnyMap` existiert nicht.

- [ ] **Step 3: Converter ergänzen**

`Converters.kt` um Gson-basierte Map-Konvertierung erweitern (Import `com.google.gson.Gson`, `com.google.gson.reflect.TypeToken`):

```kotlin
    private val gson = Gson()

    @TypeConverter
    fun fromStringAnyMap(value: Map<String, Any>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toStringAnyMap(value: String?): Map<String, Any>? {
        if (value == null) return null
        return try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            gson.fromJson(value, type)
        } catch (_: Exception) {
            // A row written by an older build, or a truncated value. Losing the
            // metadata beats failing the whole query.
            null
        }
    }
```

- [ ] **Step 4: Entity anlegen**

Neue Datei `NotificationEntity.kt`:

```kotlin
package com.baluhost.android.data.local.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.time.Instant

/**
 * A notification as this device knows it, scoped to the account that owns it.
 *
 * The primary key is (ownerUserId, id): [ownerUserId] is the signed-in account
 * from PreferencesManager, NOT the notification's own [userId] — that one is
 * null for broadcasts to admins and cannot identify an owner.
 *
 * The four local* timestamps ARE the outbox. There is no separate operation
 * log: notification state is a small idempotent set of fields, so recording the
 * intent on the row itself avoids ordering and duplicate problems entirely, and
 * survives process death for free.
 */
@Entity(tableName = "notifications", primaryKeys = ["owner_user_id", "id"])
data class NotificationEntity(
    @ColumnInfo(name = "owner_user_id")
    val ownerUserId: Int,

    @ColumnInfo(name = "id")
    val id: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    /** The notification's own target user; null means a broadcast to admins. */
    @ColumnInfo(name = "user_id")
    val userId: Int? = null,

    @ColumnInfo(name = "notification_type")
    val notificationType: String,

    /** Raw server category. Open set: core categories, "lifecycle", plugin names. */
    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "message")
    val message: String,

    @ColumnInfo(name = "action_url")
    val actionUrl: String? = null,

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,

    /** Server timestamp of the move to trash; null means active. */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant? = null,

    @ColumnInfo(name = "priority")
    val priority: Int = 0,

    @ColumnInfo(name = "metadata")
    val metadata: Map<String, Any>? = null,

    @ColumnInfo(name = "snoozed_until")
    val snoozedUntil: Instant? = null,

    @ColumnInfo(name = "local_read_at")
    val localReadAt: Instant? = null,

    @ColumnInfo(name = "local_trashed_at")
    val localTrashedAt: Instant? = null,

    @ColumnInfo(name = "local_restored_at")
    val localRestoredAt: Instant? = null,

    @ColumnInfo(name = "local_snoozed_until")
    val localSnoozedUntil: Instant? = null,

    /** REST, WEBSOCKET or FCM — where this row's content last came from. */
    @ColumnInfo(name = "source")
    val source: String,

    /** True while the row was built from an FCM payload and lacks server fields. */
    @ColumnInfo(name = "is_partial")
    val isPartial: Boolean = false
) {
    /** True while any local decision still has to reach the server. */
    val hasPendingIntent: Boolean
        get() = localReadAt != null || localTrashedAt != null ||
            localRestoredAt != null || localSnoozedUntil != null
}
```

- [ ] **Step 5: DAO anlegen**

Neue Datei `NotificationDao.kt`:

```kotlin
package com.baluhost.android.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.baluhost.android.data.local.database.entities.NotificationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Thin by design: every merge decision lives in NotificationMerge, which is
 * plain Kotlin and therefore unit-testable. This repo has no androidTest source
 * set, so anything expressed as a Room query is effectively untested.
 */
@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notifications: List<NotificationEntity>)

    @Update
    suspend fun update(notification: NotificationEntity)

    @Query(
        """
        SELECT * FROM notifications
        WHERE owner_user_id = :ownerUserId
          AND ((:trashed = 0 AND deleted_at IS NULL) OR (:trashed = 1 AND deleted_at IS NOT NULL))
        ORDER BY created_at DESC
        """
    )
    fun observe(ownerUserId: Int, trashed: Boolean): Flow<List<NotificationEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM notifications
        WHERE owner_user_id = :ownerUserId AND is_read = 0 AND deleted_at IS NULL
        """
    )
    fun observeUnreadCount(ownerUserId: Int): Flow<Int>

    @Query("SELECT * FROM notifications WHERE owner_user_id = :ownerUserId AND id = :id")
    suspend fun find(ownerUserId: Int, id: Int): NotificationEntity?

    @Query("SELECT * FROM notifications WHERE owner_user_id = :ownerUserId")
    suspend fun getAll(ownerUserId: Int): List<NotificationEntity>

    @Query(
        """
        SELECT * FROM notifications
        WHERE owner_user_id = :ownerUserId
          AND (local_read_at IS NOT NULL OR local_trashed_at IS NOT NULL
               OR local_restored_at IS NOT NULL OR local_snoozed_until IS NOT NULL)
        """
    )
    suspend fun getWithPendingIntent(ownerUserId: Int): List<NotificationEntity>

    @Query("DELETE FROM notifications WHERE owner_user_id = :ownerUserId AND id = :id")
    suspend fun delete(ownerUserId: Int, id: Int)

    @Query("DELETE FROM notifications WHERE owner_user_id = :ownerUserId AND id IN (:ids)")
    suspend fun deleteAllById(ownerUserId: Int, ids: List<Int>)

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()

    /**
     * Rows eligible for eviction, oldest first: never evict a row whose local
     * decision has not reached the server yet.
     */
    @Query(
        """
        SELECT * FROM notifications
        WHERE owner_user_id = :ownerUserId
          AND local_read_at IS NULL AND local_trashed_at IS NULL
          AND local_restored_at IS NULL AND local_snoozed_until IS NULL
        ORDER BY created_at ASC
        """
    )
    suspend fun getEvictable(ownerUserId: Int): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM notifications WHERE owner_user_id = :ownerUserId")
    suspend fun count(ownerUserId: Int): Int
}
```

- [ ] **Step 6: Datenbank auf Version 5 heben, mit echter Migration**

`BaluHostDatabase.kt`: `NotificationEntity::class` in die `entities`-Liste, `version = 5`, `abstract fun notificationDao(): NotificationDao`, und im selben File die Migration:

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // A real migration, not the destructive fallback: version 4 holds
        // PendingOperationEntity, i.e. offline operations that have not run
        // yet. Dropping the database would silently discard them.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `notifications` (
                `owner_user_id` INTEGER NOT NULL,
                `id` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `user_id` INTEGER,
                `notification_type` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `message` TEXT NOT NULL,
                `action_url` TEXT,
                `is_read` INTEGER NOT NULL,
                `deleted_at` INTEGER,
                `priority` INTEGER NOT NULL,
                `metadata` TEXT,
                `snoozed_until` INTEGER,
                `local_read_at` INTEGER,
                `local_trashed_at` INTEGER,
                `local_restored_at` INTEGER,
                `local_snoozed_until` INTEGER,
                `source` TEXT NOT NULL,
                `is_partial` INTEGER NOT NULL,
                PRIMARY KEY(`owner_user_id`, `id`)
            )
            """.trimIndent()
        )
    }
}
```

Imports: `androidx.room.migration.Migration`, `androidx.sqlite.db.SupportSQLiteDatabase`.

- [ ] **Step 7: Migration und DAO in Hilt registrieren**

In `DatabaseModule.kt` den Builder um `.addMigrations(MIGRATION_4_5)` ergänzen (vor `.fallbackToDestructiveMigration()`), und den DAO bereitstellen:

```kotlin
    @Provides
    @Singleton
    fun provideNotificationDao(database: BaluHostDatabase) = database.notificationDao()
```

- [ ] **Step 8: Tests und Build prüfen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS, XML-Zählung > 0.

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. Room erzeugt seinen Code erst hier — Schemafehler in Entity oder DAO-Query fallen **nur** an dieser Stelle auf, nicht im Unit-Test.

- [ ] **Step 9: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/data/local/database" "app/src/main/java/com/baluhost/android/di/DatabaseModule.kt" "app/src/test/java/com/baluhost/android/data/local/database"
git commit -m "feat(notifications): add the local notification table with a real 4->5 migration"
```

---

### Task 3: Die Zusammenführungsregeln

Das Herzstück, und der einzige Teil mit echter Logik. Reine Kotlin-Klasse, kein Room, kein Android, kein Retrofit — damit vollständig unter reinem JUnit testbar.

**Files:**
- Create: `app/src/main/java/com/baluhost/android/domain/service/NotificationMerge.kt`
- Test: `app/src/test/java/com/baluhost/android/domain/service/NotificationMergeTest.kt` (neu)

**Interfaces:**
- Produces: `NotificationMerge.merge(local: LocalState, server: ServerState, now: Instant): Merged` mit `Merged(state: LocalState, pushes: List<Push>)` und `Push` als `MarkRead`/`Dismiss`/`Restore`/`Snooze(hours: Int)`. Task 4 ruft das auf.

- [ ] **Step 1: Failing Tests schreiben**

Neue Datei `app/src/test/java/com/baluhost/android/domain/service/NotificationMergeTest.kt`:

```kotlin
package com.baluhost.android.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NotificationMergeTest {

    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")

    private fun local(
        isRead: Boolean = false,
        deletedAt: Instant? = null,
        snoozedUntil: Instant? = null,
        localReadAt: Instant? = null,
        localTrashedAt: Instant? = null,
        localRestoredAt: Instant? = null,
        localSnoozedUntil: Instant? = null
    ) = NotificationMerge.LocalState(
        isRead, deletedAt, snoozedUntil,
        localReadAt, localTrashedAt, localRestoredAt, localSnoozedUntil
    )

    private fun server(
        isRead: Boolean = false,
        deletedAt: Instant? = null,
        snoozedUntil: Instant? = null
    ) = NotificationMerge.ServerState(isRead, deletedAt, snoozedUntil)

    // --- isRead: monotone OR ---

    @Test
    fun `read on the server wins over unread locally`() {
        val merged = NotificationMerge.merge(local(isRead = false), server(isRead = true), now)

        assertTrue(merged.state.isRead)
        assertTrue(merged.pushes.isEmpty())
    }

    @Test
    fun `read locally is pushed while the server still says unread`() {
        val merged = NotificationMerge.merge(
            local(localReadAt = now.minusSeconds(60)), server(isRead = false), now
        )

        assertTrue(merged.state.isRead)
        assertTrue(merged.pushes.contains(NotificationMerge.Push.MarkRead))
    }

    @Test
    fun `a confirmed read intent is cleared`() {
        val merged = NotificationMerge.merge(
            local(localReadAt = now.minusSeconds(60)), server(isRead = true), now
        )

        assertNull(merged.state.localReadAt)
        assertTrue(merged.pushes.isEmpty())
    }

    // --- trash: the server wins (interim rule until BaluHost#504) ---

    @Test
    fun `the server restore wins over a local dismiss`() {
        // The interim rule. Losing a dismiss is harmless - the user taps again.
        // A deleted notification reappearing is visibly wrong.
        val merged = NotificationMerge.merge(
            local(deletedAt = now.minusSeconds(600), localTrashedAt = now.minusSeconds(600)),
            server(deletedAt = null),
            now
        )

        assertNull(merged.state.deletedAt)
        assertTrue(merged.pushes.contains(NotificationMerge.Push.Dismiss))
    }

    @Test
    fun `a confirmed dismiss intent is cleared`() {
        val trashedAt = now.minusSeconds(600)
        val merged = NotificationMerge.merge(
            local(localTrashedAt = trashedAt), server(deletedAt = trashedAt), now
        )

        assertNull(merged.state.localTrashedAt)
        assertEquals(trashedAt, merged.state.deletedAt)
        assertTrue(merged.pushes.isEmpty())
    }

    @Test
    fun `a local restore is pushed while the server still has it trashed`() {
        val merged = NotificationMerge.merge(
            local(localRestoredAt = now.minusSeconds(30)),
            server(deletedAt = now.minusSeconds(600)),
            now
        )

        assertTrue(merged.pushes.contains(NotificationMerge.Push.Restore))
    }

    @Test
    fun `dismiss and restore both pending resolves to the later one`() {
        // Offline the user dismissed, then changed their mind and restored.
        val merged = NotificationMerge.merge(
            local(
                localTrashedAt = now.minusSeconds(600),
                localRestoredAt = now.minusSeconds(60)
            ),
            server(deletedAt = now.minusSeconds(900)),
            now
        )

        assertTrue(merged.pushes.contains(NotificationMerge.Push.Restore))
        assertFalse(merged.pushes.contains(NotificationMerge.Push.Dismiss))
    }

    // --- snooze ---

    @Test
    fun `a future local snooze is pushed as remaining whole hours`() {
        val merged = NotificationMerge.merge(
            local(localSnoozedUntil = now.plusSeconds(3600 * 2 + 5)), server(), now
        )

        assertEquals(NotificationMerge.Push.Snooze(3), merged.pushes.single())
    }

    @Test
    fun `an expired local snooze is dropped instead of pushed`() {
        val merged = NotificationMerge.merge(
            local(localSnoozedUntil = now.minusSeconds(60)), server(), now
        )

        assertTrue(merged.pushes.isEmpty())
        assertNull(merged.state.localSnoozedUntil)
    }

    @Test
    fun `a snooze shorter than an hour is pushed as one hour`() {
        // The server accepts 1..168 only.
        val merged = NotificationMerge.merge(
            local(localSnoozedUntil = now.plusSeconds(120)), server(), now
        )

        assertEquals(NotificationMerge.Push.Snooze(1), merged.pushes.single())
    }

    @Test
    fun `the server snooze wins when nothing is pending locally`() {
        val until = now.plusSeconds(7200)
        val merged = NotificationMerge.merge(local(), server(snoozedUntil = until), now)

        assertEquals(until, merged.state.snoozedUntil)
        assertTrue(merged.pushes.isEmpty())
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*NotificationMergeTest"`
Expected: Kompilierfehler — `NotificationMerge` existiert nicht.

- [ ] **Step 3: Implementieren**

Neue Datei `app/src/main/java/com/baluhost/android/domain/service/NotificationMerge.kt`:

```kotlin
package com.baluhost.android.domain.service

import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/**
 * Reconciles this device's view of a notification with the server's.
 *
 * Deliberately free of Room, Android and Retrofit: this repo has no
 * androidTest source set, so logic only counts as tested when it can run under
 * plain JUnit. Everything around this object stays mechanical.
 */
object NotificationMerge {

    private const val MIN_SNOOZE_HOURS = 1
    private const val MAX_SNOOZE_HOURS = 168

    data class LocalState(
        val isRead: Boolean,
        val deletedAt: Instant?,
        val snoozedUntil: Instant?,
        val localReadAt: Instant?,
        val localTrashedAt: Instant?,
        val localRestoredAt: Instant?,
        val localSnoozedUntil: Instant?
    )

    data class ServerState(
        val isRead: Boolean,
        val deletedAt: Instant?,
        val snoozedUntil: Instant?
    )

    sealed interface Push {
        data object MarkRead : Push
        data object Dismiss : Push
        data object Restore : Push
        data class Snooze(val hours: Int) : Push
    }

    data class Merged(val state: LocalState, val pushes: List<Push>)

    fun merge(local: LocalState, server: ServerState, now: Instant): Merged {
        val pushes = mutableListOf<Push>()

        // isRead is monotone: the server offers /read and /read-all but nothing
        // that marks something unread again. An OR needs no timestamps and
        // cannot conflict.
        val mergedRead = local.isRead || server.isRead || local.localReadAt != null
        val readIntent = if (server.isRead) null else local.localReadAt
        if (readIntent != null) pushes.add(Push.MarkRead)

        // Trash: the server wins (interim rule until BaluHost#504 adds a write
        // timestamp for restore). The local intent is still pushed, so the
        // user's decision is not silently dropped - it just does not override
        // what the server currently says.
        val effectiveTrashIntent = latestOf(local.localTrashedAt, local.localRestoredAt)
        var trashedIntent: Instant? = null
        var restoredIntent: Instant? = null
        when {
            effectiveTrashIntent == null -> Unit
            effectiveTrashIntent == local.localRestoredAt -> {
                if (server.deletedAt != null) {
                    pushes.add(Push.Restore)
                    restoredIntent = local.localRestoredAt
                }
            }
            else -> {
                if (server.deletedAt == null) {
                    pushes.add(Push.Dismiss)
                    trashedIntent = local.localTrashedAt
                }
            }
        }

        // Snooze is stored absolute; the endpoint takes whole hours from now.
        val snoozeIntent = local.localSnoozedUntil?.takeIf { it.isAfter(now) }
        if (snoozeIntent != null && snoozeIntent != server.snoozedUntil) {
            pushes.add(Push.Snooze(hoursUntil(snoozeIntent, now)))
        }

        return Merged(
            state = LocalState(
                isRead = mergedRead,
                deletedAt = server.deletedAt,
                snoozedUntil = server.snoozedUntil,
                localReadAt = readIntent,
                localTrashedAt = trashedIntent,
                localRestoredAt = restoredIntent,
                localSnoozedUntil = snoozeIntent
            ),
            pushes = pushes
        )
    }

    private fun latestOf(a: Instant?, b: Instant?): Instant? = when {
        a == null -> b
        b == null -> a
        b.isAfter(a) -> b
        else -> a
    }

    private fun hoursUntil(target: Instant, now: Instant): Int {
        val minutes = Duration.between(now, target).toMinutes()
        val hours = ceil(minutes / 60.0).toInt()
        return hours.coerceIn(MIN_SNOOZE_HOURS, MAX_SNOOZE_HOURS)
    }
}
```

- [ ] **Step 4: Tests laufen lassen und grün bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS, XML-Zählung > 0.

- [ ] **Step 5: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/domain/service/NotificationMerge.kt" "app/src/test/java/com/baluhost/android/domain/service/NotificationMergeTest.kt"
git commit -m "feat(notifications): add the local/server merge rules as pure Kotlin"
```

---

### Task 4: Repository wird cache-first

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/domain/repository/NotificationRepository.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/repository/NotificationRepositoryImpl.kt`
- Modify: `app/src/main/java/com/baluhost/android/data/remote/api/NotificationsApi.kt`
- Create: `app/src/main/java/com/baluhost/android/data/repository/NotificationEntityMapper.kt`
- Test: `app/src/test/java/com/baluhost/android/data/repository/NotificationRepositoryImplTest.kt` (neu)

**Interfaces:**
- Consumes: `NotificationDao` (Task 2), `NotificationMerge` (Task 3), `AppNotification.deletedAt`/`rawCategory` (Task 1).
- Produces: auf `NotificationRepository` neu — `observeNotifications(ownerUserId: Int, trashed: Boolean): Flow<List<AppNotification>>`, `observeUnreadCount(ownerUserId: Int): Flow<Int>`, `suspend fun sync(ownerUserId: Int): Result<Unit>`, `suspend fun markReadLocally(ownerUserId: Int, id: Int)`, `dismissLocally`, `restoreLocally`, `snoozeLocally(ownerUserId: Int, id: Int, hours: Int)`, `suspend fun deletePermanently(ownerUserId: Int, id: Int): Result<Unit>`, `suspend fun emptyTrash(ownerUserId: Int): Result<Unit>`, `suspend fun upsertFromPush(entity: NotificationEntity)`, `suspend fun clearAll()`. Tasks 5–10 nutzen diese.

- [ ] **Step 1: Fehlende Endpunkte in der API ergänzen**

In `NotificationsApi.kt`:

```kotlin
    @GET("notifications/trash")
    suspend fun getTrash(
        @Query("category") category: String? = null,
        @Query("notification_type") notificationType: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50
    ): NotificationListResponse

    @POST("notifications/{id}/restore")
    suspend fun restore(@Path("id") id: Int): NotificationDto

    @POST("notifications/dismiss-all")
    suspend fun dismissAll(): MarkReadResponse

    @DELETE("notifications/{id}")
    suspend fun deletePermanently(@Path("id") id: Int)

    @DELETE("notifications/trash")
    suspend fun emptyTrash()
```

- [ ] **Step 2: Failing Tests schreiben**

Neue Datei `app/src/test/java/com/baluhost/android/data/repository/NotificationRepositoryImplTest.kt`:

```kotlin
package com.baluhost.android.data.repository

import com.baluhost.android.data.local.database.dao.NotificationDao
import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.remote.api.NotificationsApi
import com.baluhost.android.data.remote.dto.NotificationDto
import com.baluhost.android.data.remote.dto.NotificationListResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class NotificationRepositoryImplTest {

    private lateinit var api: NotificationsApi
    private lateinit var dao: NotificationDao
    private lateinit var repository: NotificationRepositoryImpl

    private val owner = 3

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        repository = NotificationRepositoryImpl(api, dao)
    }

    private fun dto(id: Int, isRead: Boolean = false, deletedAt: String? = null) = NotificationDto(
        id = id,
        createdAt = "2026-08-01T10:00:00Z",
        userId = null,
        notificationType = "info",
        category = "system",
        title = "Title $id",
        message = "Message $id",
        actionUrl = null,
        isRead = isRead,
        deletedAt = deletedAt,
        priority = 0,
        metadata = null,
        timeAgo = null,
        snoozedUntil = null
    )

    private fun listOfDto(vararg items: NotificationDto) = NotificationListResponse(
        notifications = items.toList(), total = items.size, unreadCount = 0, page = 1, pageSize = 50
    )

    private fun entity(
        id: Int,
        isRead: Boolean = false,
        localReadAt: Instant? = null,
        localTrashedAt: Instant? = null
    ) = NotificationEntity(
        ownerUserId = owner,
        id = id,
        createdAt = Instant.parse("2026-08-01T10:00:00Z"),
        notificationType = "info",
        category = "system",
        title = "Title $id",
        message = "Message $id",
        isRead = isRead,
        localReadAt = localReadAt,
        localTrashedAt = localTrashedAt,
        source = "REST"
    )

    @Test
    fun `sync pushes a local read intent the server does not know yet`() = runTest {
        coEvery { api.getNotifications(any(), any(), any(), any(), any(), any()) } returns
            listOfDto(dto(id = 1, isRead = false))
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(entity(id = 1, localReadAt = Instant.now()))

        repository.sync(owner)

        coVerify(exactly = 1) { api.markAsRead(1) }
    }

    @Test
    fun `sync does not push a read intent the server already confirmed`() = runTest {
        coEvery { api.getNotifications(any(), any(), any(), any(), any(), any()) } returns
            listOfDto(dto(id = 1, isRead = true))
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(entity(id = 1, localReadAt = Instant.now()))

        repository.sync(owner)

        coVerify(exactly = 0) { api.markAsRead(any()) }
    }

    @Test
    fun `a server row replaces a partial FCM row`() = runTest {
        coEvery { api.getNotifications(any(), any(), any(), any(), any(), any()) } returns
            listOfDto(dto(id = 9))
        coEvery { api.getTrash(any(), any(), any(), any()) } returns listOfDto()
        coEvery { dao.getAll(owner) } returns listOf(
            entity(id = 9).copy(source = "FCM", isPartial = true, title = "from push")
        )
        val stored = slot<List<NotificationEntity>>()
        coEvery { dao.upsertAll(capture(stored)) } returns Unit

        repository.sync(owner)

        val row = stored.captured.single { it.id == 9 }
        assertEquals("Title 9", row.title)
        assertEquals("REST", row.source)
        assertTrue(!row.isPartial)
    }

    @Test
    fun `a failing server call leaves the cache untouched and reports failure`() = runTest {
        coEvery { api.getNotifications(any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("offline")

        val result = repository.sync(owner)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }

    @Test
    fun `eviction keeps rows whose local decision has not been pushed`() = runTest {
        coEvery { dao.count(owner) } returns 502
        coEvery { dao.getEvictable(owner) } returns listOf(entity(id = 1), entity(id = 2))
        val evicted = slot<List<Int>>()
        coEvery { dao.deleteAllById(owner, capture(evicted)) } returns Unit

        repository.evictOverflow(owner)

        assertEquals(listOf(1, 2), evicted.captured)
    }

    @Test
    fun `markReadLocally records the intent without calling the server`() = runTest {
        coEvery { dao.find(owner, 4) } returns entity(id = 4)
        val updated = slot<NotificationEntity>()
        coEvery { dao.update(capture(updated)) } returns Unit

        repository.markReadLocally(owner, 4)

        assertTrue(updated.captured.isRead)
        assertTrue(updated.captured.localReadAt != null)
        coVerify(exactly = 0) { api.markAsRead(any()) }
    }

    @Test
    fun `markReadLocally on an unknown id does nothing rather than crashing`() = runTest {
        coEvery { dao.find(owner, 99) } returns null

        repository.markReadLocally(owner, 99)

        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun `a permanent delete removes the row locally too`() = runTest {
        val result = repository.deletePermanently(owner, 5)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { api.deletePermanently(5) }
        coVerify(exactly = 1) { dao.delete(owner, 5) }
    }

    @Test
    fun `a failed permanent delete keeps the row`() = runTest {
        coEvery { api.deletePermanently(5) } throws java.io.IOException("offline")

        val result = repository.deletePermanently(owner, 5)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dao.delete(owner, 5) }
    }
}
```

- [ ] **Step 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*NotificationRepositoryImplTest"`
Expected: Kompilierfehler — `NotificationRepositoryImpl` nimmt noch keinen DAO und kennt `sync` nicht.

- [ ] **Step 4: Mapper anlegen**

Neue Datei `app/src/main/java/com/baluhost/android/data/repository/NotificationEntityMapper.kt` mit `NotificationDto.toEntity(ownerUserId: Int, source: String): NotificationEntity`, `NotificationEntity.toDomain(): AppNotification` und `NotificationEntity.toLocalState()`/`applyMerged(...)`. Zeitstempel: der Server liefert ISO-8601-Strings, gespeichert wird `Instant`; benutze `Instant.parse(...)` mit `runCatching { … }.getOrDefault(Instant.EPOCH)` für `createdAt`, damit ein unerwartetes Format keine Zeile verliert. `AppNotification.createdAt`/`deletedAt`/`snoozedUntil` bleiben `String?` und werden per `toString()` zurückgewandelt.

- [ ] **Step 5: Repository implementieren**

`NotificationRepositoryImpl` bekommt `private val notificationDao: NotificationDao` als zweiten Konstruktorparameter. Zu implementieren:

- `observeNotifications(ownerUserId, trashed)` → `notificationDao.observe(...).map { it.map(NotificationEntity::toDomain) }`
- `observeUnreadCount(ownerUserId)` → `notificationDao.observeUnreadCount(ownerUserId)`
- `sync(ownerUserId)`:
  1. `api.getNotifications(...)` und `api.getTrash(...)` laden (Seite 1, `pageSize = 50`). Jede Ausnahme ⇒ `Result.failure`, **ohne** den Cache anzufassen.
  2. `dao.getAll(ownerUserId)` als lokalen Stand holen.
  3. Pro Server-Zeile `NotificationMerge.merge(local.toLocalState(), serverState, Instant.now())` aufrufen; Pushes ausführen (`api.markAsRead`, `api.dismiss`, `api.restore`, `api.snooze`); Ergebniszeile aus der **Server**-Zeile bauen (`source = "REST"`, `isPartial = false`) und die zusammengeführten Zustandsfelder daraufsetzen.
  4. Lokale Zeilen ohne Server-Entsprechung, die **keine** offene Absicht tragen, löschen — so wird ein Hard-Delete am Vollabgleich erkannt.
  5. `dao.upsertAll(...)`, dann `evictOverflow(ownerUserId)`.
  6. `Result.success(Unit)`
- `evictOverflow(ownerUserId)`: `count` > 500 ⇒ `getEvictable` nehmen, die ältesten `count - 500` löschen.
- `markReadLocally`/`dismissLocally`/`restoreLocally`/`snoozeLocally`: Zeile per `find` holen (null ⇒ nichts tun), optimistisch den sichtbaren Zustand setzen **und** den passenden `local*`-Zeitstempel, dann `update`.
- `deletePermanently`/`emptyTrash`: erst Server, dann lokal löschen. Ein Fehlschlag lässt lokal alles stehen.
- `upsertFromPush(entity)` → `dao.upsertAll(listOf(entity))`
- `clearAll()` → `dao.deleteAll()`

Die bestehenden acht Methoden bleiben unverändert.

- [ ] **Step 6: Interface nachziehen**

`NotificationRepository` um die in **Interfaces** genannten Signaturen erweitern. `kotlin.Result` beibehalten.

- [ ] **Step 7: Tests und Build prüfen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS, XML-Zählung > 0.

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/data" "app/src/main/java/com/baluhost/android/domain/repository/NotificationRepository.kt" "app/src/test/java/com/baluhost/android/data/repository/NotificationRepositoryImplTest.kt"
git commit -m "feat(notifications): make the repository cache-first with a two-way sync"
```

---

### Task 5: FCM schreibt in den Cache

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/services/BaluFirebaseMessagingService.kt`
- Create: `app/src/main/java/com/baluhost/android/data/notification/PushNotificationStore.kt`
- Test: `app/src/test/java/com/baluhost/android/data/notification/PushNotificationStoreTest.kt` (neu)

**Interfaces:**
- Consumes: `NotificationRepository.upsertFromPush`, `PreferencesManager.getUserId()`.
- Produces: `PushNotificationStore.store(data: Map<String, String>, title: String, body: String, receivedAt: Instant): Boolean` — `false`, wenn nichts gespeichert wurde.

Die Logik kommt in eine eigene, injizierbare Klasse statt in den Service, weil `FirebaseMessagingService` unter reinem JUnit nicht instanziierbar ist.

- [ ] **Step 1: Failing Tests schreiben**

Neue Datei `app/src/test/java/com/baluhost/android/data/notification/PushNotificationStoreTest.kt`:

```kotlin
package com.baluhost.android.data.notification

import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class PushNotificationStoreTest {

    private lateinit var repository: NotificationRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var store: PushNotificationStore

    private val receivedAt = Instant.parse("2026-08-01T12:00:00Z")

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        every { preferencesManager.getUserId() } returns flowOf(3)
        store = PushNotificationStore(repository, preferencesManager)
    }

    @Test
    fun `a push is stored as a partial row for the signed-in account`() = runTest {
        val stored = slot<NotificationEntity>()
        coEvery { repository.upsertFromPush(capture(stored)) } returns Unit

        val result = store.store(
            data = mapOf(
                "notification_id" to "42",
                "category" to "raid",
                "priority" to "2",
                "action_url" to "/storage"
            ),
            title = "RAID degraded",
            body = "Disk 2 missing",
            receivedAt = receivedAt
        )

        assertTrue(result)
        assertEquals(3, stored.captured.ownerUserId)
        assertEquals(42, stored.captured.id)
        assertEquals("raid", stored.captured.category)
        assertEquals(2, stored.captured.priority)
        assertEquals("/storage", stored.captured.actionUrl)
        assertEquals("RAID degraded", stored.captured.title)
        assertEquals(receivedAt, stored.captured.createdAt)
        assertEquals("FCM", stored.captured.source)
        assertTrue(stored.captured.isPartial)
    }

    @Test
    fun `nothing is stored while no account is signed in`() = runTest {
        // The row could not be attributed to anyone, and handing it to whoever
        // signs in next would be wrong.
        every { preferencesManager.getUserId() } returns flowOf(null)

        val result = store.store(
            data = mapOf("notification_id" to "42"),
            title = "t", body = "b", receivedAt = receivedAt
        )

        assertFalse(result)
        coVerify(exactly = 0) { repository.upsertFromPush(any()) }
    }

    @Test
    fun `a push without a usable notification id is not stored`() = runTest {
        val result = store.store(
            data = mapOf("category" to "raid"),
            title = "t", body = "b", receivedAt = receivedAt
        )

        assertFalse(result)
        coVerify(exactly = 0) { repository.upsertFromPush(any()) }
    }

    @Test
    fun `a missing category falls back to system rather than dropping the push`() = runTest {
        val stored = slot<NotificationEntity>()
        coEvery { repository.upsertFromPush(capture(stored)) } returns Unit

        store.store(
            data = mapOf("notification_id" to "7"),
            title = "t", body = "b", receivedAt = receivedAt
        )

        assertEquals("system", stored.captured.category)
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*PushNotificationStoreTest"`
Expected: Kompilierfehler — `PushNotificationStore` existiert nicht.

- [ ] **Step 3: Implementieren**

Neue Datei `PushNotificationStore.kt`: `@Singleton class PushNotificationStore @Inject constructor(private val repository: NotificationRepository, private val preferencesManager: PreferencesManager)`. `store(...)` liest `preferencesManager.getUserId().first()`; ist er `null`, `return false`. `data["notification_id"]?.toIntOrNull()` — bei `null` oder `0` ebenfalls `false`. Sonst eine `NotificationEntity` bauen mit `category = data["category"] ?: "system"`, `priority = data["priority"]?.toIntOrNull() ?: 0`, `actionUrl = data["action_url"]?.takeIf { it.isNotBlank() }`, `notificationType = "info"`, `createdAt = receivedAt`, `source = "FCM"`, `isPartial = true`, `isRead = false`, und `repository.upsertFromPush(...)` aufrufen. `return true`.

- [ ] **Step 4: Service anbinden**

In `BaluFirebaseMessagingService`:

```kotlin
    @Inject
    lateinit var pushNotificationStore: PushNotificationStore
```

und in `handleBackendNotification(...)` nach dem Anzeigen der System-Notification, statt `notificationWebSocketManager.incrementUnreadCount()`:

```kotlin
        // Persist so the notification survives without a server connection.
        // The unread badge now derives from the local table, so no separate
        // counter has to be nudged here.
        CoroutineScope(Dispatchers.IO).launch {
            pushNotificationStore.store(data, title, body, Instant.now())
        }
```

- [ ] **Step 5: Tests und Build prüfen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS, XML-Zählung > 0.

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/data/notification/PushNotificationStore.kt" "app/src/main/java/com/baluhost/android/services/BaluFirebaseMessagingService.kt" "app/src/test/java/com/baluhost/android/data/notification/PushNotificationStoreTest.kt"
git commit -m "feat(notifications): persist FCM pushes so they survive without a connection"
```

---

### Task 6: WebSocket schreibt in den Cache

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/data/notification/NotificationWebSocketManager.kt`
- Test: `app/src/test/java/com/baluhost/android/data/notification/NotificationWebSocketManagerTest.kt` (neu)

**Interfaces:**
- Consumes: `NotificationRepository.upsertFromPush`, `PreferencesManager.getUserId()`, `NotificationDto.toEntity`.
- Produces: keine neuen Signaturen; `latestNotification` bleibt für die bestehende Live-Anzeige erhalten.

Kontext für den Implementierenden: laut [BaluHost #306](https://github.com/Xveyn/BaluHost/issues/306) ist der WebSocket-Broadcast bei vier Prod-Workern prozess-lokal. Der Live-Kanal ist also **nicht** verlässlich — er ergänzt den Abgleich, ersetzt ihn nicht.

- [ ] **Step 1: Failing Test schreiben**

Neue Datei mit einem Test, der eine über `onMessage` eingehende `notification`-Nachricht in den Cache schreibt: `NotificationWebSocketManager` bekommt `NotificationRepository` und `PreferencesManager` injiziert; der Test ruft die Persistenzfunktion direkt auf (der `WebSocketListener` selbst ist nicht ohne echten Socket testbar). Zu prüfen: Zeile wird mit `source = "WEBSOCKET"` und `isPartial = false` gespeichert; ohne angemeldetes Konto wird nichts gespeichert.

```kotlin
package com.baluhost.android.data.notification

import com.baluhost.android.data.local.database.entities.NotificationEntity
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.remote.dto.NotificationDto
import com.baluhost.android.domain.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationWebSocketManagerTest {

    private val okHttpClient: okhttp3.OkHttpClient = mockk(relaxed = true)
    private val api: com.baluhost.android.data.remote.api.NotificationsApi = mockk(relaxed = true)
    private val repository: NotificationRepository = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)

    private fun manager(userId: Int?): NotificationWebSocketManager {
        every { preferencesManager.getUserId() } returns flowOf(userId)
        return NotificationWebSocketManager(okHttpClient, api, preferencesManager, repository)
    }

    private val dto = NotificationDto(
        id = 11,
        createdAt = "2026-08-01T10:00:00Z",
        userId = null,
        notificationType = "critical",
        category = "smart",
        title = "SMART failure",
        message = "sda is failing",
        actionUrl = null,
        isRead = false,
        deletedAt = null,
        priority = 3,
        metadata = null,
        timeAgo = null,
        snoozedUntil = null
    )

    @Test
    fun `a live notification is cached as a complete row`() = runTest {
        val stored = slot<NotificationEntity>()
        coEvery { repository.upsertFromPush(capture(stored)) } returns Unit

        manager(userId = 3).persist(dto)

        assertEquals(3, stored.captured.ownerUserId)
        assertEquals(11, stored.captured.id)
        assertEquals("WEBSOCKET", stored.captured.source)
        assertFalse(stored.captured.isPartial)
    }

    @Test
    fun `nothing is cached while no account is signed in`() = runTest {
        manager(userId = null).persist(dto)

        coVerify(exactly = 0) { repository.upsertFromPush(any()) }
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*NotificationWebSocketManagerTest"`
Expected: Kompilierfehler — der Konstruktor nimmt kein Repository, `persist` existiert nicht.

- [ ] **Step 3: Implementieren**

`NotificationWebSocketManager` bekommt `private val notificationRepository: NotificationRepository` als vierten Konstruktorparameter und eine Funktion:

```kotlin
    /** Cache a live notification. Internal so the socket listener and tests share one path. */
    suspend fun persist(dto: NotificationDto) {
        val owner = preferencesManager.getUserId().first() ?: return
        notificationRepository.upsertFromPush(dto.toEntity(owner, source = "WEBSOCKET"))
    }
```

Im `onMessage`-Zweig `"notification"` zusätzlich zum bestehenden `_latestNotification.emit(notification)` ein `persist(notification)` im selben `scope.launch`.

**`_unreadCount` und `incrementUnreadCount()` bleiben vorerst stehen** — sie werden in Task 7 abgelöst, wenn alle Konsumenten umgestellt sind. Ein Entfernen hier würde `DashboardViewModel` brechen.

- [ ] **Step 4: Tests und Build prüfen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS, XML-Zählung > 0.

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/data/notification/NotificationWebSocketManager.kt" "app/src/test/java/com/baluhost/android/data/notification/NotificationWebSocketManagerTest.kt"
git commit -m "feat(notifications): cache live WebSocket notifications"
```

---

### Task 7: Ungelesen-Zähler aus der Datenbank

Der Zähler lebt heute nur im Speicher (`NotificationWebSocketManager:43`) und fällt bei jedem Prozesstod auf 0 — auch der per FCM hochgezählte Wert.

**Files:**
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/notification/ObserveUnreadCountUseCase.kt`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/dashboard/DashboardViewModel.kt:96`
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/notifications/NotificationsViewModel.kt:44`
- Modify: `app/src/main/java/com/baluhost/android/data/notification/NotificationWebSocketManager.kt`
- Test: `app/src/test/java/com/baluhost/android/domain/usecase/notification/ObserveUnreadCountUseCaseTest.kt` (neu)

**Interfaces:**
- Produces: `ObserveUnreadCountUseCase.invoke(): Flow<Int>` — beobachtet `PreferencesManager.getUserId()` und schaltet per `flatMapLatest` auf `repository.observeUnreadCount(owner)`; ohne angemeldetes Konto `flowOf(0)`.

- [ ] **Step 1: Failing Test schreiben**

```kotlin
package com.baluhost.android.domain.usecase.notification

import app.cash.turbine.test
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.repository.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveUnreadCountUseCaseTest {

    private val repository: NotificationRepository = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)

    @Test
    fun `the count comes from the local table for the signed-in account`() = runTest {
        every { preferencesManager.getUserId() } returns flowOf(3)
        every { repository.observeUnreadCount(3) } returns flowOf(4)

        ObserveUnreadCountUseCase(repository, preferencesManager)().test {
            assertEquals(4, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `without a signed-in account the count is zero`() = runTest {
        every { preferencesManager.getUserId() } returns flowOf(null)

        ObserveUnreadCountUseCase(repository, preferencesManager)().test {
            assertEquals(0, awaitItem())
            awaitComplete()
        }
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*ObserveUnreadCountUseCaseTest"`
Expected: Kompilierfehler — `ObserveUnreadCountUseCase` existiert nicht.

- [ ] **Step 3: Use Case implementieren**

```kotlin
package com.baluhost.android.domain.usecase.notification

import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.domain.repository.NotificationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * Unread count derived from the local table rather than an in-memory counter,
 * so it survives process death - including the increments that arrived by push
 * while the app was not running.
 */
class ObserveUnreadCountUseCase @Inject constructor(
    private val repository: NotificationRepository,
    private val preferencesManager: PreferencesManager
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<Int> =
        preferencesManager.getUserId().flatMapLatest { owner ->
            if (owner == null) flowOf(0) else repository.observeUnreadCount(owner)
        }
}
```

- [ ] **Step 4: Konsumenten umstellen**

- `DashboardViewModel:96`: `notificationWebSocketManager.unreadCount` ersetzen durch `observeUnreadCountUseCase().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)`; den Use Case in den Konstruktor aufnehmen.
- `NotificationsViewModel:44`: analog.
- In beiden bestehenden Testdateien (`DashboardViewModelDesktopActionTest`, `DashboardViewModelVpnActionTest`) den neuen Konstruktorparameter ergänzen und `coEvery`/`every` so stubben, dass er `flowOf(0)` liefert — ein ungestubbter Flow, der nie emittiert, lässt die ViewModel-Initialisierung hängen (siehe `app/src/test/CLAUDE.md`).

- [ ] **Step 5: Toten Zähler entfernen**

Aus `NotificationWebSocketManager` `_unreadCount`, `unreadCount` und `incrementUnreadCount()` entfernen — jetzt sind alle Konsumenten umgestellt. Der `"unread_count"`-Zweig in `onMessage` verliert damit seine Wirkung; ihn ersatzlos entfernen, da der Zählerstand nun aus der lokalen Tabelle kommt.

- [ ] **Step 6: Tests und Build prüfen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS, XML-Zählung > 0.

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add "app/src/main/java/com/baluhost/android" "app/src/test/java/com/baluhost/android"
git commit -m "feat(notifications): derive the unread badge from the local table"
```

---

### Task 8: Beim Entkoppeln aufräumen

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModel.kt:199-202`
- Modify: `app/src/main/java/com/baluhost/android/services/BaluFirebaseMessagingService.kt:226-229`
- Test: `app/src/test/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModelTest.kt` (neu oder vorhandene erweitern)

**Interfaces:**
- Consumes: `NotificationRepository.clearAll()` (Task 4).

**Umfangsgrenze:** Es wird **nur** die Notification-Tabelle geleert. Dass die übrigen Room-Tabellen ein Entkoppeln überleben, ist ein vorbestehender Befund und als [BaluApp #7](https://github.com/Xveyn/BaluApp/issues/7) erfasst — hier ausdrücklich **nicht** mitfixen.

- [ ] **Step 1: Failing Test schreiben**

Test, der `deleteDevice()` aufruft und `coVerify(exactly = 1) { notificationRepository.clearAll() }` prüft, sowie dass das auch dann passiert, wenn der Server-Aufruf `deleteDevice` wirft (der bestehende Kommentar an `:199` sagt „Always clear local data regardless of server response").

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*SettingsViewModelTest"`
Expected: FAIL — `clearAll()` wird nicht gerufen.

- [ ] **Step 3: Implementieren**

In `SettingsViewModel.deleteDevice()` nach `securePreferences.clearAll()`:

```kotlin
            // Notifications are per-account; leaving them would show the next
            // account what the previous one received. Only this table - the
            // remaining ones are BaluApp#7.
            notificationRepository.clearAll()
```

In `BaluFirebaseMessagingService`, `handleDeviceRemoved`, im bestehenden `CoroutineScope(Dispatchers.IO).launch` neben `preferencesManager.clearAll()` dasselbe ergänzen (Repository per `@Inject lateinit var`).

- [ ] **Step 4: Tests und Build prüfen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS, XML-Zählung > 0.

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/presentation/ui/screens/settings/SettingsViewModel.kt" "app/src/main/java/com/baluhost/android/services/BaluFirebaseMessagingService.kt" "app/src/test/java/com/baluhost/android/presentation/ui/screens/settings"
git commit -m "feat(notifications): clear the notification table when the device is unpaired"
```

---

### Task 9: ViewModel auf den Cache umstellen

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/notifications/NotificationsViewModel.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/notification/ObserveNotificationsUseCase.kt`
- Create: `app/src/main/java/com/baluhost/android/domain/usecase/notification/SyncNotificationsUseCase.kt`
- Test: `app/src/test/java/com/baluhost/android/presentation/ui/screens/notifications/NotificationsViewModelTest.kt` (neu)

**Interfaces:**
- Produces: `NotificationsViewModel.UiState` erhält `tab: Tab` (`INBOX`/`TRASH`), `typeFilter: NotificationType?`, `isOffline: Boolean`, `retentionDays: Int`; neue Funktionen `setTab`, `setTypeFilter`, `restore`, `deletePermanently`, `emptyTrash`, `dismissAll`. Task 10 nutzt diese.

- [ ] **Step 1: Failing Tests schreiben**

Zu prüfen sind mindestens: die Liste kommt aus dem Cache und ist ohne Serververbindung nicht leer; ein fehlgeschlagener `sync` setzt `isOffline = true` **ohne** `error` zu setzen (Hintergrundabgleich ist eine Ermittlungsoperation); `markAsRead` schreibt sofort lokal und zeigt den Zustand, auch wenn der Server nicht erreichbar ist; ein Wechsel auf `Tab.TRASH` beobachtet den Papierkorb-Flow; `dismiss` in der Inbox lässt die Zeile verschwinden und im Papierkorb auftauchen. Filter nach Kategorie und Typ werden auf dem beobachteten Flow angewandt.

Beachte `app/src/test/CLAUDE.md`: `Dispatchers.setMain(UnconfinedTestDispatcher())` in `@Before`, `resetMain()` in `@After`, und jeden `PreferencesManager`-Flow stubben, den `init` anfasst.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests "*NotificationsViewModelTest"`
Expected: Kompilierfehler — die neuen Felder und Funktionen existieren nicht.

- [ ] **Step 3: Use Cases anlegen**

`ObserveNotificationsUseCase(repository, preferencesManager)` mit `operator fun invoke(trashed: Boolean): Flow<List<AppNotification>>`, analog zu `ObserveUnreadCountUseCase` per `flatMapLatest` auf die Konto-id; ohne Konto `flowOf(emptyList())`.

`SyncNotificationsUseCase(repository, preferencesManager)` mit `suspend operator fun invoke(): Result<Unit>` (`util.Result`), das ohne angemeldetes Konto `Result.Error(Exception("Kein Konto angemeldet"))` liefert.

- [ ] **Step 4: ViewModel umbauen**

- `notifications` kommt aus `ObserveNotificationsUseCase(trashed = tab == Tab.TRASH)`, per `flatMapLatest` auf den Tab-Wechsel, gefiltert nach `selectedCategory` (Vergleich auf `rawCategory`), `typeFilter` und `unreadOnly`.
- `loadNotifications()` wird zu `refresh()`: ruft `SyncNotificationsUseCase`; bei `Result.Error` `isOffline = true` setzen und **kein** `error`; bei Erfolg `isOffline = false`.
- `markAsRead`/`dismiss`/`snooze` rufen die lokalen Repository-Funktionen und danach `refresh()` als Best-Effort-Push.
- `restore`/`deletePermanently`/`emptyTrash`/`dismissAll` ergänzen; bei Fehlschlag eine Snackbar-Meldung, weil der Nutzer sie ausgelöst hat.
- `retentionDays` aus `GetNotificationPreferencesUseCase` laden (Feld `trashRetentionDays` aus Task 1), Rückfall `7`.
- **`observeWebSocket()` entfällt**: die Live-Nachricht landet seit Task 6 im Cache, und der beobachtete Flow zeigt sie von selbst. Das beseitigt zugleich den vorbestehenden Fehler, dass eine während eines laufenden Abrufs eintreffende WebSocket-Notification vom zurückkehrenden Ergebnis überschrieben wurde (alter Stand: `state` wurde vor dem Netzwerkaufruf gelesen und danach zurückgeschrieben).

- [ ] **Step 5: Die übrigen Abgleich-Auslöser verdrahten**

Die Spec nennt vier Auslöser. `init` deckt „Screen geöffnet" ab; die anderen drei fehlen sonst:

**App-Start:** In `BaluHostApplication.onCreate()` einen Abgleich anstoßen, damit ein per Push eingegangener Bestand auch ohne Öffnen des Screens vervollständigt wird:

```kotlin
    @Inject
    lateinit var syncNotificationsUseCase: SyncNotificationsUseCase

    // In onCreate(), after super.onCreate():
    // A push may have landed while the app was dead. Reconciling here keeps the
    // badge honest before the user opens anything.
    CoroutineScope(Dispatchers.IO).launch { syncNotificationsUseCase() }
```

**Verbindung wiedererlangt:** Im `NotificationsViewModel` auf `NetworkStateManager` hören und bei Rückkehr der Verbindung `refresh()` rufen. Verwende dieselbe API, die `DashboardViewModel` bereits nutzt — vor dem Schreiben in `util/NetworkStateManager.kt` nachsehen, welche Funktion das genau ist, und **nicht** raten.

**Nach FCM-Empfang:** In `PushNotificationStore.store(...)` nach erfolgreichem `upsertFromPush` einen Abgleich als Best-Effort anstoßen und jede Ausnahme schlucken — der Server ist in genau dem Szenario, für das dieses Vorhaben existiert, typischerweise nicht erreichbar, und ein Fehlschlag darf die bereits gespeicherte Zeile nicht gefährden.

Ergänze für jeden der drei Auslöser einen Test, wo die Klasse testbar ist: für `PushNotificationStore` ein Test, dass ein fehlschlagender Abgleich das Speichern **nicht** rückgängig macht (`store` liefert weiterhin `true`).

- [ ] **Step 6: Tests und Build prüfen**

Run: `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`
Expected: PASS, XML-Zählung > 0.

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add "app/src/main/java/com/baluhost/android" "app/src/test/java/com/baluhost/android"
git commit -m "feat(notifications): drive the notification screen from the local cache"
```

---

### Task 10: Papierkorb, Filter und die neuen Aktionen in der Oberfläche

**Files:**
- Modify: `app/src/main/java/com/baluhost/android/presentation/ui/screens/notifications/NotificationsScreen.kt`
- Modify: `app/src/main/java/com/baluhost/android/domain/usecase/CLAUDE.md`
- Modify: `app/src/main/java/com/baluhost/android/data/local/CLAUDE.md`
- Modify: `app/src/main/java/com/baluhost/android/domain/model/CLAUDE.md`

**Interfaces:**
- Consumes: die in Task 9 ergänzten `UiState`-Felder und Funktionen.
- Produces: nichts — Ende der Kette.

Für diese Task gibt es **keinen** automatisierten Test: das Repo hat kein `androidTest`-Sourceset und keine Compose-UI-Tests (`app/src/test/CLAUDE.md`). Das ist eine dokumentierte Tatsache, kein Versäumnis — **keinen** anlegen. Nachweis sind der Build und der Handtest.

- [ ] **Step 1: Tab-Umschaltung ergänzen**

Unter der `TopAppBar` eine `TabRow` mit „Posteingang" und „Papierkorb", gebunden an `uiState.tab` und `viewModel.setTab(...)`. Farben aus `presentation/ui/theme/` (`Sky400` für den aktiven Tab, `Slate400` für den inaktiven), passend zum bestehenden Bildschirm.

- [ ] **Step 2: Filterzeile ergänzen**

Die vorhandene `LazyRow` mit Kategorie-Chips um Typ-Chips („Info", „Warnung", „Kritisch" → `NotificationType`) und einen „Nur ungelesen"-Chip erweitern. Der Kategorie-Chip schreibt jetzt den **rohen String**, nicht das Enum — sonst sind Plugin-Kategorien und `lifecycle` nicht filterbar.

- [ ] **Step 3: Offline-Kennzeichnung ergänzen**

Bei `uiState.isOffline` eine Zeile über der Liste: `Text("Offline – zuletzt bekannter Stand", color = Orange500, style = MaterialTheme.typography.bodySmall)`. Keine Fehlermeldung und kein Dialog: der Bestand ist ja da.

- [ ] **Step 4: Aktionen ergänzen**

- Im Papierkorb-Tab pro Eintrag „Wiederherstellen" (`Icons.Default.RotateLeft`) und „Endgültig löschen" (`Icons.Default.DeleteForever`).
- In der `TopAppBar` im Papierkorb-Tab „Papierkorb leeren" (`Icons.Default.DeleteSweep`), im Posteingang „Alle wegklicken" (`Icons.Default.ClearAll`) — beide mit `AlertDialog`-Bestätigung, weil sie viele Einträge auf einmal betreffen und, anders als die Gaming-Aktionen, nicht mit einem Klick rückgängig zu machen sind.
- Im Papierkorb-Kopf ein Hinweis: `Text("Einträge werden nach ${uiState.retentionDays} Tagen endgültig gelöscht", …)`, wie es die Webapp tut.

- [ ] **Step 5: Bauen und von Hand prüfen**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

Danach am Gerät gegen den echten Server:

1. Notifications laden, App in den Flugmodus, Screen erneut öffnen → **Liste ist weiterhin da**, Offline-Kennzeichnung erscheint.
2. Im Flugmodus eine Notification lesen und eine wegklicken → beide Änderungen sind sofort sichtbar.
3. Flugmodus aus, Screen aktualisieren → beide Änderungen sind auf dem Server angekommen (Gegenprobe in der Webapp).
4. In der Webapp eine Notification wegklicken, in der App aktualisieren → sie wandert in den Papierkorb-Tab.
5. Im Papierkorb wiederherstellen → sie steht wieder im Posteingang, in der Webapp ebenso.
6. Bei **geschlossener App und ohne VPN/WLAN-Verbindung zum Server** eine Notification auslösen (z. B. serverseitig) → sie kommt per Push an; App öffnen, weiterhin ohne Serververbindung → sie steht in der Liste.
7. Gerät entkoppeln und neu koppeln → die Liste ist leer.

Punkt 6 ist der eigentliche Zweck des Vorhabens; ihn bitte ausdrücklich bestätigen.

- [ ] **Step 6: Doku nachziehen**

- `domain/usecase/CLAUDE.md`: Gesamtzahlen und die `notification/`-Zeile anpassen (drei neue Use Cases: `ObserveUnreadCountUseCase`, `ObserveNotificationsUseCase`, `SyncNotificationsUseCase`). **Zahlen auf der Platte zählen, nicht aus dem Plan übernehmen.**
- `data/local/CLAUDE.md`: Room-Version 4 → 5, die neue Entity und den neuen DAO in die Tabellen aufnehmen, und den Abschnitt „Adding a new local store" um den Hinweis ergänzen, dass es jetzt eine echte Migration gibt.
- `domain/model/CLAUDE.md`: Der Abschnitt zu `Notification.kt` beschreibt `AppNotification`; `isDismissed` ist durch `deletedAt` ersetzt und `rawCategory` dazugekommen — inklusive der Bedeutung von `null` bei `deletedAt` (aktiv, nicht „unbekannt").

- [ ] **Step 7: Commit**

```bash
git add "app/src/main/java/com/baluhost/android/presentation/ui/screens/notifications/NotificationsScreen.kt" "app/src/main/java/com/baluhost/android/domain/usecase/CLAUDE.md" "app/src/main/java/com/baluhost/android/data/local/CLAUDE.md" "app/src/main/java/com/baluhost/android/domain/model/CLAUDE.md"
git commit -m "feat(notifications): add the trash tab, filters and bulk actions"
```

---

## Verifikation zum Abschluss

- [ ] `./gradlew cleanTestDebugUnitTest testDebugUnitTest --no-build-cache` grün, **und** die XML-Zählung bestätigt einen echten Lauf.
- [ ] `./gradlew assembleDebug` grün.
- [ ] Der siebenschrittige Handtest aus Task 10 durchgeführt, insbesondere Punkt 6 (Push ohne Serververbindung landet im Bestand).
- [ ] Die Migration 4 → 5 an einer **bestehenden** Installation geprüft: App über eine vorhandene Version installieren (nicht deinstallieren!) und bestätigen, dass die Datenbank nicht zurückgesetzt wurde. `.fallbackToDestructiveMigration()` steht weiterhin im Builder — wenn die Migration nicht greift, fällt das nur hier auf, und zwar als stiller Datenverlust.
- [ ] `./gradlew assembleRelease` bleibt **außerhalb** dieses Plans: der erste Release-Build dieses Repos ist ein eigener, noch offener Punkt (R8 + Retrofit ohne Keep-Regeln).
