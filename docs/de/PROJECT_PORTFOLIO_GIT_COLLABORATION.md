# Gemeinsames Projektportfolio über Git

## Zweck

Mehrere Personen können Anforderungen, Lösungsentscheidungen und Produktbewertungen unabhängig voneinander modellieren und anschließend in ein gemeinsames Architekturmodell integrieren, ohne Analysen zu vermischen oder die Beiträge anderer Personen zu überschreiben.

Dafür werden das bestehende Workspace- und Branch-Modell sowie der Git-basierte Portfoliovertrag aus ADR 0002 verwendet.

## Architekturlebensdauer, Beiträge und Historie

Das Repository identifiziert die fortbestehende Architektur, nicht die Laufzeit
eines einzelnen Projekts. Ein Projektabschluss beendet oder ersetzt die Architektur
nicht. Ein Folgeprojekt kann eine ursprüngliche Anforderung ändern oder wiederverwenden;
ihre projektqualifizierte Identität bleibt erhalten und das spätere Projekt wird
als Beitrag dokumentiert. `NEW`, `CHANGE` und `LINK` sind ausdrückliche
[Eingangsentscheidungen](PROJECT_REQUIREMENT_PORTFOLIO.md#requirement-intake).

Aktueller Text, unveränderliche Analysesnapshots und geprüfte Umsetzung sind
verschiedene Datensätze. Eine Umsetzungsprüfung bindet exakte Textversion/Hash an
Architekturabhängigkeiten samt Hashes, beitragendes Projekt, Benutzer und Begründung.
Spätere Text- oder Abhängigkeitsänderungen benötigen erneute Prüfung; die frühere
Bewertung bleibt nachvollziehbar. Ein älterer Git-Checkpoint oder eine semantische
Revision ist schreibgeschützt und verwendet die eigene Quelle ohne heutige
Portfolioüberblendung. Er kann Version 1 zeigen, während der aktuelle
Anforderungskopf Version 2 enthält.

Die [Anforderungshistorie](PROJECT_REQUIREMENT_PORTFOLIO.md#requirement-history)
durchsucht erreichbare Git-Ancestry über natives JGit und `jgit-storage-hibernate`.
Sie benötigt keine Shell-Git-Kommandos im Produkt und keine zweite Audit-Datenbank.
Die Antwort nennt erfassten HEAD, tatsächliche Eltern, Quellen, Signaturbelege und
begrenzte Abdeckung. Mit demselben HEAD fortsetzen, nach seiner Änderung neu beginnen.
Eine leere begrenzte Seite oder nicht verfügbarer Speicher beweist keine fehlende Historie.

Angenommene Lifecycle-Änderungen bereiten einen Checkpoint in derselben Transaktion
wie Fachbeitrag und Editorjournal vor. Die Git-Veröffentlichung folgt danach.
`CHECKPOINT_PENDING` erhält die über `pendingCheckpointId` sichtbare Absicht;
exakter Retry oder vorhandene Resume-Operation schließen sie ohne wiederholte
Fachänderung ab. Ein geänderter Git-HEAD kann einen ausdrücklichen
[Versionsabgleich](ARCHITECTURE_EDITOR.md#editor-version-reconciliation) erfordern.
Basis/lokale/eingehende Quelle prüfen, jeden Konflikt entscheiden und das Ergebnis
vor der Annahme erneut vorschauen. Wiederholtes Speichern allein löst keine Konflikte.

Der Abgleich erhält unveränderliche Anforderungsversionen und prüft kanonischen
Titel/Text/Version/Hash gegen die portable aktuelle Version. Geänderte verwaltete
Portfolioblöcke werden vor der Git-Veröffentlichung atomar mit dem Editorjournal
materialisiert; exakte Retries materialisieren nicht doppelt. Nicht unterstützte
Auslassungen von Fachblöcken erfordern `LOCAL` und ausdrückliche Archivierung.
Unverträgliche Portfolioentscheidungen setzen die Annahme zurück; ein späterer
Git-Speicherfehler bleibt ein Fall für die Checkpoint-Wiederaufnahme. Siehe den
[API-Vertrag](PROJECT_PORTFOLIO_API.md#editor-version-reconciliation-api).

Die Integration prüft außerdem unveränderliche Anforderungsversionen der exakten
Zieldatenbank, selbst wenn diese Versionen nicht mehr in der lokalen DSL stehen.
Widersprüchlicher Text, Hashes oder mitgelieferte Herkunft dürfen vorhandene Versionen
nicht überschreiben; bekannter Inhalt erhält nicht stillschweigend eine andere
Versionsnummer. Diese Vorprüfung erfolgt, bevor die vorgeschlagene Integration
Zielmodell- oder Tracking-Referenzen fortschreibt.

## Empfohlener Ablauf

```text
Gemeinsamer Architekturbranch
        │
        ├── Workspace/Branch Alice
        │     ├── P-001 / REQ-A-001
        │     ├── bestätigte Taxonomiezuordnungen
        │     └── Lösung SOL-A / Produktkandidat PRD-A
        │
        ├── Workspace/Branch Bob
        │     ├── P-001 / REQ-B-001
        │     ├── bestätigte Taxonomiezuordnungen
        │     └── Lösung SOL-B / Produktkandidat PRD-B
        │
        └── Pull / Review / semantischer Merge / Publish
              └── ein gemeinsames Architektur- und Lösungsmodell
```

1. Jede Person synchronisiert zunächst den gemeinsamen Architekturstand.
2. Anforderungen, Lösungsvorschläge und Produktbewertungen werden im eigenen Workspace erfasst oder geändert.
3. Vor Pull oder Publish projiziert die Anwendung das relationale Portfolio automatisch in `architecture.taxdsl`.
4. Pull und Publish verwenden einen dreiwegigen, blocksemantischen Merge.
5. Die zusammengeführte DSL wird anschließend in die Zielprojektion materialisiert.
6. Nur echte Eigenschaftskonflikte müssen fachlich geprüft werden.

## Git-basierte Blöcke

Der dauerhafte Kollaborationsvertrag enthält:

### Projekte und Anforderungen

- `project`
- `projectRequirement`
- `requirementVersion`
- kanonische `requirement`-Blöcke
- kanonische `mapping`-Blöcke zwischen Anforderung und Taxonomieknoten

### Lösungen und Produkte

- `solutionDefinition`
- `solutionTaxonomyCoverage`
- `projectSolutionDecision`
- `requirementSolutionDecision`
- `productDefinition`
- `productTaxonomyCoverage`
- `solutionProductDecision`

Alle Verknüpfungen verwenden stabile fachliche Schlüssel wie `P-001`, `REQ-A-001`, `SOL-001` und `PRD-001`. Datenbank-Primärschlüssel sind nicht Bestandteil des Git-Vertrags.

Beispiel:

```text
project P-001 {
  title: "Gemeinsame Zielarchitektur";
  status: ACTIVE;
  x-portfolio-managed: true;
}

projectRequirement P-001 REQ-A-001 {
  title: "Sichere Sprachkommunikation";
  owner: "alice";
  reviewStatus: CONFIRMED;
  currentVersionNumber: 2;
  x-portfolio-managed: true;
}

requirementVersion P-001 REQ-A-001 2 {
  text: "Die Lösung muss verschlüsselte Sprachkommunikation bereitstellen.\nSie muss auch bei Netzausfall weiterarbeiten.";
  contentHash: "...";
  createdBy: "alice";
  x-portfolio-managed: true;
}

mapping P-001__REQ-A-001 -> CR-1047 {
  score: 83;
  source: "analysis-snapshot-id";
  x-review-status: "CONFIRMED";
  x-action-status: "REUSE";
  x-portfolio-managed: true;
}

solutionDefinition SOL-001 {
  title: "Sicherer Kommunikationsdienst";
  solutionType: "SERVICE";
  lifecycleStatus: "ACTIVE";
  x-portfolio-managed: true;
}

projectSolutionDecision P-001 SOL-001 {
  status: "SELECTED";
  actionStatus: "REUSE";
  priority: 90;
  x-portfolio-managed: true;
}

productDefinition PRD-001 {
  manufacturer: "Beispielhersteller";
  productName: "Beispielprodukt";
  editionVersion: "2026.1";
  sourceReference: "Geprüfte Herstellerdokumentation";
  verifiedAt: "2026-08-02T18:00:00Z";
  x-portfolio-managed: true;
}

solutionProductDecision P-001 SOL-001 PRD-001 {
  coveragePercent: 92;
  reviewStatus: "CONFIRMED";
  selectionStatus: "SELECTED";
  x-portfolio-managed: true;
}
```

Mehrzeilige Texte werden als `\n`, `\r` und `\t` in einer physischen DSL-Zeile gespeichert und beim Einlesen exakt wiederhergestellt. Dadurch bleiben Git-Diffs stabil, ohne Inhalt zu verlieren.

## Mergeverhalten

Unabhängige Anforderungen, Lösungen und Produkte werden automatisch vereinigt. Gleichzeitig geänderte, aber unterschiedliche Eigenschaften desselben Blocks können ebenfalls automatisch kombiniert werden.

Eine fachliche Prüfung ist erforderlich bei:

- unterschiedlichen Texten derselben Anforderungsversion,
- Löschen auf einer Seite und Ändern auf der anderen,
- widersprüchlichen Prüf- oder Maßnahmenentscheidungen desselben Mappings,
- unterschiedlicher Auswahl desselben Produkts für dieselbe Projektlösung,
- unterschiedlichen Werten derselben Projekt-, Anforderungs-, Lösungs- oder Produkteigenschaft.

Der Konflikt benennt den exakten Block und die Eigenschaft:

```text
projectRequirement P-001 REQ-A-001:title
mapping P-001__REQ-A-001 -> CR-1047:x-action-status
projectSolutionDecision P-001 SOL-001:actionStatus
solutionProductDecision P-001 SOL-001 PRD-001:selectionStatus
```

## Pull und Publish zwischen Workspaces

Für getrennte Workspace-Repositories verwaltet die Anwendung einen privaten Branch `sync-base`. Dieser enthält den zuletzt gemeinsam integrierten semantischen Stand und dient beim nächsten Pull oder Publish als Merge-Basis.

Ein Pull verwendet:

```text
Basis   = workspace/sync-base
Unser   = workspace/<aktueller Branch>
Andere  = shared/draft
```

Beim Publish werden Quelle und Ziel vertauscht. Das frühere vollständige Ersetzen der Zieldatei wird nicht mehr verwendet. Nach einem Publish erhält auch der lokale Workspace den vollständig zusammengeführten Stand, damit der nächste Pull auf derselben Basis beginnt.

Die normalen Workspace-Endpunkte verwenden diesen Ablauf automatisch:

```text
POST /api/workspace/sync-from-shared
POST /api/workspace/publish
POST /api/workspace/resolve-diverged
```

Diese Operationen stehen den Rollen `ARCHITECT` und `ADMIN` zur Verfügung. Reine Leser dürfen keine Architekturänderungen veröffentlichen.

## Explizite Portfolio-Git-Operationen

```text
GET  /api/projects/git/export
POST /api/projects/git/commit?branch=draft
POST /api/projects/git/materialize?branch=draft
POST /api/projects/git/merge
```

Merge-Anfrage:

```json
{
  "sourceBranch": "alice-requirements",
  "targetBranch": "integration"
}
```

Beide Branches müssen im ausgewählten Workspace-Repository liegen. Alternativ akzeptiert
der Endpunkt `?source=alice-requirements&target=integration` mit einer optionalen JSON-Nachricht.

Befinden sich beide Branches im selben Repository, erzeugt auch der semantische Fallback einen echten Merge-Commit mit zwei Eltern.

## Was nicht in Git gespeichert wird

Operative und umfangreiche Daten bleiben relational:

- laufende Analysejobs,
- Retry- und Rate-Limit-Zustände,
- vollständige große Analyse-Payloads,
- Caches und Diagnosedaten.

Snapshot-IDs können als Herkunftshinweis gespeichert werden, werden beim Import in einen anderen Workspace aber nicht als fremde Datenbankbeziehung vorausgesetzt. Die DSL enthält stabile Identitäten, bestätigte Architekturentscheidungen, Quellenangaben und Hashes. Dadurch bleibt das gemeinsame Architektur-, Lösungs- und Produktmodell nachvollziehbar und überprüfbar.
