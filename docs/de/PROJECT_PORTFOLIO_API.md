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

Lifecycle-/Editor-Befehlsfehler liefern außerdem stabile `code`-Werte sowie bei
Bedarf `field`, Abhängigkeits- oder Revisionsangaben. Daraus Aktualisierung, neue
Vorschau oder Wiederaufnahme ableiten; keine übersetzten Fehlermeldungen parsen.

<a id="architecture-lifecycle-api"></a>
## Architektur-Lifecycle, Eingangsprüfung und Umsetzung

Das Architektur-Repository besteht unabhängig vom Projektabschluss weiter. Ein
Folgeprojekt trägt zur selben Architektur bei; die ursprüngliche projektqualifizierte
Anforderungsidentität bleibt erhalten. Siehe den
[Benutzerablauf](PROJECT_REQUIREMENT_PORTFOLIO.md#architecture-lifecycle).

| Methode | Route | Zweck |
|---|---|---|
| GET | `/api/architecture/lifecycle/architectures` | Lesbare aktive Architekturen und eigene unvollständige Erstellungen auflisten. |
| POST | `/api/architecture/lifecycle/architectures` | Exakte eigene private Architekturerstellung anlegen oder wiederaufnehmen. |
| GET | `/api/architecture/lifecycle/architectures/{repositoryId}/workspaces` | Vorhandene private Arbeitskopien des Aufrufers für diese Architektur wiederfinden. |
| GET | `/api/architecture/lifecycle` | Exakten gewählten Architekturstand samt Umsetzungsbewertungen lesen. |
| GET | `/api/architecture/lifecycle/history` | Tatsächlich erreichbare Git-Historie mit begrenzter Abdeckung durchsuchen. |
| POST | `/api/architecture/lifecycle/intake-review` | Eingangstext gegen erlaubte Kandidaten prüfen, ohne zu schreiben. |
| POST | `/api/architecture/editor/requirements` | Ausdrückliche Auswahl `NEW`, `CHANGE` oder `LINK` ausführen und ihren Checkpoint vorbereiten/veröffentlichen. |
| POST | `/api/architecture/lifecycle/realizations/preview` | Prüfung einer exakten Anforderungsversion und ihrer Architekturabhängigkeiten vorschauen. |
| POST | `/api/architecture/lifecycle/realizations` | Exakte Prüfungsvorschau annehmen und ihren Checkpoint vorbereiten/veröffentlichen. |

Die Übersicht akzeptiert optionale `repositoryId`, `workspaceScopeKey` und `branch`
als Abgleich mit dem berechtigten Kontext. Sie gewähren keinen Zugriff auf einen
anderen Workspace. `commit` (vollständiger erreichbarer Git-SHA) **oder** semantische
`revision` wählen, niemals beide. Die Abfrageauswahl wird nicht aus der
`context.revision` eines Befehls abgeleitet.

| Auswahl | Quelle und Verhalten |
|---|---|
| Privater Workspace: kein Selektor oder ausdrücklich aktuelle Revision | `WORKSPACE_REVISION`; aktuelle Portfoliobeiträge können überblendet werden. Bearbeitung benötigt weiterhin passende Rolle und bereite Projektion. |
| Frühere erhaltene semantische Revision | `WORKSPACE_REVISION`, `projectionState=HISTORICAL`, `mayEdit=false`; gespeicherte Quelle ohne heutige Portfolioüberblendung. |
| Erreichbarer Git-Commit | `GIT_CHECKPOINT`, `projectionState=HISTORICAL`, `mayEdit=false`; exakt gespeicherte Commit-Quelle. |
| Zentraler/Fork-Lesekontext ohne Selektor | `GIT_CHECKPOINT` am aktuellen HEAD, `mayEdit=false`; keine private semantische Journalrevision oder aktuelle Portfolioüberblendung. |

Ungültige, fehlende oder unzugängliche Stände ergeben Fehler statt aktueller Daten.
Die Antwort enthält `context`, `architectureTitle`, `source`, `projectionState`,
`publicationState`, `mayEdit`, `projects`, `requirements` und auswählbare `dependencies`.
`version`, `versionId`, `contentHash` und Text jeder Anforderung beschreiben den
gewählten Modellstand. Eine historische Zeile überschreibt nicht den aktuellen
Portfolio-Anforderungskopf. `realization`, `contributors` und `links` erhalten Prüf-
und Beitragsherkunft. Historische portable Quellen benötigen keine aktuellen Datenbank-IDs.

### Unabhängige Architektur anlegen und exakt wiederaufnehmen

Die Erstellung übernimmt `{displayName, slug, description}`: einen nicht leeren Namen
mit höchstens 255 Zeichen, einen stabilen Slug gemäß `[a-z0-9][a-z0-9-]{0,99}` und eine
optionale Beschreibung mit höchstens 1.000 Zeichen. Eine neue Erstellung liefert
201, eine exakte Wiederaufnahme 200. Nach unterbrochener Antwort denselben Slug,
Namen und dieselbe Beschreibung behalten. Wiederaufnahme passt nur zur ursprünglichen
privaten Erstellungsidentität desselben Aufrufers. Ein passender `ACTIVE`-Eintrag
wird wiederverwendet; ein passender `FAILED`-Eintrag kann die Bereitstellung unter
derselben Repository-ID fortsetzen. `PROVISIONING` ergibt 409: Zustand beobachten/neu
laden, bevor erneut versucht wird. Abweichender Eigentümer, Identität oder
Erstellungsinhalt ergibt einen Konflikt statt eine fremde Erstellung offenzulegen
oder zu übernehmen.

Die Architekturantwort enthält `repositoryId`, `displayName`, `slug`, `defaultBranch`,
`owned`, `description` und `lifecycleState`. Die Liste enthält eigene Erstellungen
mit `FAILED`/`PROVISIONING`, damit eine verlorene Antwort keine neue Identität
erfordert. Die Wiederaufnahme prüft und erhält einen bereits geschriebenen initialen
Git-Commit; sie löscht oder initialisiert den logischen Git-Speicher nicht neu.
Abweichender erhaltener Git-Inhalt ergibt 409 und benötigt ausdrückliche Prüfung.
Vor dem Anlegen einer weiteren privaten Arbeitskopie vorhandene über die
Workspace-Listenroute wiederfinden. Deren Einträge nennen `workspaceId`,
`sourceRepositoryId`, `sourceBranch`, `currentBranch`, `displayName` und `provisioningStatus`.

### Exakter Kontext, Eingabe und Veröffentlichung

Schreibende Lifecycle-Befehle und Umsetzungsvorschauen benötigen
`If-Match: "workspace-revision-N"` passend zur `context.revision` im Body;
`If-None-Match` darf nicht zusätzlich gesendet werden. Fehlende Vorbedingungen ergeben
428, fehlerhafte Vorbedingungen 400 und eine verschobene semantische Revision 412.
Kontext-/Versionskonflikte erfordern einen neuen Vergleich. Der Kontext enthält
`repositoryId`, `workspaceScopeKey`, `branch`, `commit`, `revision`, `actor` und
`writeMode`. Leseantworten liefern ETag, semantische Revision und No-Store-Vorgabe;
die Übersicht nennt ihre Quelle zusätzlich in `X-Taxonomy-Source`.

`metadata` enthält stabile UUIDs `commandId`, `correlationId`, `causationId` und
eine Begründung. Für einen Transport-Retry exakte Anfrage und Befehlsidentität
behalten. Dieselbe Befehlsidentität mit geändertem Inhalt wird abgewiesen. Eine
erfolgreiche Wiederholung erzeugt keine weitere Anforderungsversion und wiederholt
keinen angenommenen Beitrag.

Der Anforderungsbefehl enthält `context`, `metadata`, `choice`, `projectId`,
`newProject`, `contributorProjectId`, `requirementId`, `requirement`,
`expectedVersionId`, `expectedContentHash` und `inputHash`. `inputHash` ist SHA-256
des exakten UTF-8-Anforderungstexts ohne Trimmen oder Normalisierung.

| Auswahl | Erforderliche Absicht |
|---|---|
| `NEW` | Genau eine vorhandene `projectId` oder `newProject`, Felder zum Anlegen einer Anforderung und keine bestehende `requirementId`. |
| `CHANGE` | Bestehendes Projekt/Anforderung und exakte aktuelle Versions-ID/Hash; erzeugt eine neue unveränderliche Version unter der ursprünglichen Identität. |
| `LINK` | Bestehendes Projekt/Anforderung und exakte aktuelle Versions-ID/Hash; speichert den Beitrag, ohne gespeicherten Text zu ersetzen. |

Für `CHANGE`/`LINK` kann `contributorProjectId` oder `newProject` ein Folgeprojekt
bestimmen. Die ursprüngliche projektqualifizierte Anforderung bleibt das Ziel.
Kandidatenvorschläge erlauben keine workspaceübergreifenden Schreibzugriffe. Eine
geänderte aktuelle Anforderungsversion ergibt `REQUIREMENT_VERSION_CHANGED`, statt
stillschweigend die neueste Version auszuwählen.

Die Analyseaktion zum Speichern einer neuen Anforderungsversion benötigt zusätzlich
eine `requirementVersionId` im geladenen Quellkontext, die zur aktuellen Version passt.
Fehlende oder veraltete Quell-IDs stoppen die Aktion vor dem Absenden mit einem
ausdrücklichen Hinweis. Der resultierende Befehl bindet weiterhin `expectedVersionId`
und `expectedContentHash` für die serverseitige Prüfung. Während der Übertragung
neu eingegebener Text bleibt ein gesonderter Entwurf. Das gespeicherte Ergebnis
kann ohne Verwerfen des Entwurfs in einem anderen Tab geöffnet werden. Siehe den
[Ablauf der Analyseübernahme](USER_GUIDE.md#analysis-requirement-promotion).

Umsetzungsanfragen enthalten `projectId`, `requirementId`, `expectedVersionId`,
`expectedContentHash`, `contributorProjectId`, `status` (`OPEN`, `PARTIAL`, `COMPLETE`)
und `dependencyIds` zusätzlich zu Kontext und Metadaten. Die Annahme übergibt den
`fingerprint` der Vorschau als `previewFingerprint`. Nachweise verwenden bestehende
Elemente, Relationen oder Mappings; Relationsendpunkte und Mappingziele werden
einbezogen. Teilweise/vollständige Prüfungen benötigen Nachweise; die Abhängigkeitsmenge
ist begrenzt. Geänderter Text oder geänderte Abhängigkeiten ergeben `REVIEW_REQUIRED`,
fehlerhafte Nachweise `INVALID_EVIDENCE`. Die frühere geprüfte Version bleibt
erhalten. KI-Relevanzwerte bestimmen diese Zustände nicht.

Anforderungsantworten enthalten `projectId`, `requirementId`, `requirement` und
`publication`; die Umsetzungsannahme liefert die Veröffentlichung direkt.
`publication.state` prüfen: Nur `CHECKPOINTED` belegt einen abgeschlossenen Checkpoint.
`CHECKPOINT_PENDING` und `RECONCILIATION_REQUIRED` erhalten einen angenommenen Beitrag,
ohne eine abgeschlossene Git-Veröffentlichung zu belegen. Die Veröffentlichung
enthält `context`, `checkpoint`, `code` und `detail`. `document.pendingCheckpointId`
des Editors identifiziert einen vorbereiteten Schreibvorgang für die vorhandene
Resume-Route. Die nachfolgenden Projekt-CRUD-Routen bleiben Entwurfsoperationen
ohne diese Lifecycle-Checkpoint-Garantie.

### Suchabdeckung und Providerergebnisse der Eingangsprüfung

Die schreibfreie Anfrage benötigt `text`; `projectId`, `title` und `analysisLanguage`
sind optional. Die Antwort enthält `status`, `code`, `inputHash`, `scope`,
`provider`, `coverage`, `candidates` und `manualReviewRequired`. Die Abdeckung nennt
`scanned`, `admitted` und `truncated`. Grenzen für Projekte, Anforderungen,
Repositories, Dokumente und Nachweisgröße begrenzen die Suche. Private Workspaces
anderer Benutzer sind keine Kandidatenquelle. Kandidatenaktionen berücksichtigen
den tatsächlich erlaubten Geltungsbereich.

`scope` identifiziert `repositoryId`, `workspaceId`, `workspaceScopeKey` und `branch`.
Kandidatenarten sind `REQUIREMENT`, `PROJECT`, `ARCHITECTURE` und
`ARCHITECTURE_REQUIREMENT`. Anforderungskandidaten behalten Quell-IDs, Textversion
und Inhaltshash. Der Provider vergleicht begrenzte berechtigte Textkandidaten direkt.
Der semantische Taxonomieindex ist kein Anforderungs-Vektorindex.

`status` ist `COMPLETE`, `PARTIAL`, `UNAVAILABLE` oder `FAILED`. Die Klassifikation
eines Kandidaten ist `DUPLICATE`, `CHANGE`, `ADDITION`, `CONFLICT` oder `NONE`, bei
Bewertung mit Begründung und Quellauszügen. Eine vollständige Bewertung bezieht sich
nur auf zugelassene Kandidaten; bei ungeprüften Kandidaten bleibt `classification` null.
`NONE`, Begrenzung oder leeres Ergebnis garantiert
keine globale Neuartigkeit. Exakte Gleichheit gespeicherten Texts kann auch ohne
Providererfolg deterministisch erkannt werden. Fehlende Konfiguration,
Projekt-Providerregeln, lokale/simulierte/Replay-Provider, Timeout und Ratenlimits
sind von gültigen negativen Befunden getrennt. Ungültige oder unvollständige
Modellantworten sind fehlgeschlagene Prüfungen, keine leeren Erfolge. Manuelle
Prüfung und ausdrückliches Speichern bleiben von der Modellverfügbarkeit getrennt.

### Native Git-Historie und Fortsetzung

Die Historie akzeptiert Kontextabgleiche sowie gemeinsam `projectKey`/`requirementKey`,
optional `text`, `expectedHead`, `offset` (Standard 0), `limit` (Standard 25, maximal
100) und `scanLimit` (Standard 100, maximal 500). Ein positiver Offset benötigt den
erfassten `expectedHead`. Beide Fachschlüssel müssen gemeinsam angegeben werden.
Die Suche verwendet natives JGit über das bestehende `jgit-storage-hibernate`-Repository,
keinen Shell-Git-Befehl oder parallelen Audit-Speicher. Durchsucht werden nur vom
gewählten Branch-HEAD erreichbare Vorgänger. Änderungen vergleichen jeden Commit mit
seinem ersten Elterncommit; die Einträge behalten alle tatsächlichen Eltern.
Die Merge-Topologie wird nicht zu einer erfundenen linearen Historie umgedeutet.

Die Seite enthält `head`, `entries`, `offset`, `nextOffset`, `scanned`, `coverage`
und `source=LIVE_GIT`. Einträge enthalten `commitId`, `parents`, `author`, `timestamp`,
vollständige `message`, Signaturbelege und `changes`. Änderungsarten umfassen `ADDED`,
`REMOVED`, `REQUIREMENT_CHANGED`, `REQUIREMENT_METADATA_CHANGED`, `DEPENDENCIES_CHANGED`,
`REALIZATION_CHANGED`, `MERGE` und `GIT_CHANGE`. Die Abdeckung ist `COMPLETE`, `PARTIAL`
oder `LIMIT_REACHED`. Mit zurückgegebenem Offset und demselben HEAD fortsetzen.
Ein geänderter HEAD ergibt HTTP 409 `HISTORY_HEAD_CHANGED` mit `branch`, `expectedHead`
und `currentHead`. Auf der ersten Seite neu beginnen statt mit dem alten Offset
fortzusetzen. Begrenzte Seiten können ohne Treffer bleiben, obwohl weitere Historie
existiert. Berechtigungs-, Speicher- und Quelllesefehler
dürfen nicht als leere Historie dargestellt werden.

<a id="editor-version-reconciliation-api"></a>
### Lokale Beiträge mit geändertem Git-HEAD abgleichen

| Methode | Route | Vertrag |
|---|---|---|
| POST | `/api/architecture/editor/versions/reconciliation/preview` | Basis, angenommenen lokalen Stand und eingehende Git-Quelle ohne Änderung vergleichen. |
| POST | `/api/architecture/editor/versions/reconciliation/accept` | Exakte aufgelöste Vorschau annehmen und ihren Checkpoint abschließen. |
| POST | `/api/architecture/editor/checkpoints/resume` | Vorbereiteten Checkpoint nach unterbrochenem Git-Schreibvorgang abschließen. |

Beide Abgleichrouten benötigen das `If-Match` der exakten Revision. Die Anfrage ist
`{context, metadata, resolutions, headCommit, previewFingerprint}`. `resolutions`
ordnet zurückgegebenen Konflikt-IDs `LOCAL` oder `INCOMING` zu. Jede Entscheidung
wählt einen vollständigen Quellblock; der reservierte Konflikt `$document` wählt
Dokumentmetadaten außerhalb der Blöcke. Die Vorschau liefert `context`, `headCommit`,
`fingerprint`, `baseDsl`, `localDsl`, `incomingDsl`, `mergedDsl`, `changes`, `conflicts`
und `canAccept`. Ein Konflikt enthält `id`, `reason`, `base`, `local`, `incoming` und
`resolution`. Fehlende Entscheidungen ergeben `canAccept=false`; das kombinierte
Modell muss ebenfalls die Validierung bestehen. Nach der Auswahl erneut vorschauen.
Die Annahme bindet dieselben Entscheidungen, Metadaten, Kontext, erfassten HEAD und
`fingerprint` als `previewFingerprint`. Geänderte Eingaben benötigen eine neue Vorschau.

Die schreibfreie Prüfung des Portfolio-Fachmoduls gleicht verwalteten kanonischen
Titel, Text, `x-version` und `x-content-hash` mit dem portablen Fachdatensatz und
seiner aktuellen unveränderlichen Version ab. Die Quelle vorhandener lokaler
`requirementVersion`-Blöcke darf nicht umgeschrieben werden; geänderter Text benötigt
eine neue Version. Ungültige Kombinationen erscheinen als Quellkonflikte mit
ausdrücklicher `LOCAL`-/`INCOMING`-Auswahl. Konsistente lokale Blöcke beibehalten und
anschließend eine neue Version anlegen. Git-Auslassungen löschen keine verwaltete
Fachhistorie: `LOCAL` wählen und ausdrücklich archivieren. Unverträgliche Entscheidungen
ergeben HTTP 409 `PORTFOLIO_RECONCILIATION_REQUIRED`.

Die Kompatibilitätsprüfung berücksichtigt auch unveränderliche Versionen der exakten
Zieldatenbank, einschließlich Versionen, die in der lokalen DSL fehlen. Widersprüchlicher
Text, Hashes oder mitgelieferte Quellherkunft, doppelte Versionsnummern und bekannter
Inhalt unter einer anderen Versionsnummer werden abgewiesen statt umgeschrieben oder
umnummeriert. Diese Vorprüfung läuft, bevor die vorgeschlagene Integration Zielmodell-
oder Tracking-Referenzen fortschreibt. Passende portable Quellblöcke allein reichen nicht.

Bei der Annahme werden geänderte verwaltete Portfolioblöcke über
`WorkspacePortfolioGitPort` innerhalb der Journaltransaktion materialisiert, vor
Checkpoint-Absicht und Git-Veröffentlichung. Portfolio-Validierungs-/Materialisierungskonflikte
setzen diese Annahme ohne neue Datenbankänderungen, Journaloperationen oder
Git-Checkpoints zurück. Eine exakte Wiederholung verwendet den erhaltenen Vorgang
ohne zweite Materialisierung. Ein späterer Git-Speicherfehler verwendet weiterhin
die Wiederaufnahme des ausstehenden Checkpoints. Erfolg liefert den vorhandenen
`CheckpointAccepted`-Beleg (`context`, `commandId`, `commitId`, `fromRevision`,
`throughRevision`, `commitCreated`, `replayed`), keine Lifecycle-Veröffentlichungshülle.
Geänderter HEAD/Revision benötigt eine neue Vorschau; wiederholtes Speichern löst
keinen Branch-Konflikt. `/api/architecture/editor/versions/recover` bleibt für
abgeschlossene Versionsschreibvorgänge ohne neuere uncheckpointete lokale Änderungen
vorgesehen. Siehe den [Editorablauf](ARCHITECTURE_EDITOR.md#editor-version-reconciliation).

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

## Word-Berichte aus unveränderlichen Snapshots

Die Architekturansicht bietet **Architektur als Word** und **Entscheidungen als Word** für die ausgewählte gespeicherte Analyse:

- `GET /api/projects/{projectId}/snapshots/{snapshotId}/architecture-report/docx?language=de`
- `GET /api/projects/{projectId}/snapshots/{snapshotId}/decision-report/docx?language=de`

Beide verwenden dieselbe gespeicherte Anforderungsversion, Entscheidungsevidenz und den persistierten Architekturgraphen. Aktuelle Katalogauswahlen, Präferenzen und Vorschläge berechnen den Graphen nicht erneut. Beide enthalten Architekturübersicht, begrenzte Detailabbildungen, vollständige Element- und Beziehungsinventare, Empfehlung, dokumentierte Lücken und Herkunftsnachweise. Der Entscheidungsbericht enthält außerdem alle Vaterknoten und Alternativen im verlinkten Entscheidungsbaum sowie sämtliche Entscheidungskapitel. Fehlende Werte bleiben „Nicht bewertet“; null Prozent bedeutet bewertet und verworfen.

`X-Taxonomy-Snapshot-Id` und `X-Taxonomy-Graph-SHA256` kennzeichnen die gemeinsame Quelle. Dieselben Werte stehen in den Dokumenteigenschaften `taxonomy.snapshot.id` und `taxonomy.graph.sha256`. Der Graphen-Fingerabdruck umfasst alle eingefrorenen Knoten- und Beziehungsdaten einschließlich Container-Metadaten, ohne veränderliche Anzeigetitel. Er unterscheidet sich vom rein semantischen Fingerabdruck der Austauschformate.

Überschriften, fertig befüllte Inhaltsübersichten mit internen Kapitelverknüpfungen, wiederholte Tabellenköpfe, nummerierte Beschriftungen und Bildbeschreibungen sind native OOXML-Strukturen. Der Export berücksichtigt die gewählte Verzeichnistiefe und funktioniert ohne leeres Inhaltsverzeichnisfeld oder automatische Feldaktualisierung beim Öffnen. Die Navigation verwendet Kapitelverknüpfungen; Seitenzahlen werden nicht geschätzt. Die Detailansichten umfassen alle Knoten und Beziehungen mit höchstens 12 Knoten und 18 Beziehungen je Abbildung sowie mindestens 8pt für Knotenbeschriftungen. Mehr als 500 Knoten, 1.000 Beziehungen oder 250 Detailansichten werden mit HTTP 409 abgewiesen; Graphen werden niemals still gekürzt. Fehlende oder widersprüchliche eingefrorene Evidenz führt ebenfalls zu 409.

`language=en` wählt englische generierte Beschriftungen. Gespeicherte Evidenz und administrativ gepflegte Vorlagentexte bleiben unverändert. Der Entscheidungsbericht verwendet weiterhin den versionierten DOTX-Body-Marker und Dekorator; eigene Formatvorlagen, Kopf- und Fußzeilen, Logos und Vorlagenidentität bleiben erhalten. `/api/report/docx` bleibt der strukturierte **Ad-hoc-/Live-Bericht** ohne behauptete Snapshot-Herkunft.

Die Akzeptanzprüfung prüft Quellenparität und semantische Vollständigkeit und rendert beide Word-Dateien mit LibreOffice/Poppler. Dies ist keine Produktzertifizierung für Microsoft Word.
