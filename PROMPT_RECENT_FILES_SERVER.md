# Feature: File Activity Tracking & Recent Files API

## Ziel

Implementiere ein serverseitiges **File Activity Tracking System** in BaluHost, das alle relevanten Datei-Aktionen (Öffnen, Bearbeiten, Hochladen, Herunterladen, Löschen, Umbenennen, Verschieben, Teilen) protokolliert und über eine API als "Recent Files" / "Recent Activity" bereitstellt.

Das System ist **server-dominant**: Der Server ist die primäre Quelle der Wahrheit (Single Source of Truth). Der Mobile-Client (Android-App "BaluApp") kann ebenfalls Aktivitäten melden (z.B. "Datei geöffnet", "Datei heruntergeladen"), die der Server entgegennimmt und in seinen Activity-Log integriert. Der Server trackt seinerseits alle Datei-Operationen, die über seine eigenen Endpoints laufen — unabhängig davon, ob der Client gerade erreichbar ist.

---

## Konzept: Bidirektionales Activity-Tracking mit Server-Dominanz

### Warum Server-dominant?

- Der Server sieht **alle** Datei-Operationen — auch die über SMB, WebDAV, andere Clients
- Der Server ist immer verfügbar (im Gegensatz zum Smartphone)
- Cross-Device: Alle Geräte sehen dieselbe Activity-History
- Kein Datenverlust wenn ein Client offline war

### Wie funktioniert die Synchronisation?

```
┌─────────────┐                    ┌──────────────┐
│  BaluApp     │                    │  BaluHost     │
│  (Android)   │                    │  (Server)     │
│              │                    │               │
│  Client      │───── POST ────────▶  Activity Log │
│  Events:     │  /activity/report  │  (DB Table)   │
│  - file.open │                    │               │
│  - file.view │◀── GET ───────────│  Aggregiert:  │
│  - download  │  /activity/recent  │  - Server-Ops │
│              │                    │  - Client-Ops │
│  Local Cache │◀── GET ───────────│  - SMB-Ops    │
│  (Room DB)   │  /activity/feed    │  - WebDAV-Ops │
└─────────────┘                    └──────────────┘
```

1. **Server trackt automatisch:** Jede Datei-Operation die über BaluHost-Endpoints läuft (Upload, Download, Delete, Move, Rename, Permission-Change) erzeugt automatisch einen Activity-Eintrag.

2. **Client meldet zusätzlich:** Wenn der User in der App eine Datei öffnet, ansieht oder herunterlädt, sendet die App diese Events an den Server (`POST /api/activity/report`). Falls offline, werden Events lokal gepuffert und beim nächsten Sync übertragen.

3. **Server aggregiert:** Der Server führt alle Events zusammen — eigene und gemeldete — dedupliziert sie und stellt sie über die API bereit.

4. **Client fragt ab:** Die App holt per `GET /api/activity/recent` die aggregierte, sortierte Liste und zeigt sie im Dashboard an.

---

## Recherche-Anweisung

**WICHTIG: Bevor du mit der Implementierung oder dem Plan beginnst, führe eine Websearch durch:**

Recherchiere wie die folgenden Systeme "Recent Files" / "File Activity Tracking" serverseitig implementieren:
- **Nextcloud** Activity API & Recent Files
- **Synology DSM** File Station Activity Log
- **OneDrive** Microsoft Graph `/recent` Endpoint
- **Seafile** File Activity API

Achte dabei auf:
- Datenbank-Schema für Activity-Logs (welche Felder, Indizes)
- API-Design (Endpoints, Pagination, Filterung)
- Deduplizierung (z.B. wenn eine Datei 10x in 1 Minute geöffnet wird)
- Retention/Cleanup-Strategien (wie lange werden Activities aufbewahrt?)
- Performance-Überlegungen (wie skaliert das bei vielen Dateien?)

---

## Technischer Kontext: BaluHost Server

BaluHost ist der Server (Python/FastAPI), der die NAS-Funktionalität bereitstellt. Die BaluApp (Android/Kotlin) kommuniziert über REST-API mit dem Server.

### Bestehende relevante Server-Endpoints (auf die das Activity-Tracking aufsetzen soll)

Die folgenden Endpoints existieren bereits und sollten **automatisches Activity-Tracking** erhalten:

```
# File Operations (FilesApi)
GET    /api/files/list?path=...          → activity: "file.list" (optional, niedrige Prio)
POST   /api/files/upload                 → activity: "file.upload"
GET    /api/files/download?path=...      → activity: "file.download"
DELETE /api/files/{path}                 → activity: "file.delete"
POST   /api/files/folder                 → activity: "folder.create"
PUT    /api/files/move                   → activity: "file.move"
PUT    /api/files/rename                 → activity: "file.rename"
GET    /api/files/metadata?path=...      → activity: "file.view" (optional)
PUT    /api/files/permissions            → activity: "file.permissions_change"

# Sync Operations (SyncApi)
POST   /api/mobile/sync/folders/{id}/trigger → activity: "sync.triggered"

# Upload (Chunked)
POST   /api/files/upload/chunked/complete → activity: "file.upload"
```

### Bestehende Datenbank-Infos

- BaluHost verwendet SQLAlchemy (oder vergleichbares ORM) — prüfe das im Code
- Es gibt bereits User-Management mit User-IDs und Device-IDs
- Auth läuft über JWT-Tokens mit `X-Device-ID` Header

---

## Anforderungen an die Implementierung

### 1. Datenbank: Activity-Log Tabelle

Erstelle eine `file_activities` Tabelle mit mindestens:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| id | UUID/Integer | Primary Key |
| user_id | FK → users | Wer hat die Aktion ausgeführt |
| device_id | String (nullable) | Von welchem Gerät (null = Server-intern) |
| action_type | Enum/String | Art der Aktion (siehe unten) |
| file_path | String | Pfad der betroffenen Datei |
| file_name | String | Dateiname (für schnelle Anzeige ohne Path-Parsing) |
| is_directory | Boolean | Ordner oder Datei |
| file_size | BigInt (nullable) | Dateigröße zum Zeitpunkt der Aktion |
| mime_type | String (nullable) | MIME-Type |
| metadata | JSON (nullable) | Zusätzliche Infos (z.B. alte/neue Pfade bei Move/Rename) |
| source | Enum | "server" / "client" / "smb" / "webdav" |
| created_at | Timestamp | Zeitpunkt der Aktion |

**Action Types:**
```
file.open       - Datei geöffnet/angesehen (Client-reported)
file.download   - Datei heruntergeladen
file.upload     - Datei hochgeladen
file.edit       - Datei bearbeitet (Inhalt geändert)
file.delete     - Datei gelöscht
file.move       - Datei verschoben (metadata: {from, to})
file.rename     - Datei umbenannt (metadata: {old_name, new_name})
file.share      - Datei geteilt
file.permission - Berechtigungen geändert
folder.create   - Ordner erstellt
sync.triggered  - Sync manuell ausgelöst
```

**Indizes:** `(user_id, created_at DESC)`, `(file_path)`, `(action_type, created_at DESC)`

### 2. API-Endpoints

#### `GET /api/activity/recent`
Liefert die letzten Aktivitäten des Users, aggregiert und dedupliziert.

**Query-Parameter:**
- `limit` (default: 20, max: 100) — Anzahl Einträge
- `offset` (default: 0) — Pagination
- `action_types` (optional) — Komma-separierte Filter, z.B. `file.open,file.download`
- `file_type` (optional) — Filter: `file`, `directory`, `image`, `video`, `document`
- `since` (optional) — ISO-Timestamp, nur Activities nach diesem Zeitpunkt
- `path_prefix` (optional) — Nur Activities in diesem Verzeichnis

**Response:**
```json
{
  "activities": [
    {
      "id": "...",
      "action_type": "file.download",
      "file_path": "/documents/report.pdf",
      "file_name": "report.pdf",
      "is_directory": false,
      "file_size": 1048576,
      "mime_type": "application/pdf",
      "source": "client",
      "device_id": "pixel-7-abc123",
      "metadata": null,
      "created_at": "2026-03-11T14:30:00Z"
    }
  ],
  "total": 142,
  "has_more": true
}
```

#### `GET /api/activity/recent-files`
Convenience-Endpoint: Liefert die zuletzt genutzten **Dateien** (nicht Activities), dedupliziert nach Datei-Pfad. Zeigt pro Datei nur die letzte Aktion.

**Query-Parameter:**
- `limit` (default: 10, max: 50)
- `actions` (default: `file.open,file.download,file.upload,file.edit`) — welche Actions zählen als "genutzt"

**Response:**
```json
{
  "files": [
    {
      "file_path": "/documents/report.pdf",
      "file_name": "report.pdf",
      "is_directory": false,
      "file_size": 1048576,
      "mime_type": "application/pdf",
      "last_action": "file.download",
      "last_action_at": "2026-03-11T14:30:00Z",
      "action_count": 5
    }
  ]
}
```

#### `POST /api/activity/report`
Client meldet Aktivitäten an den Server (Batch-fähig für Offline-Sync).

**Request Body:**
```json
{
  "activities": [
    {
      "action_type": "file.open",
      "file_path": "/photos/vacation.jpg",
      "file_name": "vacation.jpg",
      "is_directory": false,
      "file_size": 2048000,
      "mime_type": "image/jpeg",
      "device_id": "pixel-7-abc123",
      "occurred_at": "2026-03-11T14:25:00Z"
    }
  ]
}
```

**Deduplizierung:** Wenn derselbe User dieselbe Datei mit derselben Action innerhalb von 5 Minuten erneut meldet, wird kein neuer Eintrag erstellt (Update des Timestamps statt Insert).

**Response:**
```json
{
  "accepted": 3,
  "deduplicated": 1,
  "rejected": 0
}
```

### 3. Automatisches Server-Side Tracking

Implementiere einen **Middleware/Decorator/Event-Hook**, der bei bestehenden File-Endpoints automatisch Activity-Einträge erzeugt. Der Code darf die bestehenden Endpoint-Handler NICHT aufblähen — das Tracking soll transparent und entkoppelt sein.

Mögliche Ansätze (wähle den, der am besten zur bestehenden Architektur passt):
- FastAPI Middleware die bestimmte Routes matched
- Dependency-Injection-basierter Decorator
- Event-basiertes System (emit event → listener schreibt Activity)
- Service-Layer Hook im Repository/Service

### 4. Retention & Cleanup

- Activities älter als **90 Tage** werden automatisch gelöscht
- Implementiere einen periodischen Cleanup (Cron-Job, Background-Task, oder DB-Trigger)
- `file.list` und `file.view` Activities haben kürzere Retention (7 Tage), da sie hochfrequent und weniger relevant sind

### 5. Performance-Überlegungen

- Activity-Writes dürfen die File-Operationen NICHT verlangsamen — async/fire-and-forget Pattern verwenden
- Indizes auf den häufigsten Query-Patterns
- Pagination ist Pflicht, keine unbegrenzten Result-Sets
- Bei sehr vielen Activities pro User: Betrachte ob ein materialized view oder eine separate "recent_files" Summary-Tabelle sinnvoll ist

---

## Wichtige Hinweise

- **Prüfe zuerst die bestehende BaluHost-Codebase** gründlich: ORM, DB-Setup, Router-Struktur, Auth-Middleware, bestehende Models
- **Folge den bestehenden Patterns** — nutze dasselbe ORM, dieselbe Projekt-Struktur, denselben Code-Stil
- **Keine Breaking Changes** an bestehenden Endpoints — das Tracking wird transparent hinzugefügt
- **Auth/Permissions:** Nur Activities des eingeloggten Users zurückgeben, nicht die anderer User
- **Teste die Endpoints** mit bestehenden Test-Patterns im Projekt
- **Migration:** Erstelle eine saubere DB-Migration für die neue Tabelle
- Beginne mit der DB und den neuen Endpoints, dann füge das automatische Tracking zu den bestehenden File-Endpoints hinzu

---

## Abgrenzung

Dieses Ticket behandelt NUR die **Server-Seite (BaluHost)**. Die Client-Seite (BaluApp Android) wird separat implementiert. Die API-Spezifikation oben dient als Vertrag zwischen Server und Client.
