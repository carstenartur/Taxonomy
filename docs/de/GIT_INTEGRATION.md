# Git-Integration

Der Taxonomy Architecture Analyzer verwendet **JGit**, um eine vollständige Git-Versionskontrolle für Architecture-DSL-Dokumente bereitzustellen. Das Git-Repository wird in der Anwendungsdatenbank gespeichert (kein Dateisystem erforderlich), was Ihnen Branching, Commit-Historie, Diff, Merge und Cherry-Pick-Funktionen für Ihre Architekturmodelle bietet.

## Inhaltsverzeichnis

- [Überblick](#überblick)
- [Repository-Architektur](#repository-architektur)
- [Branching](#branching)
- [Commit-Historie](#commit-historie)
- [Diff und Vergleich](#diff-und-vergleich)
- [Cherry-Pick](#cherry-pick)
- [Merge](#merge)
- [Konflikterkennung](#konflikterkennung)
- [Materialisierung](#materialisierung)
- [Veralterungsverfolgung](#veralterungsverfolgung)
- [Hypothesen-Lebenszyklus](#hypothesen-lebenszyklus)
- [Commit-Historie-Suche](#commit-historie-suche)
- [REST-API-Endpunkte](#rest-api-endpunkte)
- [Verwandte Dokumentation](#verwandte-dokumentation)

---

## Überblick

Architecture-DSL-Dokumente (`.taxdsl`-Dateien) werden in einem JGit-DFS-Repository (Distributed File System) gespeichert, das durch HSQLDB-Tabellen (`git_packs`, `git_reflog`) unterstützt wird. Jede Änderung an der DSL erzeugt einen Git-Commit mit Autor, Zeitstempel und Commit-Nachricht — und bietet damit eine vollständige Audit-Spur.

Der Git-Zustand wird über die UI-Statusleiste und die REST-API bereitgestellt, sodass Sie den Zustand des Repositorys überwachen, veraltete Projektionen erkennen und Merge-/Cherry-Pick-Operationen vor der Ausführung in der Vorschau betrachten können.

---

## Repository-Architektur

```
┌─────────────────────┐
│  DslGitRepository    │   JGit-DFS-Repository
│  (datenbankgestützt) │   Tabellen: git_packs, git_reflog
├─────────────────────┤
│  Branches            │   draft (Standard), main, feature/*
│  Commits             │   Vollständiger SHA, Autor, Nachricht, Zeitstempel
│  Objekte             │   Git-Blobs, Trees, Commits in der DB gespeichert
└─────────────────────┘
         │
         ▼
┌─────────────────────┐
│  RepositoryStateService  │   Verfolgt Projektions-/Index-Veralterung
│  ConflictDetectionService│   Vorschau von Merge/Cherry-Pick
│  GitStateController      │   REST-API für Zustandsabfragen
└─────────────────────┘
```

Wichtige Methoden von `DslGitRepository`:

| Methode | Zweck |
|---|---|
| `commitDsl(content, message, author)` | Einen neuen Commit erstellen |
| `getDslAtHead(branch)` | Aktuellen DSL-Inhalt lesen |
| `getDslAtCommit(sha)` | DSL zu einem bestimmten Commit lesen |
| `getDslHistory(branch, limit)` | Commit-Historie auflisten |
| `listBranches()` | Alle Branch-Namen auflisten |
| `createBranch(name, startPoint)` | Einen neuen Branch erstellen |
| `diffBetween(fromSha, toSha)` | Semantischer Diff zwischen Commits |
| `diffBranches(from, to)` | Diff zwischen Branch-HEADs |
| `textDiff(fromSha, toSha)` | Unified-Text-Diff |
| `cherryPick(commitId, targetBranch)` | Einen einzelnen Commit portieren |
| `merge(fromBranch, intoBranch)` | Drei-Wege-Merge |

---

## Branching

Der Standard-Branch ist `draft`. Sie können Feature-Branches erstellen, um mit Architekturänderungen zu experimentieren, ohne den Haupt-Branch zu beeinflussen.

```
POST /api/dsl/branches
{
  "name": "feature/new-service",
  "startPoint": "draft"
}
```

Der aktive Branch für die Materialisierung wird über die Einstellung `dsl.default-branch` konfiguriert (siehe [Einstellungen](PREFERENCES.md)).

---

## Commit-Historie

Jede DSL-Änderung erzeugt einen Git-Commit mit:

- **SHA** — Eindeutiger Commit-Bezeichner
- **Autor** — Authentifizierter Benutzer, der die Änderung vorgenommen hat
- **Nachricht** — Beschreibung der Änderung
- **Zeitstempel** — Wann der Commit erstellt wurde

Commit-Historie anzeigen:

```
GET /api/dsl/history?branch=draft&limit=20
```

---

## Diff und Vergleich

Zwei Diff-Modi stehen zur Verfügung:

| Modus | Endpunkt | Ausgabe |
|---|---|---|
| **Semantisch** | `GET /api/dsl/diff?from={sha}&to={sha}` | Strukturiertes JSON mit hinzugefügten, entfernten und geänderten Elementen und Beziehungen |
| **Unified-Text** | `GET /api/dsl/text-diff?from={sha}&to={sha}` | Standard-Unified-Diff-Format (Patch) |

Sie können auch zwischen Branches vergleichen:

```
GET /api/dsl/diff-branches?from=draft&to=main
```

---

## Cherry-Pick

Einen bestimmten Commit von einem Branch auf einen anderen portieren:

```
POST /api/dsl/cherry-pick
{
  "commitId": "abc1234...",
  "targetBranch": "draft"
}
```

Die Operation verwendet intern Drei-Wege-Merge-Logik. Verwenden Sie zuerst den Vorschau-Endpunkt, um auf Konflikte zu prüfen (siehe [Konflikterkennung](#konflikterkennung)).

---

## Merge

Zwei Branches mittels Drei-Wege-Merge zusammenführen:

```
POST /api/dsl/merge
{
  "fromBranch": "feature/new-service",
  "intoBranch": "draft"
}
```

Die Merge-Strategie ist RECURSIVE (Standard-Git-Verhalten). Fast-Forward-Merges werden durchgeführt, wenn der Ziel-Branch ein direkter Vorgänger des Quell-Branches ist.

---

## Konflikterkennung

Vor der Ausführung eines Merge oder Cherry-Pick können Sie die Operation in der Vorschau betrachten, um auf Konflikte zu prüfen:

### Merge-Vorschau

```
GET /api/dsl/merge/preview?from=feature/new-service&into=draft
```

Antwort:

```json
{
  "canMerge": true,
  "fromBranch": "feature/new-service",
  "intoBranch": "draft",
  "fromCommit": "abc1234...",
  "intoCommit": "def5678...",
  "alreadyMerged": false,
  "fastForwardable": true,
  "warnings": []
}
```

### Cherry-Pick-Vorschau

```
GET /api/dsl/cherry-pick/preview?commitId=abc1234&branch=draft
```

Antwort:

```json
{
  "canCherryPick": true,
  "commitId": "abc1234...",
  "targetBranch": "draft",
  "targetCommit": "def5678...",
  "warnings": []
}
```

### Sicherheitsprüfung für Operationen

```
GET /api/dsl/operation/check?branch=draft
```

Der `RepositoryStateGuard` prüft, ob eine Schreiboperation auf dem gegebenen Branch sicher ausgeführt werden kann.

---

## Materialisierung

DSL-Dokumente werden in die Anwendungsdatenbank **materialisiert**. Dadurch werden `TaxonomyRelation`-Entitäten aus DSL-Beziehungen erstellt, die im Graph Explorer, in den Beziehungsvorschlägen und in der Architekturansicht sichtbar werden.

Zwei Materialisierungsmodi stehen zur Verfügung:

| Modus | Endpunkt | Beschreibung |
|---|---|---|
| **Vollständig** | `POST /api/dsl/materialize` | Ersetzt alle Beziehungen durch DSL-Inhalte |
| **Inkrementell** | `POST /api/dsl/materialize-incremental` | Wendet nur das Delta zwischen zwei Versionen an |

Nach der Materialisierung zeichnet der `RepositoryStateService` den Projektions-Commit auf, um zu verfolgen, ob die Datenbank mit dem Git-HEAD synchron ist.

---

## Veralterungsverfolgung

Das System verfolgt, ob die Datenbankprojektion und der Suchindex mit dem Git-HEAD synchron sind:

| Feld | Bedeutung |
|---|---|
| `projectionStale` | Datenbankbeziehungen weichen vom Git-HEAD ab |
| `indexStale` | Suchindex weicht vom Git-HEAD ab |

**Veralterungslogik:** Wenn der SHA des zuletzt materialisierten Commits mit dem SHA des aktuellen HEAD-Commits übereinstimmt, ist die Projektion **nicht veraltet**. Andernfalls ist sie veraltet und sollte erneut materialisiert werden.

Veralterung abfragen:

```
GET /api/git/stale?branch=draft
```

Antwort:

```json
{
  "projectionStale": false,
  "indexStale": false
}
```

Die Benutzeroberfläche pollt `/api/git/state` alle 10 Sekunden, um einen Statusindikator anzuzeigen, wenn die Projektion veraltet ist.

---

## Hypothesen-Lebenszyklus

Beziehungen, die während der LLM-Analyse generiert werden, werden als **Hypothesen** gespeichert — vorläufige Beziehungen, die eine menschliche Überprüfung erfordern, bevor sie dauerhaft werden:

```
PENDING  →  ACCEPTED  (erstellt TaxonomyRelation)
         →  REJECTED  (als abgelehnt markiert)
         →  APPLIED   (nur für die Sitzung, nicht persistiert)
```

Die Hypothesen-API (`/api/dsl/hypotheses`) ermöglicht das Abfragen, Akzeptieren und Ablehnen von Hypothesen, wobei für jede unterstützende Nachweise verfügbar sind.

---

## Commit-Historie-Suche

Die Commit-Historie wird in Hibernate Search für die Volltextsuche indexiert. Sie können:

- Über alle Commit-Nachrichten und Änderungsinhalte suchen
- Alle Commits finden, die ein bestimmtes Element betroffen haben
- Alle Commits finden, die eine bestimmte Beziehung betroffen haben
- Aggregierte Änderungshistorie für ein Element anzeigen

---

## REST-API-Endpunkte

### Git-Zustand

| Methode | Endpunkt | Beschreibung |
|---|---|---|
| `GET` | `/api/git/state?branch=draft` | Vollständiger Repository-Zustandsschnappschuss |
| `GET` | `/api/git/projection?branch=draft` | Aktualität von Projektion/Index |
| `GET` | `/api/git/branches?branch=draft` | Alle Branches mit HEAD-Commits |
| `GET` | `/api/git/stale?branch=draft` | Schnelle Veralterungsprüfung |

### DSL-Operationen

| Methode | Endpunkt | Beschreibung |
|---|---|---|
| `POST` | `/api/dsl/materialize` | Vollständige Materialisierung |
| `POST` | `/api/dsl/materialize-incremental` | Inkrementelle Materialisierung |
| `POST` | `/api/dsl/merge` | Branches zusammenführen |
| `POST` | `/api/dsl/cherry-pick` | Einen Commit cherry-picken |
| `GET` | `/api/dsl/merge/preview` | Merge-Ergebnis in der Vorschau anzeigen |
| `GET` | `/api/dsl/cherry-pick/preview` | Cherry-Pick-Ergebnis in der Vorschau anzeigen |
| `GET` | `/api/dsl/operation/check` | Sicherheitsprüfung für Schreiboperationen |
| `GET` | `/api/dsl/merge/conflicts?from=X&into=Y` | Merge-Konfliktdetails (DSL-Inhalt beider Seiten) |
| `POST` | `/api/dsl/merge/resolve?fromBranch=X&intoBranch=Y` | Manuell aufgelösten Merge-Inhalt committen |
| `GET` | `/api/dsl/cherry-pick/conflicts?commitId=X&targetBranch=Y` | Cherry-Pick-Konfliktdetails |
| `POST` | `/api/dsl/cherry-pick/resolve?commitId=X&targetBranch=Y` | Manuell aufgelösten Cherry-Pick-Inhalt committen |
| `DELETE` | `/api/dsl/branch?name=X` | Einen Branch löschen (geschützte Branches: draft, accepted, main) |

### Arbeitsbereich-Synchronisation

| Methode | Endpunkt | Beschreibung |
|---|---|---|
| `POST` | `/api/workspace/sync-from-shared?userBranch=X` | Gemeinsamen Branch in Benutzer-Branch zusammenführen (Pull) |
| `POST` | `/api/workspace/publish?userBranch=X` | Benutzer-Branch in gemeinsamen Branch zusammenführen (Push) |
| `GET` | `/api/workspace/sync-state` | Synchronisationsstatus abrufen (UP_TO_DATE, BEHIND, AHEAD, DIVERGED) |
| `POST` | `/api/workspace/resolve-diverged?strategy=X&userBranch=Y` | Divergierten Zustand auflösen (Strategien: MERGE, KEEP_MINE, TAKE_SHARED) |

Siehe [API-Referenz](API_REFERENCE.md) für vollständige Anfrage-/Antwortschemata.

---

## Verwandte Dokumentation

- [Benutzerhandbuch](USER_GUIDE.md) — Abschnitt Architecture DSL (§11g)
- [Architektur](ARCHITECTURE.md) — DSL-Speicherarchitektur
- [Konzepte](CONCEPTS.md) — DSL, Hypothesen und das kanonische Modell
- [Einstellungen](PREFERENCES.md) — Konfiguration von `dsl.default-branch` und Remote-Push-Einstellungen
- [Framework-Import](FRAMEWORK_IMPORT.md) — Wie importierte Dateien als DSL-Dokumente gespeichert werden
