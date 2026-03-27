# Taxonomy Architecture Analyzer — API-Referenz

> **📌 Diese Referenz richtet sich an Entwickler und Integratoren.** Für die tägliche Arbeit
> verwenden Sie die grafische Oberfläche (siehe [Benutzerhandbuch](USER_GUIDE.md)).

Alle Endpunkte erfordern **HTTP-Basic-Authentifizierung**, sofern nicht als öffentlich gekennzeichnet.
CSRF ist für `/api/**` deaktiviert — REST-Clients benötigen keine CSRF-Tokens.

> **⚠️ Sicherheit:** Die folgenden Beispiele verwenden `-u admin:admin` (das Standard-Entwicklungspasswort).
> Für jede nicht-lokale Bereitstellung setzen Sie `TAXONOMY_ADMIN_PASSWORD`, bevor Sie die Anwendung exponieren.
> Siehe [Sicherheit](SECURITY.md) für Details.

Interaktive Dokumentation: [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html) (wenn die Anwendung läuft).

> End-to-End-Workflow-Beispiele mit cURL finden Sie unter [cURL-Beispiele für Automatisierung & Integration](CURL_EXAMPLES.md).

---

## Inhaltsverzeichnis

- [Authentifizierung](#authentifizierung)
- [Analyse](#analyse)
- [Suche](#suche)
- [Graph-Exploration](#graph-exploration)
- [Beziehungen](#beziehungen)
- [Beziehungsvorschläge](#beziehungsvorschläge)
- [Export](#export)
- [Berichte](#berichte)
- [Lückenanalyse & Empfehlungen](#lückenanalyse--empfehlungen)
- [Architektur-DSL](#architektur-dsl)
- [Dokumentimport & Provenienz](#dokumentimport--provenienz)
- [Administration](#administration)
- [Fehlerantworten](#fehlerantworten)

---

## Authentifizierung

Alle `/api/**`-Endpunkte erfordern Authentifizierung über **HTTP Basic**:

```bash
curl -u admin:admin http://localhost:8080/api/taxonomy
```

Standard-Anmeldedaten: `admin` / `admin` (konfigurierbar über `TAXONOMY_ADMIN_PASSWORD`).

Details zur rollenbasierten Zugriffskontrolle finden Sie unter [SECURITY.md](SECURITY.md).

---

## Analyse

### Taxonomie-Knoten gegen eine Anforderung bewerten

```
POST /api/analyze
```

**Anfrage:**

```bash
curl -u admin:admin -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"businessText": "Provide integrated communication services for hospital staff", "includeArchitectureView": true}'
```

**Antwort (200):**

```json
{
  "scores": { "CP-1023": 92, "CO-1011": 88, "CR-1047": 81 },
  "reasons": { "CP-1023": "Directly relevant to communications capability" },
  "architectureView": {
    "layers": [ ... ],
    "relations": [ ... ]
  }
}
```

| Parameter | Typ | Erforderlich | Beschreibung |
|---|---|---|---|
| `businessText` | string | ✅ | Freitext-Anforderung zur Analyse |
| `includeArchitectureView` | boolean | — | Architekturansicht generieren (Standard: false) |
| `maxArchitectureNodes` | integer | — | Maximale Anzahl von Knoten in der Architekturansicht |

### Streaming-Analyse (Server-Sent Events)

```
GET /api/analyze-stream?businessText=voice+communications
```

Gibt Echtzeit-Fortschrittsereignisse als SSE zurück. Jedes Ereignis enthält Teilbewertungen.

### Blatt-Begründung

```
POST /api/justify-leaf
```

Generiert eine natürlichsprachliche Erklärung, warum ein bestimmter Blattknoten die jeweilige Bewertung erhalten hat.

**Anfrage:**

```bash
curl -u admin:admin -X POST http://localhost:8080/api/justify-leaf \
  -H "Content-Type: application/json" \
  -d '{"nodeCode": "CR-1047", "businessText": "Secure voice communications", "scores": {"CR-1047": 87}}'
```

---

## Suche

### Volltextsuche

```bash
curl -u admin:admin "http://localhost:8080/api/search?q=voice+communications&maxResults=20"
```

### Semantische Suche (erfordert aktivierte Embeddings)

```bash
curl -u admin:admin "http://localhost:8080/api/search/semantic?q=secure+communications&maxResults=20"
```

### Hybride Suche (Reciprocal Rank Fusion)

```bash
curl -u admin:admin "http://localhost:8080/api/search/hybrid?q=voice+communications&maxResults=20"
```

### Graph-semantische Suche

```bash
curl -u admin:admin "http://localhost:8080/api/search/graph?q=communications&maxResults=20"
```

### Ähnliche Knoten finden

```bash
curl -u admin:admin "http://localhost:8080/api/search/similar/CR-1047?topK=5"
```

---

## Graph-Exploration

### Upstream-Nachbarn

```bash
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/upstream?maxHops=2"
```

**Antwort (200):**

```json
{
  "sourceNode": "CR-1047",
  "sourceTitle": "Infrastructure Services",
  "nodes": [
    { "code": "CO-1011", "title": "Communications Access Services", "distance": 1 }
  ]
}
```

### Downstream-Nachbarn

```bash
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/downstream?maxHops=2"
```

### Ausfallwirkungsanalyse

```bash
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/failure-impact?maxHops=3"
```

### Anforderungswirkungsanalyse

```bash
curl -u admin:admin -X POST http://localhost:8080/api/graph/impact \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CR-1047": 87, "CP-1023": 92}, "businessText": "Secure voice", "maxHops": 2}'
```

---

## Beziehungen

### Alle Beziehungen auflisten

```bash
curl -u admin:admin "http://localhost:8080/api/relations"
curl -u admin:admin "http://localhost:8080/api/relations?type=REALIZES"
```

### Beziehungen für einen Knoten abrufen

```bash
curl -u admin:admin "http://localhost:8080/api/node/CP-1023/relations"
```

### Beziehung erstellen (erfordert ARCHITECT- oder ADMIN-Rolle)

```bash
curl -u admin:admin -X POST http://localhost:8080/api/relations \
  -H "Content-Type: application/json" \
  -d '{"sourceCode": "CP-1023", "targetCode": "CR-1047", "relationType": "REALIZES"}'
```

### Beziehung löschen (erfordert ARCHITECT- oder ADMIN-Rolle)

```bash
curl -u admin:admin -X DELETE http://localhost:8080/api/relations/42
```

---

## Beziehungsvorschläge

### KI-Vorschläge generieren

```bash
curl -u admin:admin -X POST http://localhost:8080/api/proposals/propose \
  -H "Content-Type: application/json" \
  -d '{"sourceCode": "CR-1047", "relationType": "SUPPORTS"}'
```

### Ausstehende Vorschläge auflisten

```bash
curl -u admin:admin "http://localhost:8080/api/proposals/pending"
```

### Akzeptieren / Ablehnen / Zurücksetzen

```bash
curl -u admin:admin -X POST http://localhost:8080/api/proposals/42/accept
curl -u admin:admin -X POST http://localhost:8080/api/proposals/42/reject
curl -u admin:admin -X POST http://localhost:8080/api/proposals/42/revert
```

### Massenaktion

```bash
curl -u admin:admin -X POST http://localhost:8080/api/proposals/bulk \
  -H "Content-Type: application/json" \
  -d '{"ids": [42, 43, 44], "action": "ACCEPT"}'
```

---

## Export

### ArchiMate-XML

```bash
curl -u admin:admin -X POST http://localhost:8080/api/diagram/archimate \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}}' \
  -o architecture.xml
```

### Visio (.vsdx)

```bash
curl -u admin:admin -X POST http://localhost:8080/api/diagram/visio \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}}' \
  -o architecture.vsdx
```

### Mermaid

```bash
curl -u admin:admin -X POST http://localhost:8080/api/diagram/mermaid \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}}'
```

### JSON (Analyse speichern/laden)

```bash
# Exportieren
curl -u admin:admin -X POST http://localhost:8080/api/scores/export \
  -H "Content-Type: application/json" \
  -d '{"requirement": "Secure voice comms", "scores": {"CP-1023": 92}}'

# Importieren
curl -u admin:admin -X POST http://localhost:8080/api/scores/import \
  -H "Content-Type: application/json" \
  -d @saved-analysis.json
```

---

## Berichte

> 💡 **GUI:** Klicken Sie auf die Export-Buttons (**📄 Markdown**, **🌐 HTML**, **📋 DOCX**) in der Architekturansicht.

Analyseergebnisse als formatierte Berichte exportieren:

```bash
# Markdown
curl -u admin:admin -X POST http://localhost:8080/api/report/markdown \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92}, "businessText": "Secure voice comms", "minScore": 50}'

# HTML
curl -u admin:admin -X POST http://localhost:8080/api/report/html ...

# DOCX
curl -u admin:admin -X POST http://localhost:8080/api/report/docx ... -o report.docx

# JSON
curl -u admin:admin -X POST http://localhost:8080/api/report/json ...
```

---

## Lückenanalyse & Empfehlungen

### Lückenanalyse

```bash
curl -u admin:admin -X POST http://localhost:8080/api/gap/analyze \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}, "businessText": "Secure voice"}'
```

**Antwort:** Identifiziert fehlende Beziehungen und unvollständige Architekturmuster.

### Empfehlungen

```bash
curl -u admin:admin -X POST http://localhost:8080/api/recommend \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CO-1056": 88, "CR-1047": 81}, "businessText": "Satellite communications"}'
```

**Antwort:** Schlägt zusätzliche Knoten und Beziehungen vor.

### Mustererkennung

```bash
# Für einen einzelnen Knoten
curl -u admin:admin "http://localhost:8080/api/patterns/detect?nodeCode=CP-1023"

# Für bewertete Knoten
curl -u admin:admin -X POST http://localhost:8080/api/patterns/detect \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}}'
```

---

## Architektur-DSL

### Aktuelle Architektur als DSL-Text exportieren

```bash
curl -u admin:admin "http://localhost:8080/api/dsl/export"
```

### DSL-Text committen (erfordert ARCHITECT- oder ADMIN-Rolle)

```bash
curl -u admin:admin -X POST "http://localhost:8080/api/dsl/commit?branch=draft&message=initial+architecture" \
  -H "Content-Type: text/plain" \
  -d 'meta { language: "taxdsl"; version: "2.0"; namespace: "example"; }
element CP-1023 type Capability { title: "CIS Capabilities"; }
relation CR-1011 SUPPORTS BP-1327 { status: proposed; }'
```

### Commit-Verlauf anzeigen

```bash
curl -u admin:admin "http://localhost:8080/api/dsl/history?branch=draft"
```

### Diff zwischen Commits

```bash
curl -u admin:admin "http://localhost:8080/api/dsl/diff/semantic/{beforeId}/{afterId}"
```

### Branch und Merge

```bash
# Branch erstellen
curl -u admin:admin -X POST "http://localhost:8080/api/dsl/branches?name=review&fromBranch=draft"

# Zusammenführen
curl -u admin:admin -X POST "http://localhost:8080/api/dsl/merge?fromBranch=review&intoBranch=accepted"
```

---

## Dokumentimport & Provenienz

PDF/DOCX-Dokumente hochladen und Anforderungen mit Quell-Provenienz-Tracking extrahieren.
Für die vollständige Feature-Beschreibung siehe [Dokumentimport](DOCUMENT_IMPORT.md).

### Dokument hochladen und parsen

```bash
curl -u admin:admin -X POST http://localhost:8080/api/documents/upload \
  -F "file=@vorschrift.pdf"
```

**Antwort (200):**

```json
{
  "title": "vorschrift.pdf",
  "format": "PDF",
  "pageCount": 12,
  "sections": [
    { "heading": "§1 Geltungsbereich", "content": "Diese Vorschrift gilt für..." },
    { "heading": "§2 Anforderungen", "content": "Das System soll..." }
  ],
  "candidates": [
    { "text": "Das System soll sichere Authentifizierung bereitstellen", "section": "§2 Anforderungen" }
  ]
}
```

Maximale Upload-Größe: 50 MB. Unterstützte Formate: PDF, DOCX.

### KI-gestützte Anforderungsextraktion

```bash
curl -u admin:admin -X POST http://localhost:8080/api/documents/extract-ai \
  -H "Content-Type: application/json" \
  -d '{"documentText": "Das System soll...", "title": "vorschrift.pdf"}'
```

Nutzt das konfigurierte LLM, um Anforderungen aus dem Dokumenttext zu identifizieren und zu strukturieren.

### Direkte Vorschriften-zu-Architektur-Zuordnung

```bash
curl -u admin:admin -X POST http://localhost:8080/api/documents/map-regulation \
  -H "Content-Type: application/json" \
  -d '{"documentText": "Das System soll...", "title": "VV Digitale Anträge"}'
```

Ordnet Vorschrifteninhalte direkt per KI-Analyse den Taxonomie-Knoten zu.

### Extrahierte Kandidaten bestätigen

```bash
curl -u admin:admin -X POST http://localhost:8080/api/documents/confirm-candidates \
  -H "Content-Type: application/json" \
  -d '{"sourceTitle": "vorschrift.pdf", "sourceType": "REGULATION", "candidates": [{"text": "Sichere Authentifizierung", "section": "§2"}]}'
```

Verknüpft ausgewählte Anforderungskandidaten mit dem Quell-Provenienz-Modell.

### Quellartefakte auflisten

```bash
curl -u admin:admin "http://localhost:8080/api/provenance/sources"
```

Gibt alle registrierten Quellartefakte zurück (hochgeladene Dokumente, Vorschriften usw.).

### Provenienz-Links für eine Anforderung abrufen

```bash
curl -u admin:admin "http://localhost:8080/api/provenance/links/REQ-001"
```

Gibt alle Provenienz-Links zurück, die eine Anforderung mit ihren Quellartefakten verbinden.

---

## Administration

### Systemstatus

```bash
curl -u admin:admin "http://localhost:8080/api/ai-status"
curl -u admin:admin "http://localhost:8080/api/status/startup"
curl -u admin:admin "http://localhost:8080/api/embedding/status"
```

### LLM-Diagnose (nur ADMIN)

```bash
curl -u admin:admin "http://localhost:8080/api/diagnostics"
```

---

## Fehlerantworten

| HTTP-Code | Bedeutung | Häufige Ursache |
|---|---|---|
| `200` | Erfolg | — |
| `400` | Ungültige Anfrage | Fehlender oder leerer `businessText`, ungültiger Knotencode |
| `401` | Nicht autorisiert | Fehlende oder falsche Anmeldedaten |
| `403` | Verboten | Unzureichende Rolle (z. B. USER versucht, eine Beziehung zu löschen) |
| `404` | Nicht gefunden | Ungültiger Knotencode oder Vorschlags-ID |
| `423` | Gesperrt | Zu viele fehlgeschlagene Anmeldeversuche — IP ist vorübergehend gesperrt |
| `429` | Zu viele Anfragen | Ratenbegrenzung überschritten (konfigurierbar über `TAXONOMY_RATE_LIMIT_PER_MINUTE`) |
| `503` | Dienst nicht verfügbar | Taxonomie wird noch geladen — `/api/status/startup` abfragen |
| `500` | Serverfehler | LLM-Timeout, Export-E/A-Fehler |

---

## Benutzerverwaltung (nur Admin)

Alle Benutzerverwaltungs-Endpunkte erfordern `ROLE_ADMIN` und verwenden HTTP-Basic-Authentifizierung.

### Benutzer auflisten

```bash
curl -u admin:password http://localhost:8080/api/admin/users
```

**Antwort:**
```json
[
  {
    "id": 1,
    "username": "admin",
    "displayName": "Administrator",
    "email": null,
    "enabled": true,
    "roles": ["ROLE_ADMIN", "ROLE_ARCHITECT", "ROLE_USER"]
  }
]
```

### Benutzer erstellen

```bash
curl -u admin:password -X POST http://localhost:8080/api/admin/users \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"securepass123","roles":["USER","ARCHITECT"],"displayName":"Alice","email":"alice@example.com"}'
```

### Benutzer aktualisieren

```bash
curl -u admin:password -X PUT http://localhost:8080/api/admin/users/2 \
  -H "Content-Type: application/json" \
  -d '{"displayName":"Alice Updated","roles":["USER","ARCHITECT","ADMIN"]}'
```

### Benutzerpasswort ändern

```bash
curl -u admin:password -X PUT http://localhost:8080/api/admin/users/2/password \
  -H "Content-Type: application/json" \
  -d '{"password":"newpass123"}'
```

### Benutzer deaktivieren (Soft Delete)

```bash
curl -u admin:password -X DELETE http://localhost:8080/api/admin/users/2
```

> **Sicherheitshinweis:** Der letzte verbleibende Admin-Benutzer kann nicht deaktiviert werden. Ein Versuch, dies zu tun, gibt HTTP 400 zurück.

---

## Workspace-Verwaltung

Alle Workspace-Endpunkte erfordern Authentifizierung (HTTP Basic). Jeder Benutzer erhält beim ersten Zugriff automatisch einen persönlichen Workspace.

### Aktuellen Workspace abrufen

```bash
curl -u alice:password http://localhost:8080/api/workspace/current
```

**Antwort:**
```json
{
  "workspaceId": "a1b2c3d4-...",
  "username": "alice",
  "displayName": "alice's workspace",
  "currentBranch": "draft",
  "baseBranch": "draft",
  "shared": false,
  "currentContext": { "branch": "draft", "mode": "EDITABLE" },
  "createdAt": "2026-03-15T10:00:00Z",
  "lastAccessedAt": "2026-03-15T12:00:00Z"
}
```

### Aktive Workspaces auflisten (Admin)

```bash
curl -u admin:password http://localhost:8080/api/workspace/active
```

### Workspace-Statistiken

```bash
curl -u admin:password http://localhost:8080/api/workspace/stats
```

**Antwort:** `{ "activeWorkspaces": 3 }`

### Workspace entfernen (Admin)

```bash
curl -u admin:password -X POST "http://localhost:8080/api/workspace/evict?username=alice"
```

### Branches vergleichen

```bash
curl -u alice:password -X POST "http://localhost:8080/api/workspace/compare?leftBranch=draft&rightBranch=feature-x"
```

**Antwort:**
```json
{
  "left": { "branch": "draft", "mode": "EDITABLE" },
  "right": { "branch": "feature-x", "mode": "READ_ONLY" },
  "summary": {
    "elementsAdded": 2,
    "elementsChanged": 1,
    "elementsRemoved": 0,
    "relationsAdded": 1,
    "relationsChanged": 0,
    "relationsRemoved": 0
  },
  "changes": [
    {
      "changeType": "ADD",
      "objectType": "ELEMENT",
      "objectId": "CP-1023",
      "description": "Element CP-1023 added (Capability)",
      "before": null,
      "after": "Secure Voice Service"
    }
  ],
  "rawDslDiff": "..."
}
```

### Vom gemeinsamen Repository synchronisieren

```bash
curl -u alice:password -X POST "http://localhost:8080/api/workspace/sync-from-shared?userBranch=feature-x"
```

**Antwort:** `{ "success": true, "branch": "feature-x", "mergeCommit": "abc1234...", "syncedAt": "2026-03-15T10:00:00Z" }`

### Im gemeinsamen Repository veröffentlichen

```bash
curl -u alice:password -X POST "http://localhost:8080/api/workspace/publish?userBranch=feature-x"
```

**Antwort:** `{ "success": true, "branch": "feature-x", "mergeCommit": "def5678...", "publishedAt": "2026-03-15T10:05:00Z" }`

### Synchronisierungsstatus abrufen

```bash
curl -u alice:password http://localhost:8080/api/workspace/sync-state
```

**Antwort:**
```json
{
  "syncStatus": "AHEAD",
  "unpublishedCommitCount": 3,
  "lastSyncTimestamp": "2026-03-15T10:00:00Z",
  "lastPublishTimestamp": null
}
```

### Navigationsverlauf abrufen

```bash
curl -u alice:password http://localhost:8080/api/workspace/history
```

### Lokale Änderungen abrufen

```bash
curl -u alice:password "http://localhost:8080/api/workspace/local-changes?branch=feature-x"
```

**Antwort:** `{ "branch": "feature-x", "changeCount": 3, "hasUnpublishedChanges": true }`

### Dirty-Status prüfen

```bash
curl -u alice:password http://localhost:8080/api/workspace/dirty
```

**Antwort:** `{ "username": "alice", "dirty": true, "syncStatus": "AHEAD" }`

### Projektionsstatus abrufen

```bash
curl -u alice:password http://localhost:8080/api/workspace/projection
```

**Antwort:**
```json
{
  "username": "alice",
  "lastProjectionCommit": "abc1234",
  "lastProjectionBranch": "draft",
  "lastProjectionTimestamp": "2026-03-15T10:00:00Z",
  "lastIndexCommit": "abc1234",
  "lastIndexTimestamp": "2026-03-15T10:00:00Z",
  "persistedProjectionCommit": "abc1234",
  "persistedProjectionBranch": "draft",
  "persistedProjectionTimestamp": "2026-03-15T10:00:00Z",
  "persistedIndexCommit": "abc1234",
  "persistedIndexTimestamp": "2026-03-15T10:00:00Z",
  "stale": false
}
```

### Arbeitsbereich-Bereitstellung & Topologie

**Bereitstellungsstatus abfragen:**
```bash
curl -u alice:password http://localhost:8080/api/workspace/provisioning-status
```

**Antwort:**
```json
{
  "status": "READY",
  "topologyMode": "INTERNAL_SHARED",
  "sourceRepository": "uuid-des-system-repos",
  "error": null
}
```

**Arbeitsbereich bereitstellen (persönlichen Branch erstellen):**
```bash
curl -u alice:password -X POST http://localhost:8080/api/workspace/provision
```

**Antwort:**
```json
{
  "status": "READY",
  "branch": "alice/workspace",
  "baseBranch": "draft"
}
```

**Repository-Topologie abfragen:**
```bash
curl -u alice:password http://localhost:8080/api/workspace/topology
```

**Antwort:**
```json
{
  "mode": "INTERNAL_SHARED",
  "sharedBranch": "draft",
  "systemRepositoryId": "uuid-des-system-repos",
  "displayName": "Shared Architecture Repository"
}
```

---

## Weitere Referenzen

| Dokument | Inhalt |
|---|---|
| [cURL-Beispiele für Automatisierung](CURL_EXAMPLES.md) | Kopierbare cURL-Befehle für jeden Endpunkt |
| [Sicherheit](SECURITY.md) | Authentifizierung, Rollen, Deployment-Härtung |
| [Konfiguration](CONFIGURATION_REFERENCE.md) | Umgebungsvariablen und Einstellungen |
| [Swagger UI](http://localhost:8080/swagger-ui.html) | Interaktiver API-Explorer (wenn die Anwendung läuft) |
