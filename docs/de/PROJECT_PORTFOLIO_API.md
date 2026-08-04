# Projektportfolio-API

Diese Referenz richtet sich an Integrationen, Automatisierung, Tests und eigene Clients. Endanwender verwenden das GUI-first-[Benutzerhandbuch zum Projektportfolio](PROJECT_REQUIREMENT_PORTFOLIO.md).

## Authentifizierung und Schreibschutz

Alle Endpunkte benötigen einen angemeldeten Benutzer. Im lokalen Form-Login-Profil benötigen Browser-Sitzungen für Schreibzugriffe ein CSRF-Token. Clients mit explizitem HTTP-Basic- oder Bearer-Header werden als zustandslose API-Clients behandelt.

Rollen:

- `USER`, `ARCHITECT`, `ADMIN`: Portfolio lesen und freigegebene Analysen starten;
- `ARCHITECT`, `ADMIN`: Projekte, Anforderungen, Lösungen, Produkte, Prüfungen, Konflikte und Git-Zustand verändern;
- `ADMIN`: administrative APIs außerhalb des Portfolios.

Unbekannte API-Schreibmethoden werden standardmäßig abgelehnt. Workspacegebundene Operationen brechen bei ungeklärtem isoliertem Workspace ab, sofern nicht ausdrücklich ein Legacy-Shared-Modus konfiguriert ist.

## Fehlervertrag

Validierungs-, Nicht-gefunden- und Konfliktfehler verwenden RFC-9457-`ProblemDetail`-JSON. Clients sollten hauptsächlich `status`, `title` und `detail` auswerten.

## Projekte

```text
POST   /api/projects
GET    /api/projects
GET    /api/projects/{projectId}
PATCH  /api/projects/{projectId}
```

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects \
  -H 'Content-Type: application/json' \
  -d '{
    "projectKey": "P-001",
    "title": "Sichere Kollaborationsplattform",
    "description": "Architekturprojekt für die Zielplattform",
    "status": "ACTIVE"
  }'
```

## Anforderungen und unveränderliche Versionen

```text
POST   /api/projects/{projectId}/requirements
GET    /api/projects/{projectId}/requirements
GET    /api/projects/{projectId}/requirements/{requirementId}
PATCH  /api/projects/{projectId}/requirements/{requirementId}
POST   /api/projects/{projectId}/requirements/{requirementId}/versions
GET    /api/projects/{projectId}/requirements/{requirementId}/versions
```

Anforderung anlegen:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/requirements \
  -H 'Content-Type: application/json' \
  -d '{
    "requirementKey": "REQ-001",
    "title": "Verschlüsselte Kommunikation",
    "text": "Die Plattform muss sämtliche Benutzerkommunikation verschlüsseln.",
    "status": "DRAFT",
    "priority": 80,
    "criticality": "HIGH",
    "requirementType": "SECURITY",
    "reviewStatus": "PROPOSED",
    "changeReason": "Erste Version",
    "source": {
      "sourceArtifactId": 41,
      "sourceVersionId": 42,
      "sourceFragmentIds": [101, 102],
      "sectionReference": "Abschnitt 5.2",
      "pageNumber": 17,
      "originalText": "Sämtliche Kommunikation ist zu verschlüsseln."
    }
  }'
```

Neue unveränderliche Textversion anlegen:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/requirements/11/versions \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "Die Plattform muss freigegebene Ende-zu-Ende-Verschlüsselung verwenden.",
    "changeReason": "Verschlüsselungsanforderung präzisiert",
    "source": {
      "sectionReference": "Abschnitt 5.2.1",
      "pageNumber": 18,
      "originalText": "Freigegebene Ende-zu-Ende-Verschlüsselung ist verpflichtend."
    }
  }'
```

## Dokumentauslesung

Der grafische Assistent verwendet die vorhandenen Provenienz-Endpunkte:

```text
POST /api/documents/upload       multipart/form-data
POST /api/documents/extract-ai   multipart/form-data
```

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/documents/upload \
  -F 'file=@anforderungen.pdf' \
  -F 'title=Plattformanforderungen' \
  -F 'sourceType=REGULATION'
```

Die Antwort enthält Quellartefakt/-version, deterministische Kandidaten, Seiteninformationen und Warnungen.

## Atomarer geprüfter Import

```text
POST /api/projects/{projectId}/requirements/import-review
```

Der Endpunkt übernimmt neue Anforderungen und neue Versionen in einer Transaktion:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/requirements/import-review \
  -H 'Content-Type: application/json' \
  -d '{
    "analyzeAfterImport": true,
    "maxArchitectureNodes": 25,
    "idempotencyKey": "quelle-42-geprueft-2026-08-04",
    "items": [
      {
        "decision": "NEW_REQUIREMENT",
        "requirementKey": "REQ-002",
        "title": "Audit-Historie",
        "text": "Die Plattform muss eine prüfbare Entscheidungshistorie führen.",
        "requirementType": "FUNCTIONAL",
        "priority": 70,
        "criticality": "HIGH",
        "source": {
          "sourceArtifactId": 41,
          "sourceVersionId": 42,
          "sectionReference": "Abschnitt 7",
          "pageNumber": 22,
          "originalText": "Eine prüfbare Historie ist aufzubewahren."
        }
      },
      {
        "decision": "NEW_VERSION",
        "targetRequirementId": 11,
        "title": "Verschlüsselte Kommunikation",
        "text": "Die Plattform muss freigegebene Ende-zu-Ende-Verschlüsselung verwenden.",
        "requirementType": "SECURITY",
        "priority": 80,
        "criticality": "HIGH",
        "source": {
          "sourceArtifactId": 41,
          "sourceVersionId": 42,
          "sectionReference": "Abschnitt 5.2.1",
          "pageNumber": 18,
          "originalText": "Freigegebene Ende-zu-Ende-Verschlüsselung ist verpflichtend."
        }
      }
    ]
  }'
```

Mit Analyse liefert der Endpunkt `202 Accepted` und einen kanonischen `Location`-Header. Ohne Analyse liefert er `201 Created`.

## Analysejobs

```text
POST   /api/projects/{projectId}/analyses
POST   /api/projects/{projectId}/requirements/{requirementId}/analyses
GET    /api/projects/{projectId}/analysis-jobs
GET    /api/projects/{projectId}/analysis-jobs/{jobId}
POST   /api/projects/{projectId}/analysis-jobs/{jobId}/retry-failed
```

Alle Anforderungen getrennt analysieren:

```bash
curl -i -u analyst:password \
  -X POST http://localhost:8080/api/projects/1/analyses \
  -H 'Content-Type: application/json' \
  -d '{
    "all": true,
    "provider": "GEMINI",
    "maxArchitectureNodes": 25,
    "idempotencyKey": "P-001-baseline-2026-08-04"
  }'
```

```text
HTTP/1.1 202 Accepted
Location: /api/projects/1/analysis-jobs/550e8400-e29b-41d4-a716-446655440000
```

```bash
curl -u analyst:password \
  http://localhost:8080/api/projects/1/analysis-jobs/550e8400-e29b-41d4-a716-446655440000
```

Job-/Itemzustände:

```text
PENDING, RUNNING, SUCCESS, PARTIAL, FAILED, CANCELLED
```

Nur fehlgeschlagene beziehungsweise wiederaufnehmbare Einträge wiederholen:

```bash
curl -u analyst:password \
  -X POST http://localhost:8080/api/projects/1/analysis-jobs/550e8400-e29b-41d4-a716-446655440000/retry-failed \
  -H 'Content-Type: application/json' \
  -d '{}'
```

## Snapshots, Zuordnungen und Prüfung

```text
GET    /api/projects/{projectId}/requirements/{requirementId}/snapshots
GET    /api/projects/{projectId}/snapshots/{snapshotId}
GET    /api/projects/{projectId}/snapshots/diff?older=...&newer=...
PATCH  /api/projects/{projectId}/analysis-mappings/elements/{mappingId}
PATCH  /api/projects/{projectId}/analysis-mappings/relations/{mappingId}
```

Elementzuordnung mit echter Evidenz prüfen:

```bash
curl -u architect:password \
  -X PATCH http://localhost:8080/api/projects/1/analysis-mappings/elements/42 \
  -H 'Content-Type: application/json' \
  -d '{
    "reviewStatus": "CONFIRMED",
    "actionStatus": "REUSE",
    "actionEvidence": "Servicekatalogeintrag SOL-004 wurde gegen die Anforderung geprüft.",
    "comment": "Durch den Projektarchitekten geprüft."
  }'
```

Maßnahmen:

```text
UNDECIDED
SATISFIED_AS_IS
REUSE
CHANGE
CREATE
PROCURE
ORGANIZATIONAL
RETIRE_OR_REPLACE
```

## Lösungen

```text
POST   /api/solutions
GET    /api/solutions
GET    /api/solutions/{solutionId}
PATCH  /api/solutions/{solutionId}
POST   /api/solutions/{solutionId}/taxonomy-coverage

POST   /api/projects/{projectId}/solutions
GET    /api/projects/{projectId}/solutions
PATCH  /api/projects/{projectId}/solutions/{projectSolutionId}
POST   /api/projects/{projectId}/solutions/{projectSolutionId}/requirements
POST   /api/projects/{projectId}/solutions/propose-from-taxonomy
```

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/solutions \
  -H 'Content-Type: application/json' \
  -d '{
    "solutionKey": "SOL-001",
    "title": "Sicherer Kommunikationsdienst",
    "description": "Wiederverwendbare verschlüsselte Kommunikation",
    "solutionType": "SERVICE",
    "operatingModel": "PRIVATE_CLOUD",
    "lifecycleStatus": "PLANNED",
    "maturityLevel": 2,
    "extensionAttributes": {}
  }'
```

Lösung zum Projekt hinzufügen:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/solutions \
  -H 'Content-Type: application/json' \
  -d '{
    "solutionId": 21,
    "status": "PROPOSED",
    "actionStatus": "REUSE",
    "priority": 70,
    "rationale": "Kandidat für REQ-001 und REQ-002"
  }'
```

Bestätigte Taxonomieabdeckung ergänzen:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/solutions/21/taxonomy-coverage \
  -H 'Content-Type: application/json' \
  -d '{
    "nodeCode": "CR-1047",
    "coveragePercent": 90,
    "evidence": "Geprüfte Lösungsarchitektur, Version 3.1",
    "reviewStatus": "CONFIRMED"
  }'
```

Projektlösung mit einem exakten Snapshot verknüpfen:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/solutions/31/requirements \
  -H 'Content-Type: application/json' \
  -d '{
    "requirementId": 11,
    "snapshotId": "snapshot-uuid",
    "coveragePercent": 85,
    "role": "USES",
    "reviewStatus": "CONFIRMED",
    "evidence": "Snapshot und Lösungsfähigkeit wurden gemeinsam geprüft."
  }'
```

## Produkte und Kandidaten

```text
POST   /api/products
GET    /api/products
GET    /api/products/{productId}
PATCH  /api/products/{productId}
POST   /api/products/{productId}/taxonomy-coverage

POST   /api/projects/{projectId}/solutions/{projectSolutionId}/products
GET    /api/projects/{projectId}/solutions/{projectSolutionId}/products
```

Quellengebundenes Produkt anlegen:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{
    "productKey": "PRD-001",
    "manufacturer": "Beispielhersteller",
    "productFamily": "Sichere Zusammenarbeit",
    "productName": "Collaboration Server",
    "editionVersion": "4.2",
    "productStatus": "CANDIDATE",
    "operatingModel": "PRIVATE_CLOUD",
    "sourceReference": "Architekturhandbuch 4.2, Kapitel 6",
    "verifiedAt": "2026-08-04T08:00:00Z"
  }'
```

Kandidat ergänzen oder aktualisieren:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/1/solutions/31/products \
  -H 'Content-Type: application/json' \
  -d '{
    "productId": 51,
    "coveragePercent": 85,
    "hardExclusions": null,
    "strengths": "Gute Identitäts- und Verschlüsselungsintegration",
    "weaknesses": "Zusätzlicher Migrationsaufwand",
    "openEvidence": "Performance-Benchmark ist noch offen",
    "confidence": 0.82,
    "reviewStatus": "CONFIRMED",
    "selectionStatus": "SHORTLISTED"
  }'
```

Ein Produkt kann nur mit `reviewStatus=CONFIRMED` und ohne hartes Ausschlusskriterium `SELECTED` werden.

## Konfliktregister

```text
POST   /api/projects/{projectId}/conflicts/detect
GET    /api/projects/{projectId}/conflicts
PATCH  /api/projects/{projectId}/conflicts/{conflictId}
```

```bash
curl -u architect:password \
  -X PATCH http://localhost:8080/api/projects/1/conflicts/71 \
  -H 'Content-Type: application/json' \
  -d '{
    "status": "RESOLVED",
    "resolutionNote": "Das freigegebene Private-Cloud-Profil erfüllt beide Anforderungen."
  }'
```

Zustände:

```text
PROPOSED, CONFIRMED, REJECTED, RESOLVED
```

## Konsolidiertes Portfolio und Matrizen

```text
GET /api/projects/{projectId}/portfolio
```

Die Antwort enthält Projekt, Anforderungen, aggregierte Taxonomieknoten, Lösungen, Konflikte, Kennzahlen und:

- `requirementTaxonomyMatrix`
- `requirementSolutionMatrix`
- `solutionProductMatrix`

Es gibt keine separaten `/matrices/...`-Endpunkte. Jede Matrix besitzt `rows`, `columns` und eine verschachtelte `values`-Map.

## Git-Projektion und Zusammenarbeit

```text
GET    /api/projects/git/export
POST   /api/projects/git/commit
GET    /api/projects/git/materialize-preview?branch=...
POST   /api/projects/git/materialize
POST   /api/projects/git/merge?source=...&target=...
```

Aktuelles Workspaceportfolio als TaxDSL exportieren:

```bash
curl -u architect:password http://localhost:8080/api/projects/git/export
```

Geprüfte Projektion committen:

```bash
curl -u architect:password \
  -X POST http://localhost:8080/api/projects/git/commit \
  -H 'Content-Type: application/json' \
  -d '{
    "branch": "draft",
    "message": "Geprüfter Portfoliostand nach REQ-001-Analyse"
  }'
```

Materialisierung vor dem Anwenden prüfen:

```bash
curl -u architect:password \
  'http://localhost:8080/api/projects/git/materialize-preview?branch=draft'
```

Die Antwort enthält exakten Ziel-HEAD, Fingerprints, Zeilenzahlen und begrenzte Vorschauen hinzugekommener/entfallener Zeilen. Clients sollten unmittelbar vor dem Anwenden erneut prüfen und einen veränderten `targetHead` ablehnen.

Branches zusammenführen:

```bash
curl -u architect:password \
  -X POST 'http://localhost:8080/api/projects/git/merge?source=feature-a&target=draft' \
  -H 'Content-Type: application/json' \
  -d '{"message":"Geprüftes Featureportfolio zusammenführen"}'
```

## Berichte

```text
GET /api/projects/{projectId}/reports/{format}
```

Formate:

```text
markdown, html, docx, json, csv
```

Optionale Parameter:

- `requirementId`: auf eine Anforderung begrenzen;
- `matrix`: bei CSV `taxonomy`, `solutions` oder `products`.

```bash
curl -u reader:password \
  -o P-001-bericht.docx \
  http://localhost:8080/api/projects/1/reports/docx
```

```bash
curl -u reader:password \
  -o REQ-001-bericht.json \
  'http://localhost:8080/api/projects/1/reports/json?requirementId=11'
```

```bash
curl -u reader:password \
  -o P-001-loesung-produkt.csv \
  'http://localhost:8080/api/projects/1/reports/csv?matrix=products'
```

## Grenzen und Betriebskonfiguration

| Eigenschaft | Standard | Zweck |
|---|---:|---|
| `taxonomy.portfolio.max-analysis-batch` | `100` | Maximale Anforderungen je Analysejob |
| `taxonomy.portfolio.max-import-requirements` | `100` | Maximale geprüfte Einträge je Import |
| `taxonomy.portfolio.max-import-characters` | `500000` | Maximale kombinierte Textlast |
| `taxonomy.portfolio.analysis-claim-timeout-seconds` | `900` | Zeit bis ein laufender Claim wiederaufnehmbar ist |

Migration, Backup, Recovery und Produktionsbetrieb beschreibt [Portfolio-Betrieb](PROJECT_PORTFOLIO_OPERATIONS.md).
