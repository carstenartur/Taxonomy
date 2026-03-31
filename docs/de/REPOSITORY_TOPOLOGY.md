# Repository-Topologie & Arbeitsbereich-Bereitstellung

## Überblick

Der Taxonomy Architecture Analyzer verwendet ein **systemverwaltetes zentrales Repository**-Modell
mit **verzögerter Arbeitsbereich-Bereitstellung** für die Zusammenarbeit mehrerer Benutzer.
Dieses Design trennt den gemeinsamen teamweiten Architekturzustand von den individuellen
Arbeitsbereichen der Benutzer und ermöglicht sicheres paralleles Bearbeiten ohne Störungen.

## Topologiemodi

Das System unterstützt zwei Topologiemodi, die definieren, wie das zentrale Repository
sich zu externen Quellen verhält:

### INTERNAL_SHARED (Standard)

Die Anwendung hostet das gemeinsame Integrations-Repository intern. Alle Benutzer
synchronisieren mit diesem internen Repository. Dies ist der Standardmodus und
erfordert keine externe Git-Einrichtung.

Wenn `DslGitRepositoryFactory` konfiguriert ist (Standard), erhält jeder bereitgestellte
Arbeitsbereich ein eigenes logisch getrenntes Git-Repository in der gleichen Datenbank,
identifiziert durch ein eindeutiges `repositoryName`-Präfix (`ws-{workspaceId}`). Das
System-Repository verwendet den bekannten Namen `taxonomy-dsl`.

```
┌─────────────────────────────────────────────────────┐
│                    HSQLDB (git_packs-Tabelle)         │
│                                                       │
│  ┌───────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ System-Repo   │  │ Alice-Repo   │  │ Bob-Repo   │ │
│  │ "taxonomy-dsl"│  │ "ws-abc-123" │  │ "ws-def-456│ │
│  │ Branch: draft │  │ Branch: main │  │ Branch: main│ │
│  └───────────────┘  └──────────────┘  └────────────┘ │
└──────────────────────────────────────────────────────┘
```

### EXTERNAL_CANONICAL

Ein externes Git-Repository dient als kanonische zentrale Quelle. Die Anwendung
synchronisiert sich über `ExternalGitSyncService` mittels JGit's `Transport.open()`
für Fetch/Push-Operationen mit diesem externen Repository. Dieser Modus ist für die
Integration mit bestehender Enterprise-Git-Infrastruktur vorgesehen (Gitea, GitHub, GitLab).

```
┌──────────────────────────────────────────────────────┐
│                    HSQLDB (git_packs-Tabelle)          │
│                                                        │
│  ┌───────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │ System-Repo   │  │ Alice-Repo   │  │ Bob-Repo   │  │
│  │ "taxonomy-dsl"│  │ "ws-abc-123" │  │ "ws-def-456│  │
│  │ Branch: draft │  │ Branch: main │  │ Branch: main│  │
│  └───────┬───────┘  └──────────────┘  └────────────┘  │
└──────────┼────────────────────────────────────────────┘
           │ fetch/push
           ▼
┌──────────────────────┐
│   Gitea / GitHub     │
│   Remote-Repository  │
└──────────────────────┘
```

#### Externe Sync REST-API

| Endpunkt | Methode | Beschreibung |
|----------|---------|-------------|
| `/api/workspace/external/fetch` | POST | Alle Branches vom externen Remote abrufen |
| `/api/workspace/external/push` | POST | Shared-Branch zum externen Remote pushen |
| `/api/workspace/external/full-sync` | POST | Fetch + Merge in den Shared-Branch |
| `/api/workspace/external/status` | GET | Aktueller Sync-Status und Zeitstempel |
| `/api/workspace/external/configure` | PUT | Externe URL und Topologiemodus setzen |

## Lebenszyklus der Arbeitsbereich-Bereitstellung

Wenn ein neuer Benutzer auf die Anwendung zugreift, durchläuft sein Arbeitsbereich
einen Bereitstellungs-Lebenszyklus:

```
Anmeldung / Erster Zugriff
    │
    ▼
Arbeitsbereich-Metadaten erstellt
    Status: NOT_PROVISIONED
    │
    ▼
Benutzer löst "Arbeitsbereich vorbereiten" aus
    Status: PROVISIONING
    │
    ├── Erfolg → Status: READY
    │     └── Persönlicher Branch erstellt (z.B. alice/workspace)
    │
    └── Fehler → Status: FAILED
          └── Fehlermeldung für erneuten Versuch gespeichert
```

### Bereitstellungszustände

| Status | Beschreibung |
|--------|-------------|
| `NOT_PROVISIONED` | Arbeitsbereich-Metadaten existieren, aber es wurde noch kein Git-Branch erstellt |
| `PROVISIONING` | Branch-Erstellung läuft |
| `READY` | Arbeitsbereich ist vollständig bereitgestellt und einsatzbereit |
| `FAILED` | Bereitstellung fehlgeschlagen; siehe Fehlermeldung für Details |

## System-Repository

Das System erstellt beim Start automatisch einen primären Repository-Datensatz mit:
- **Topologiemodus**: `INTERNAL_SHARED`
- **Standard-Branch**: `draft`
- **Anzeigename**: "Shared Architecture Repository"

Dieser Datensatz wird von `SystemRepositoryService` verwaltet und stellt den
konfigurierbaren Namen des gemeinsamen Branches bereit, der von allen
Synchronisierungsoperationen verwendet wird.

## REST-API-Endpunkte

### GET /api/workspace/provisioning-status

Gibt den aktuellen Bereitstellungszustand des Benutzer-Arbeitsbereichs zurück.

**Antwort:**
```json
{
  "status": "READY",
  "topologyMode": "INTERNAL_SHARED",
  "sourceRepository": "uuid-des-system-repos",
  "error": null
}
```

### POST /api/workspace/provision

Erstellt den persönlichen Branch des Benutzers aus dem gemeinsamen Repository.

**Antwort:**
```json
{
  "status": "READY",
  "branch": "alice/workspace",
  "baseBranch": "draft"
}
```

### GET /api/workspace/topology

Gibt den Repository-Topologiemodus und Informationen zur gemeinsamen Quelle zurück.

**Antwort:**
```json
{
  "mode": "INTERNAL_SHARED",
  "sharedBranch": "draft",
  "systemRepositoryId": "uuid-des-system-repos",
  "displayName": "Shared Architecture Repository"
}
```

## Benutzerfreundliche Terminologie

Die folgende Terminologie wird in der Benutzeroberfläche verwendet, um
rohe Git-Konzepte zu vermeiden:

| Technischer Begriff | Englisch (UI) | Deutsch (UI) |
|-------------------|-------------|-------------|
| Zentrales Repository | Shared Space | Gemeinsamer Bereich |
| Benutzer-Arbeitsbereich | My Workspace | Mein Arbeitsbereich |
| Branch / Variante | Variant | Variante |
| Arbeitsbereich bereitstellen | Prepare Workspace | Arbeitsbereich vorbereiten |
| Vom Team synchronisieren | Sync from Team | Vom Team synchronisieren |
| Änderungen veröffentlichen | Publish for Team | Für Team veröffentlichen |
| Vergleichen | Compare | Vergleichen |
| Zusammenführen | Integrate | Integrieren |
| Cherry-Pick | Apply Single Change | Einzeländerung übernehmen |
| HEAD | Current Version | Aktuelle Version |

Begriffe wie `fork`, `fetch`, `refs`, `rebase` werden **niemals** in der
Standard-Benutzeroberfläche angezeigt.

## Architektur

```
SystemRepository (JPA-Entität — Tabelle: system_repository)
  ├── id: Long (automatisch generierter Primärschlüssel)
  ├── repositoryId: String (eindeutig, nicht null)
  ├── displayName: String
  ├── topologyMode: RepositoryTopologyMode (INTERNAL_SHARED | EXTERNAL_CANONICAL, nicht null)
  ├── defaultBranch: String (Standard "draft", nicht null)
  ├── externalUrl: String (URL für EXTERNAL_CANONICAL-Modus)
  ├── externalAuthToken: String (Authentifizierungs-Token für EXTERNAL_CANONICAL)
  ├── lastFetchAt: Instant (Zeitstempel des letzten Abrufs vom externen Remote)
  ├── lastPushAt: Instant (Zeitstempel des letzten Pushs zum externen Remote)
  ├── lastFetchCommit: String (SHA des zuletzt abgerufenen Remote-HEAD)
  ├── primaryRepo: boolean (Standard true, nicht null)
  └── createdAt: Instant (nicht null)

UserWorkspace (JPA-Entität — Tabelle: user_workspace)
  ├── id: Long (automatisch generierter Primärschlüssel)
  ├── workspaceId: String (eindeutig, nicht null)
  ├── username: String (nicht null)
  ├── displayName: String
  ├── currentBranch: String (Standard "draft", nicht null)
  ├── baseBranch: String (Standard "draft")
  ├── shared: boolean (Standard false, nicht null)
  ├── createdAt: Instant (nicht null)
  ├── lastAccessedAt: Instant
  ├── provisioningStatus: WorkspaceProvisioningStatus (NOT_PROVISIONED | PROVISIONING | READY | FAILED, Standard READY, nicht null)
  ├── topologyMode: RepositoryTopologyMode (spiegelt SystemRepository, Standard INTERNAL_SHARED, nicht null)
  ├── sourceRepositoryId: String (Verweis auf SystemRepository.repositoryId)
  ├── baseCommit: String (Git-SHA zum Bereitstellungszeitpunkt)
  ├── currentCommit: String (Git-SHA des aktuellen HEAD)
  ├── syncTargetBranch: String (konfigurierbares Sync-Ziel)
  ├── provisionedAt: Instant
  ├── provisioningError: String
  ├── description: String (max. 500 Zeichen)
  ├── archived: boolean (Standard false, nicht null)
  └── isDefault: boolean (Standard false, nicht null)

SyncState (JPA-Entität — Tabelle: sync_state)
  ├── id: Long (automatisch generierter Primärschlüssel)
  ├── username: String (nicht null)
  ├── workspaceId: String (nicht null)
  ├── lastSyncedCommitId: String
  ├── lastSyncTimestamp: Instant
  ├── lastPublishedCommitId: String
  ├── lastPublishTimestamp: Instant
  ├── syncStatus: String (Standard "UP_TO_DATE", nicht null)
  ├── unpublishedCommitCount: int (Standard 0, nicht null)
  ├── createdAt: Instant (nicht null)
  └── updatedAt: Instant

WorkspaceProjection (JPA-Entität — Tabelle: workspace_projection)
  ├── id: Long (automatisch generierter Primärschlüssel)
  ├── username: String (nicht null)
  ├── workspaceId: String (nicht null)
  ├── projectionCommitId: String
  ├── projectionBranch: String
  ├── projectionTimestamp: Instant
  ├── indexCommitId: String
  ├── indexTimestamp: Instant
  ├── stale: boolean (Standard false, nicht null)
  ├── createdAt: Instant (nicht null)
  └── updatedAt: Instant

ContextHistoryRecord (JPA-Entität — Tabelle: context_history_record)
  ├── id: Long (automatisch generierter Primärschlüssel)
  ├── username: String (nicht null)
  ├── fromContextId: String
  ├── toContextId: String
  ├── fromBranch: String
  ├── toBranch: String
  ├── fromCommitId: String
  ├── toCommitId: String
  ├── reason: String
  ├── originContextId: String
  └── createdAt: Instant (nicht null)

DslGitRepositoryFactory
  ├── getSystemRepository() → gemeinsames System-Repo ("taxonomy-dsl")
  ├── getWorkspaceRepository(workspaceId) → pro-Workspace-Repo ("ws-{id}")
  ├── resolveRepository(WorkspaceContext) → kontextbasierte Auflösung
  └── evict(workspaceId) → Cache-Bereinigung bei Löschung

Service Repository Routing:
  Alle Services lösen das korrekte Repository über explizite
  WorkspaceContext-Parameter auf, die vom Aufrufer übergeben werden.
  Nur die DslOperationsFacade (Request/UI-Schicht) ruft
  resolveCurrentContext() aus dem SecurityContextHolder auf.
  Backend-Services akzeptieren WorkspaceContext als Methodenparameter:
  - SHARED-Kontext → System-Repository ("taxonomy-dsl")
  - Workspace-Kontext → pro-Workspace-Repo ("ws-{id}")
  Factory-Modus (pro-Workspace-Repos) ist der Produktions-Standard.
  Shared-Repo + Branch-Isolation ist nur Legacy-/Test-Modus.
  Repo-fähige Services: CommitIndexService, RepositoryStateService,
  ContextNavigationService, ConflictDetectionService,
  WorkspaceProjectionService, ContextCompareService,
  SelectiveTransferService, DslOperationsFacade.

ExternalGitSyncService
  ├── fetchFromExternal() → JGit Transport.fetch() vom Remote
  ├── pushToExternal(branch) → JGit Transport.push() zum Remote
  ├── fullSync(username) → Fetch + Merge in Shared-Branch
  └── getStatus() → gibt ExternalSyncStatus zurück (externalEnabled, externalUrl,
                     lastFetchAt, lastPushAt, lastFetchCommit)

SystemRepositoryService
  ├── @PostConstruct → stellt sicher, dass primäres Repo existiert
  ├── getPrimaryRepository() → gibt SystemRepository zurück
  └── getSharedBranch() → konfigurierbarer Branch-Name

WorkspaceManager
  ├── activeWorkspaces: ConcurrentMap<String, UserWorkspaceState>  (Schlüssel: workspaceId)
  ├── activeWorkspaceByUser: ConcurrentMap<String, String>         (username → workspaceId)
  ├── getOrCreateWorkspace() → In-Memory-Status (unverändert)
  ├── findActiveWorkspace(username) → sucht aktiven Workspace über activeWorkspaceByUser
  ├── findUserWorkspace(username) → Legacy-Einzel-Workspace-Entitätssuche
  └── provisionWorkspaceRepository() → erstellt pro-Workspace-Repo (Factory) oder Branch (Legacy)

WorkspaceContextResolver
  Die Kontextauflösung verwendet eine zweistufige Suche:
  1. workspaceManager.findActiveWorkspace(username) → Multi-Workspace-fähige Suche
  2. Falls null: workspaceManager.findUserWorkspace(username) → Legacy-Fallback
  Falls keiner einen bereitgestellten Workspace zurückgibt, wird auf WorkspaceContext.SHARED zurückgefallen.
```

## Datenisolationsmodell

Drei Entitäten tragen Workspace-spezifische Daten über zwei JPA-Spalten:

| Entität | workspace_id-Spalte | owner_username-Spalte |
|---------|--------------------|-----------------------|
| `TaxonomyRelation` | ✅ (indexiert) | ✅ (indexiert) |
| `RelationHypothesis` | ✅ | ✅ |
| `RelationProposal` | ✅ | ✅ |

**OR-null-Abfragemuster:** Abfragen für Workspace-spezifische Daten verwenden einen OR-null-Filter,
sodass gemeinsame (workspace-neutrale) Datensätze neben workspace-privaten Datensätzen stets sichtbar bleiben:

```sql
WHERE (workspace_id = :workspaceId OR workspace_id IS NULL)
  AND (owner_username = :username  OR owner_username IS NULL)
```

Zeilen ohne `workspace_id` (erstellt vor der Multi-Workspace-Funktion oder absichtlich geteilt)
bleiben damit in jedem Workspace-Kontext sichtbar.

---

## Verwandte Dokumentation

- [Workspace- & Versionierungshandbuch](WORKSPACE_VERSIONING.md) — benutzerorientierter Leitfaden für die Workspace-UI
- [GIT_INTEGRATION](GIT_INTEGRATION.md) — technische Details der JGit-gestützten DSL-Speicherung, Branching und Merge
- [Architektur](ARCHITECTURE.md) — DSL-Speicherarchitektur und Modulübersicht
