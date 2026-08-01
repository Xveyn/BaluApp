# Notifications lokal persistieren und zweiseitig abgleichen — Design

**Datum:** 2026-08-01
**Repo:** BaluApp (Android-Client)
**Server-Abhängigkeit:** [BaluHost #504](https://github.com/Xveyn/BaluHost/issues/504) — angelegt, **nicht blockierend**

## Problem

Notifications erreichen das Gerät über Firebase Cloud Messaging. Dieser Weg ist von WLAN und VPN unabhängig: die Push-Nachricht kommt an, während die REST-API des Servers unerreichbar ist. Die App wirft diese Information heute weg und zeigt ohne Verbindung eine leere Liste.

Verifizierter Ist-Zustand:

- **Keine lokale Persistenz.** `BaluHostDatabase.kt` (Version 4) registriert `FileEntity`, `UserEntity`, `PendingOperationEntity`, `FileActivityEntity` — nichts für Notifications.
- **`NotificationRepositoryImpl` ist ein reiner Durchreicher**: jede der acht Methoden ist `try { notificationsApi.…() } catch { Result.failure }`, ohne Cache.
- **Ohne Verbindung leere Liste.** `NotificationsViewModel.kt:84-91` setzt bei `Result.Error` nur `error` und lässt `notifications` stehen — beim Kaltstart also `emptyList()`.
- **Die FCM-Nutzlast wird verworfen.** `BaluFirebaseMessagingService.handleBackendNotification()` (`:237-285`) zeigt die System-Notification an, ruft `incrementUnreadCount()` und speichert nichts.
- **Der Ungelesen-Zähler ist flüchtig.** `NotificationWebSocketManager._unreadCount` (`:43`) lebt nur im Speicher und fällt bei jedem Prozesstod auf 0 — inklusive der per FCM hochgezählten Werte.

Die Webapp taugt hier nicht als Vorlage: `NotificationContext.tsx:36` hält den Bestand in reinem `useState`, es gibt keinen Service Worker und kein PWA-Manifest, und Notifications laufen auch nicht durch den `queryPersister` (der spiegelt nur den TanStack-Query-Cache nach `sessionStorage`). Das ist keine Nachlässigkeit, sondern eine Asymmetrie: die Webapp wird *vom Server ausgeliefert* — ohne Server gibt es sie gar nicht. Die App ist installiert und läuft weiter.

## Ziel

Parität mit der Webapp-Ansicht `NotificationsArchivePage.tsx` — Inbox und Papierkorb, Filter nach Kategorie/Typ/ungelesen, Paginierung, und die Aktionen Lesen, Alle-Lesen, Wegklicken, Alle-Wegklicken, Wiederherstellen, Endgültig-Löschen, Papierkorb-Leeren, Snooze — **plus** vollständige Nutzbarkeit ohne Serververbindung.

### Nicht-Ziele

- Kein eigenes Archiv in der App. Der Server *ist* das Archiv, inklusive Papierkorb und Retention. Die App hält einen Cache, keine unabhängige Historie.
- Keine Änderung am Server in diesem Vorhaben. Was dort fehlt, steht in #504 und wird separat entschieden.
- Keine Mehrfachkonten. Ein Gerät ist an ein Konto gekoppelt; ein Kontowechsel geht über Entkoppeln und Neu-Koppeln.

## Server-Kontrakt: was da ist und was fehlt

Alle für die Parität nötigen Endpunkte **existieren bereits**:

| Endpunkt | Zweck |
|---|---|
| `GET /api/notifications` | Inbox, paginiert, Filter `unread_only`/`category`/`notification_type`/`created_after`/`created_before` |
| `GET /api/notifications/trash` | Papierkorb, paginiert, gleiche Filter |
| `GET /api/notifications/unread-count` | Ungelesen-Zähler, optional je Kategorie |
| `POST /api/notifications/{id}/read`, `/read-all` | Lesen, Alle lesen |
| `POST /api/notifications/{id}/dismiss`, `/dismiss-all` | In den Papierkorb |
| `POST /api/notifications/{id}/restore` | Aus dem Papierkorb |
| `DELETE /api/notifications/{id}`, `DELETE /api/notifications/trash` | Endgültig löschen, Papierkorb leeren |
| `POST /api/notifications/{id}/snooze?duration_hours=` | Snooze (1–168 h) |
| `GET`/`PUT /api/notifications/preferences` | Einstellungen inkl. `trash_retention_days` (1–7) |

Was fehlt (siehe #504):

1. **Kein Schreib-Zeitstempel für den Papierkorb-Zustand.** `deleted_at` trägt den Zeitpunkt des Wegklickens, aber `restore()` setzt ihn auf `NULL` und hinterlässt keine Spur. Ein Rennen zwischen offline weggeklickt und in der Webapp wiederhergestellt ist damit nicht auflösbar.
2. **Keine inkrementelle Abfrage.** `created_after`/`created_before` filtern nach Erstellung, nicht nach Änderung.
3. **Keine Grabsteine bei Hard-Deletes.** Endgültig gelöschte Zeilen verschwinden ohne Spur.

**`is_read` braucht nichts.** Es gibt `/read` und `/read-all`, aber keinen Endpunkt, der etwas auf ungelesen zurücksetzt — das Feld ist monoton.

### Übergangsregel bis #504

> **Beim Papierkorb-Zustand gewinnt der Server.**

Ein verlorenes Wegklicken ist harmlos, der Nutzer klickt erneut. Eine wieder auftauchende, bereits gelöschte Notification ist sichtbar falsch. Im Zweifel also die konservative Richtung. Die Regel wird durch echtes LWW ersetzt, sobald `updated_at` verfügbar ist; die dafür nötigen lokalen Zeitstempel werden von Anfang an geführt.

## Architektur

### Speicher

Neue Room-Entity `NotificationEntity` mit `NotificationDao`, Datenbank **Version 4 → 5**.

Zusammengesetzter Primärschlüssel **`(ownerUserId, id)`**.

- **`ownerUserId`** ist die id des angemeldeten Kontos aus `PreferencesManager.getUserId()` — **nicht** das `user_id`-Feld der Notification. Letzteres ist bei Broadcasts an Admins `null` (`models/notification.py:49-54`) und taugt deshalb nicht als Eigentümer-Schlüssel.
- **`id`** ist die Server-id. Der Server legt für geroutete Nicht-Admin-Nutzer eigene Kopien mit eigener id an (`services/notifications/service.py:137-147`), Kollisionen zwischen Konten sind damit ausgeschlossen — der zusammengesetzte Schlüssel sichert es zusätzlich ab.

Felder:

| Gruppe | Felder |
|---|---|
| Schlüssel | `ownerUserId`, `id` |
| Server-Zustand | `createdAt`, `userId` (nullable = Broadcast), `notificationType`, `category` (**roher String**), `title`, `message`, `actionUrl`, `isRead`, `deletedAt` (nullable), `priority`, `metadata`, `snoozedUntil` |
| Lokale Absicht | `localReadAt`, `localTrashedAt`, `localRestoredAt`, `localSnoozedUntil` (alle nullable) |
| Herkunft | `source` (`REST`/`WEBSOCKET`/`FCM`), `isPartial` |

`metadata` ist ein `Map<String, Any>?` und braucht einen Room-TypeConverter (Ablage als JSON-String); `converters/Converters.kt` ist die vorhandene Stelle dafür.

`localSnoozedUntil` wird **absolut** gespeichert, nicht als Dauer. Der Endpunkt nimmt `duration_hours`, also wird beim Push die verbleibende Zeit ab *jetzt* berechnet und auf volle Stunden aufgerundet (Minimum 1, der Server verlangt 1–168). Liegt der Zeitpunkt beim Push bereits in der Vergangenheit, wird die Absicht ersatzlos verworfen — eine abgelaufene Schlummerfunktion nachträglich zu setzen wäre sinnlos.

**Die drei lokalen Zeitstempel sind die Warteschlange.** Es gibt kein separates Operationslog. Begründung: Der Zustand einer Notification ist ein kleiner, idempotenter Satz von Feldern, kein Strom geordneter Operationen. Damit entfallen Reihenfolge- und Duplikatprobleme, jeder Absturz wird überlebt, und die spätere Umstellung auf echtes LWW ist trivial, weil die lokalen Zeitpunkte bereits vorliegen.

Verworfene Alternativen:
- **Die bestehende `OfflineQueueManager`/`PendingOperationEntity` mitbenutzen.** Datei-spezifisch: `PendingOperation.filePath` ist nicht-nullable, `OperationType` kennt nur `UPLOAD/DELETE/RENAME/MOVE/CREATE_FOLDER`. Mitbenutzen hieße, `filePath` zweckzuentfremden oder ein Enum aufzubohren, an dem der Datei-Sync hängt.
- **Eigene Outbox-Tabelle mit geordnetem Log.** Löst Probleme, die dieses Vorhaben nicht hat.

**Obergrenze: 500 Zeilen pro `ownerUserId`**, älteste nach `createdAt` fliegen zuerst raus. Papierkorb-Einträge zählen mit; sie verschwinden serverseitig ohnehin nach 1–7 Tagen. Zeilen mit noch nicht gepushter lokaler Absicht sind von der Verdrängung ausgenommen — sonst ginge eine Nutzerentscheidung verloren, bevor sie den Server erreicht.

### Merge-Regeln

Als **reine Kotlin-Klasse ohne Room- und Android-Bezug** (siehe „Testbarkeit"). Sie bekommt lokalen und Server-Zustand und liefert den zusammengeführten Zustand plus die Liste der zu pushenden Aktionen.

| Feld | Regel | Begründung |
|---|---|---|
| `isRead` | **ODER** — gelesen auf einer Seite ⇒ gelesen | Monoton, es gibt kein „ungelesen machen". Konfliktfrei ohne Zeitstempel. |
| Papierkorb (`deletedAt`) | Lokale Absicht wird gepusht; danach ist die Server-Antwort maßgeblich | Übergangsregel, siehe oben. Ersetzbar durch LWW, sobald #504 landet. |
| `snoozedUntil` | Server gewinnt; `localSnoozedUntil` wird gepusht, sofern noch in der Zukunft | Seltenste Aktion, kein Grund für eine Sonderbehandlung. |
| Inhaltsfelder (`title`, `message`, …) | Server gewinnt immer | Sie sind serverseitig unveränderlich; eine Abweichung heißt, die lokale Zeile ist eine FCM-Teilzeile. |

Eine lokale Absicht wird gelöscht, sobald der Server sie bestätigt hat. Ein fehlgeschlagener Push lässt sie stehen — der nächste Abgleich versucht es erneut.

### Datenflüsse

**REST (Vollabgleich).** Inbox- und Papierkorb-Seiten werden geladen, mit dem lokalen Bestand zusammengeführt, offene lokale Absichten gepusht. Solange `updated_after` fehlt (#504, Problem 2), erkennt dieser Vollabgleich auch endgültig gelöschte Notifications an ihrer Abwesenheit — das entschärft Problem 3 vorläufig.

**WebSocket.** `NotificationWebSocketManager.latestNotification` liefert ein vollständiges `NotificationDto` und schreibt es direkt in den Speicher (`source = WEBSOCKET`, `isPartial = false`). Zu beachten: laut [BaluHost #306](https://github.com/Xveyn/BaluHost/issues/306) ist der Broadcast bei vier Prod-Workern prozess-lokal — der Live-Kanal ist also **nicht** verlässlich, was den Wert des Nachhol-Abgleichs erhöht.

**FCM.** `handleBackendNotification()` schreibt eine Zeile mit `isPartial = true`. Verfügbar sind `notification_id`, `category`, `priority`, `action_url` sowie `title`/`body` aus dem Notification-Block (`services/notifications/firebase.py:136-142`). **`category` schickt der Server schon heute mit, die App liest es bisher nicht.** Nicht verfügbar und deshalb offen gelassen: `notificationType`, `userId`, `metadata`, `snoozedUntil`; `createdAt` wird durch die Empfangszeit ersetzt. Der nächste erfolgreiche Abgleich ersetzt die Teilzeile vollständig.

Eigentümer ist das aktuell angemeldete Konto. **Ist keines angemeldet, wird die Zeile verworfen** — sie wäre niemandem zuzuordnen, und sie einem später anmeldenden Konto zuzuschlagen wäre falsch.

### Auslöser für den Abgleich

App-Start, Öffnen des Notification-Screens, Wiedererlangen der Verbindung (`NetworkStateManager`), sowie nach FCM-Empfang, falls der Server erreichbar ist.

### Ungelesen-Zähler

Kommt aus der Datenbank: Anzahl der Zeilen des eigenen Kontos mit `isRead = false` und `deletedAt = null`, als `Flow`. Ersetzt `NotificationWebSocketManager._unreadCount` als Quelle für die UI und behebt damit, dass der Zähler heute bei jedem Prozesstod auf 0 fällt.

### Kategorien

Serverseitig ein **offener** Satz: Kern-Kategorien, `lifecycle`, und Plugin-Namen (`routes/notifications.py:37-40` begründet das ausdrücklich). Das App-Enum `NotificationCategory` ist geschlossen und faltet alles Unbekannte auf `SYSTEM` (`Notification.kt:33-35`). Deshalb: **der rohe String wird gespeichert und gefiltert**, das Enum dient nur noch der Darstellung (Icon, Farbe, Beschriftung) mit einem generischen Rückfall.

### Nutzerbindung und Aufräumen

Gelesen wird immer nur der Bestand des angemeldeten Kontos. Zusätzlich wird die Tabelle beim Entkoppeln geleert — an beiden Stellen, an denen das heute passiert:

- `SettingsViewModel.deleteDevice()` (`:199-202`)
- der `device_removed`-Pfad in `BaluFirebaseMessagingService` (`:226-229`)

**Nebenbefund, hier bewusst mitgenommen:** beide Pfade leeren heute nur `PreferencesManager` und `SecurePreferencesManager`. Die Room-Datenbank bleibt vollständig stehen — im ganzen Projekt gibt es keinen `clearAllTables`-Aufruf. Dieses Vorhaben räumt **nur die Notification-Tabelle** ab; die übrigen Tabellen (gecachte Dateien, Nutzer, Aktivitäten) bleiben als eigenständiger Befund bestehen und gehören in ein separates Issue.

### UI-Parität

Der bestehende `NotificationsScreen` bekommt:

- **Inbox/Papierkorb-Umschaltung**
- **Filter** nach Kategorie, Typ und ungelesen
- **Aktionen** Wiederherstellen, Endgültig löschen, Papierkorb leeren, Alle wegklicken — zusätzlich zu den vorhandenen Lesen/Wegklicken/Snooze
- **Retention-Hinweis** im Papierkorb aus `trash_retention_days`, wie es die Webapp tut (`NotificationsArchivePage.tsx:64-68`)
- **Offline-Kennzeichnung**, wenn der angezeigte Bestand aus dem Cache stammt und der letzte Abgleich fehlschlug

### Contract-Korrekturen (Voraussetzung)

| Stelle | Ist | Soll |
|---|---|---|
| `NotificationDto.kt:20-21` | `@SerializedName("is_dismissed") val isDismissed: Boolean` | `@SerializedName("deleted_at") val deletedAt: String?` |
| `NotificationsApi.kt:11` | `@Query("include_dismissed")` | entfernen |
| `NotificationPreferencesDto` | kein `trash_retention_days` | ergänzen |

Zur ersten Zeile: der Server liefert `deleted_at` als nullable Zeitstempel (`schemas/notification.py:73-76`), ein Feld `is_dismissed` existiert nicht. Gson lässt `isDismissed` deshalb still auf `false` — die App kann den Papierkorb-Zustand heute gar nicht darstellen. Zur zweiten: `include_dismissed` kennt der Server nicht (`routes/notifications.py:34-45`), FastAPI ignoriert unbekannte Query-Parameter stillschweigend.

**Angrenzende Altlast, hier nicht angefasst:** `NotificationPreferencesDto.emailEnabled` und `CategoryPreference.email` beziehen sich auf E-Mail-Benachrichtigungen, die der Server in Migration `042_remove_email_notifications.py` entfernt hat. Es gibt keinen Schalter dafür in der UI, der Rest ist ein hartcodiertes `email = false` in `NotificationPreferencesViewModel.kt:110`. Kein Nutzerschaden, gehört in ein eigenes Aufräum-Issue.

## Fehlerbehandlung

- **Abgleich schlägt fehl:** der Cache wird angezeigt, die Offline-Kennzeichnung erscheint. Keine Fehlermeldung — ein automatischer Hintergrundabgleich ist eine Ermittlungsoperation, kein Nutzerauftrag.
- **Vom Nutzer ausgelöste Aktion schlägt fehl:** die lokale Absicht bleibt stehen, die UI zeigt den gewünschten Zustand, und der nächste Abgleich versucht den Push erneut. Eine Snackbar-Meldung nur, wenn der Nutzer die Aktion selbst ausgelöst hat.
- **Kein angemeldetes Konto:** FCM-Zeilen werden verworfen (siehe oben), der Screen zeigt den Anmelde-Zustand.
- **`ownerUserId` unbekannt** (Anmeldung vorhanden, id noch nicht geladen): kein Schreiben in den Cache, bis sie vorliegt. Eine Zeile ohne Eigentümer wäre nicht wieder zuzuordnen.

## Testbarkeit

Es gibt **kein `androidTest`-Sourceset** und keine Compose-UI-Tests (`app/src/test/CLAUDE.md`). Room-DAOs sind unter reinem JUnit nicht sinnvoll testbar. Konsequenz für den Zuschnitt:

- **Die Merge-Regeln liegen in einer reinen Kotlin-Klasse** ohne Room-, Android- und Retrofit-Bezug und werden vollständig unit-getestet: ODER-Verhalten von `isRead` in beide Richtungen, Papierkorb-Übergangsregel, Bestätigung und Löschen lokaler Absichten, fehlgeschlagener Push, FCM-Teilzeile wird durch die vollständige ersetzt, Verdrängung schont ungepushte Absichten, sowie die Snooze-Umrechnung (verbleibende Stunden aufgerundet, Minimum 1, abgelaufene Absicht verworfen).
- **Der DAO bleibt dünn** — Abfragen und Einfügen, keine Logik.
- **Repository und ViewModel** werden mit gemocktem DAO und gemockter API getestet, wie es `DashboardViewModelDesktopActionTest` vormacht.
- Der Rest — Room-Migration 4 → 5, das tatsächliche Rendern — wird von Hand geprüft, mit `assembleDebug` für den Hilt-Graphen.

## Bewusst weggelassen

- **Kein `WorkManager`-Job für den Abgleich.** Die vorhandenen Auslöser decken jeden Fall ab, in dem der Nutzer die Liste tatsächlich ansieht. Ein periodischer Hintergrundabgleich verbraucht Akku für Daten, die niemand liest.
- **Keine Vektoruhren.** `FileMetadataCRDT`/`VectorClock` existieren im Projekt, lösen aber ein anderes Problem (nebenläufige Schreibzugriffe mehrerer Geräte auf denselben Inhalt). Hier gibt es einen Server als Schiedsrichter und ein monotones plus ein zeitstempelbares Feld.
- **Keine Volltextsuche** im lokalen Bestand. Die Webapp hat sie auch nicht.
