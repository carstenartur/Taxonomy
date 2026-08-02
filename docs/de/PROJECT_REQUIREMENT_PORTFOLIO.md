# Projekt-, Anforderungs-, Lösungs- und Produktportfolio

## Zweck

Das Projektportfolio erweitert die bisherige Analyse einer einzelnen Anforderung zu einem nachvollziehbaren Mehranforderungsprozess. Es trennt vier Fragen, die fachlich nicht miteinander vermischt werden dürfen:

1. **Anforderungsrelevanz:** Welche Taxonomieelemente sind für jede einzelne Anforderung relevant?
2. **Architekturauswirkung:** Welche verbundenen Elemente und Beziehungen bilden die anforderungsspezifische Zielarchitektur?
3. **Realisierungsentscheidung:** Ist ein Element bereits erfüllt, wiederverwendbar, zu ändern, neu zu schaffen, zu beschaffen oder organisatorisch umzusetzen?
4. **Produktentscheidung:** Welches konkrete, quellengebundene Produkt und welche Version sind geprüfte Kandidaten für eine Lösung?

Der eigene Arbeitsbereich ist erreichbar unter:

```text
/projects
```

Der bestehende Analysearbeitsbereich `/` und `POST /api/analyze` bleiben für Ad-hoc-Analysen verfügbar.

## Grundregel: Anforderungen werden getrennt analysiert

Ein Projekt kann beliebig viele stabile Anforderungen enthalten:

```text
P-001
├── REQ-001
├── REQ-002
└── REQ-003
```

**Alle analysieren** erzeugt je Anforderung ein eigenes Job-Item und einen eigenen unveränderlichen Ergebnissnapshot. Die Texte werden nicht zusammengefügt. Ein Fehler bei `REQ-002` verwirft daher nicht die erfolgreichen Ergebnisse von `REQ-001` und `REQ-003`.

## Begriffe

### Projekt

Ein Projekt ist der fachliche, workspacegebundene Container für Anforderungen, aktuelle Lösungsentscheidungen, Produktkandidaten, Konfliktprüfungen und konsolidierte Matrizen.

Ein Workspace kann mehrere Projekte enthalten. Wiederverwendbare Lösungen und Produkte können von mehreren Projekten desselben Workspace genutzt werden.

### Anforderung und Anforderungsversion

Eine Anforderung besitzt einen stabilen Schlüssel wie `REQ-001`. Ihr Text wird niemals überschrieben. Eine Änderung erzeugt eine unveränderliche nummerierte Version mit:

- SHA-256-Fingerprint des Inhalts,
- Autor und Zeitpunkt,
- Änderungsbegründung,
- optionalen Referenzen auf Quellenartefakt, Quellenversion und Quellenfragmente,
- Abschnitt, Seite und Originaltext.

Wird ein identischer Text erneut übergeben, wird die vorhandene Version ausgewählt statt eine Dublette anzulegen.

### Analysejob und Job-Item

Ein Job beschreibt eine angeforderte Mehrfachanalyse. Jede Anforderung besitzt ein eigenes Item mit einem der Zustände:

- `PENDING`
- `RUNNING`
- `SUCCESS`
- `PARTIAL`
- `FAILED`
- `CANCELLED`

Fehlgeschlagene Items können erneut ausgeführt werden, ohne erfolgreiche Anforderungen noch einmal zu analysieren.

### Analyse-Snapshot

Ein erfolgreiches oder teilweise erfolgreiches Item erzeugt einen unveränderlichen Snapshot mit:

- exakter Anforderungsversion,
- vollständigem Score-Set,
- Architekturansicht,
- Beziehungshypothesen,
- Gap-Analyse,
- Mustererkennung,
- Architekturempfehlung,
- Provider und optionalem Modell,
- Prompt- und Taxonomie-Fingerprints,
- Workspace, Branch und Git-Commit,
- Warnungen, Laufzeit und Autor.

Eine spätere Neuanalyse erzeugt einen weiteren Snapshot. Ältere Ergebnisse bleiben darstellbar und vergleichbar.

### Taxonomiezuordnung

Ein Snapshot ordnet die Anforderung konkreten Taxonomieknoten zu. Jede Zuordnung speichert:

- direkten Score,
- abgeleitete Relevanz,
- Konfidenz,
- Herkunft der Zuordnung,
- Hierarchiepfad,
- verständliche Aufnahmebegründung,
- Kennzeichen für die Wirkungsauswahl,
- Prüf- und Maßnahmenentscheidung.

Die Prüfung verändert den unveränderlichen Snapshot nicht, sondern ergänzt die abfragbare Zuordnung um eine menschliche Entscheidung.

### Maßnahmenstatus

Taxonomierelevanz beweist nicht, was gebaut oder gekauft werden muss. Der Ausgangszustand ist deshalb `UNDECIDED`.

Ein Reviewer kann folgende Maßnahmen festlegen:

| Maßnahme | Bedeutung |
|---|---|
| `SATISFIED_AS_IS` | Eine vorhandene Lösung erfüllt die Anforderung bereits |
| `REUSE` | Eine vorhandene Lösung kann wiederverwendet werden |
| `CHANGE` | Eine vorhandene Lösung muss angepasst werden |
| `CREATE` | Eine neue Lösung muss entwickelt werden |
| `PROCURE` | Eine Lösung oder ein Produkt muss beschafft werden |
| `ORGANIZATIONAL` | Organisatorische statt technische Umsetzung |
| `RETIRE_OR_REPLACE` | Vorhandene Lösung muss stillgelegt oder ersetzt werden |
| `UNDECIDED` | Noch keine geprüfte Entscheidung |

### Lösungsdefinition und Projektlösung

Eine `SolutionDefinition` beschreibt eine wiederverwendbare Realisierung eines oder mehrerer Taxonomieelemente. Sie kann einen Service, eine Anwendung, eine Plattform, einen Prozess, eine Datenlösung, Infrastruktur oder eine organisatorische Maßnahme beschreiben.

Sie enthält Betriebsmodell, Lebenszyklus, Reifegrad, Verantwortlichkeit, Kosten, Risiken und Vorlaufzeit.

Eine `ProjectSolution` ist die projektspezifische Entscheidung, diese Definition zu prüfen, auszuwählen oder umzusetzen. Die Abdeckung wird für jede Anforderung und jeden Snapshot getrennt gespeichert.

### Produktkatalogeintrag

Ein Produkteintrag beschreibt ein konkretes Herstellerprodukt und eine konkrete Version. Er ist nicht mit dem Taxonomiebereich `IP` (Information Products) gleichzusetzen.

Jeder Produkteintrag benötigt:

- Hersteller und Produktname,
- Quellenreferenz,
- Verifikationszeitpunkt.

Zusätzlich können Version, Produktfamilie, Lebenszyklus beziehungsweise Supportende, Lizenzmodell, Betriebsmodell, unterstützte Plattformen, Sicherheits- und Compliance-Merkmale sowie Kostenbasis gespeichert werden.

Ein Produktkandidat kann nur `SELECTED` werden, wenn seine Prüfung `CONFIRMED` ist und kein hartes Ausschlusskriterium vorliegt.

### Konflikthypothese

Die Konflikterkennung erzeugt nachvollziehbare Kandidaten zur Prüfung. Die ersten Regeln behandeln Hosting, Datenspeicherort, Lebenszyklus, Verfügbarkeit und Plattformvorgaben.

Jedes Ergebnis beginnt als `PROPOSED`. Ein Mensch muss es bestätigen, verwerfen oder lösen. Die Konflikterkennung unterstützt das Requirements Engineering, ersetzt es aber nicht.

## Bedienablauf

### 1. Projekt anlegen

`/projects` öffnen, **Neues Projekt** wählen und Projektschlüssel, Titel sowie optional eine Beschreibung angeben.

Der Projektschlüssel ist innerhalb des aktuellen Workspace eindeutig.

### 2. Anforderungen erfassen

Über **Neue Anforderung** wird eine Anforderung mit stabiler Identität und erster unveränderlicher Textversion angelegt.

Aus Dokumenten extrahierte Kandidaten können über die Projekt-API als getrennte Anforderungen importiert werden. Eine Zusammenführung muss ausdrücklich vom Aufrufer entschieden werden; das Portfolio verbindet Texte nie automatisch.

### 3. Anforderungen analysieren

**Analysieren** startet eine einzelne Anforderung, **Alle analysieren** das gesamte Projekt.

Die Zusammenfassung nennt erfolgreiche, teilweise erfolgreiche und fehlgeschlagene Items. Über **Snapshots** in der Anforderungszeile werden Historie, Scores, Zuordnungen, Fingerprints und Warnungen sichtbar.

### 4. Taxonomiezuordnungen prüfen

Im Snapshot wird für jede Zuordnung ein Maßnahmenstatus gewählt und bestätigt. Die menschliche Entscheidung bleibt getrennt vom generierten Score und dessen Begründung gespeichert.

### 5. Lösungen erfassen oder vorschlagen

Eine Lösung kann manuell angelegt und einem Projekt zugeordnet werden.

Nachdem eine bestätigte Lösung–Taxonomie-Abdeckung vorliegt, gleicht **Lösungen vorschlagen** wiederverwendbare Lösungen mit den aktuellen Anforderungszuordnungen ab. Die erzeugten Links bleiben `PROPOSED` und sind keine automatische Architekturfreigabe.

Jede Anforderung–Lösung-Verknüpfung muss nach Prüfung von Evidenz und Abdeckung bestätigt werden.

### 6. Produkte pflegen

Im Reiter **Produkte** wird ein quellengebundener Produkteintrag erstellt. Bei Bedarf wird eine evidenzgestützte Produkt–Taxonomie-Abdeckung ergänzt.

Danach kann das Produkt als Kandidat einer Projektlösung zugeordnet werden. Vorauswahl und endgültige Auswahl sind ausdrückliche Reviewentscheidungen.

### 7. Konflikte erkennen und prüfen

**Konflikte erkennen** ausführen. Jede Hypothese im Reiter Konflikte bestätigen, verwerfen oder lösen. Eine gelöste Hypothese kann eine Lösungsnotiz enthalten.

### 8. Konsolidierte Matrizen lesen

Das Portfolio zeigt:

- Anforderung–Taxonomie-Matrix,
- Lösung–Anforderung-Matrix,
- Lösung–Produkt-Matrix.

Die Werte sind Prozentangaben. Eine leere Zelle bedeutet „keine gespeicherte Beziehung“ und nicht automatisch einen Null-Score.

## REST-API

### Projekte und Anforderungen

```text
POST   /api/projects
GET    /api/projects
GET    /api/projects/{projectId}
PATCH  /api/projects/{projectId}

POST   /api/projects/{projectId}/requirements
POST   /api/projects/{projectId}/requirements/import
GET    /api/projects/{projectId}/requirements
GET    /api/projects/{projectId}/requirements/{requirementId}
PATCH  /api/projects/{projectId}/requirements/{requirementId}
POST   /api/projects/{projectId}/requirements/{requirementId}/versions
GET    /api/projects/{projectId}/requirements/{requirementId}/versions
```

### Analyse und Snapshots

```text
POST   /api/projects/{projectId}/analyses
POST   /api/projects/{projectId}/requirements/{requirementId}/analyses
GET    /api/projects/{projectId}/analysis-jobs
GET    /api/projects/{projectId}/analysis-jobs/{jobId}
POST   /api/projects/{projectId}/analysis-jobs/{jobId}/retry-failed

GET    /api/projects/{projectId}/requirements/{requirementId}/snapshots
GET    /api/projects/{projectId}/snapshots/{snapshotId}
GET    /api/projects/{projectId}/snapshots/diff?older=...&newer=...

PATCH  /api/projects/{projectId}/analysis-mappings/elements/{mappingId}
PATCH  /api/projects/{projectId}/analysis-mappings/relations/{mappingId}
```

### Lösungen

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

### Produkte

```text
POST   /api/products
GET    /api/products
GET    /api/products/{productId}
PATCH  /api/products/{productId}
POST   /api/products/{productId}/taxonomy-coverage

POST   /api/projects/{projectId}/solutions/{projectSolutionId}/products
GET    /api/projects/{projectId}/solutions/{projectSolutionId}/products
```

### Konsolidierung und Konflikte

```text
GET    /api/projects/{projectId}/portfolio
POST   /api/projects/{projectId}/conflicts/detect
GET    /api/projects/{projectId}/conflicts
PATCH  /api/projects/{projectId}/conflicts/{conflictId}
```

Alle Fehler werden als RFC-9457-`ProblemDetail` ausgegeben.

## Beispiele

### Alle Anforderungen getrennt analysieren

```bash
curl -X POST http://localhost:8080/api/projects/1/analyses \
  -H 'Content-Type: application/json' \
  -d '{
    "all": true,
    "provider": "GEMINI",
    "maxArchitectureNodes": 25,
    "idempotencyKey": "P-001-baseline-2026-08-02"
  }'
```

Wird derselbe Idempotenzschlüssel erneut verwendet, liefert die API den vorhandenen Job statt doppelte Snapshots zu erzeugen.

### Drei Kandidaten als drei Anforderungen importieren

```bash
curl -X POST http://localhost:8080/api/projects/1/requirements/import \
  -H 'Content-Type: application/json' \
  -d '{
    "analyzeAfterImport": true,
    "requirements": [
      {"requirementKey":"REQ-001","title":"Sichere Sprache","text":"..."},
      {"requirementKey":"REQ-002","title":"EU-Datenhaltung","text":"..."},
      {"requirementKey":"REQ-003","title":"Offline-Betrieb","text":"..."}
    ]
  }'
```

### Maßnahmenentscheidung für eine Zuordnung speichern

```bash
curl -X PATCH http://localhost:8080/api/projects/1/analysis-mappings/elements/42 \
  -H 'Content-Type: application/json' \
  -d '{
    "reviewStatus":"CONFIRMED",
    "actionStatus":"REUSE",
    "actionEvidence":"Vorhandener Servicekatalogeintrag SOL-004",
    "comment":"Durch den Projektarchitekten geprüft"
  }'
```

## Berechtigungen

- Lesen erfordert eine Anmeldung.
- Projektanalysen dürfen `USER`, `ARCHITECT` und `ADMIN` ausführen.
- Änderungen an Projekt, Anforderungen, Lösungen, Produkten und Reviews erfordern `ARCHITECT` oder `ADMIN`.
- Jeder Application Service erhält einen expliziten requestgebundenen `WorkspaceContext`.
- Ressourcen eines anderen Workspace erscheinen als nicht gefunden; ihre Existenz wird nicht offengelegt.

## Reproduzierbarkeit und Veralterung

Ein Snapshot wird in den Projektkennzahlen als veraltet gekennzeichnet, wenn:

- er älter ist als `taxonomy.portfolio.snapshot-stale-after-days` (Standard: 30 Tage), oder
- er eine nicht mehr aktuelle Anforderungsversion analysiert.

„Veraltet“ bedeutet nicht automatisch „ungültig“. Es bedeutet, dass das Projekt eine Neuanalyse mit aktueller Anforderung, Taxonomie und Prompt-Baseline prüfen sollte.

Der Snapshot-Vergleich unterscheidet:

- geänderte Scores,
- hinzugekommene oder entfallene Elemente,
- hinzugekommene oder entfallene Beziehungen,
- Änderung des Taxonomie-Fingerprints,
- Änderung des Prompt-Fingerprints,
- Providerwechsel.

## Aktuelle Grenzen

- Die Jobausführung ist aus Sicht des HTTP-Aufrufers synchron. Dauerhafte Item-Zustände und isolierter Retry sind bereits vorhanden; ein späterer Queue-/SSE-Executor kann auf demselben Modell aufsetzen.
- Produktdaten werden manuell kuratiert; ein Herstellerkatalog-Feed ist nicht enthalten.
- Konfliktregeln sind bewusst begrenzt und können Fehlalarme erzeugen oder semantische Konflikte übersehen.
- Das Produktportfolio dokumentiert geprüfte Kandidaten und Auswahlentscheidungen, führt aber keine Beschaffung aus.
- Ein Lösungsvorschlag setzt bestätigte Lösung–Taxonomie-Abdeckung voraus. Aus einem Taxonomiescore wird keine reale Lösung erfunden.

## Architekturentscheidung

Siehe [ADR 0001](../adr/0001-project-requirement-solution-portfolio.md) zu Modellgrenzen, verworfenen Alternativen und Konsequenzen.
