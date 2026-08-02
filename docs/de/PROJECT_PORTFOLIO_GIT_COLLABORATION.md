# Gemeinsame Projektanforderungen über Git

## Zweck

Mehrere Personen können Anforderungen unabhängig voneinander modellieren und anschließend in ein gemeinsames Architekturmodell integrieren, ohne Analysen zu vermischen oder die Beiträge anderer Personen zu überschreiben.

Dafür werden das bestehende Workspace- und Branch-Modell sowie der Git-basierte Portfoliovertrag aus ADR 0002 verwendet.

## Empfohlener Ablauf

```text
Gemeinsamer Architekturbranch
        │
        ├── Workspace/Branch Alice
        │     ├── P-001 / REQ-A-001
        │     └── bestätigte Taxonomiezuordnungen
        │
        ├── Workspace/Branch Bob
        │     ├── P-001 / REQ-B-001
        │     └── bestätigte Taxonomiezuordnungen
        │
        └── Pull / Review / semantischer Merge / Publish
              └── ein gemeinsames Architekturmodell
```

1. Jede Person synchronisiert zunächst den gemeinsamen Architekturstand.
2. Anforderungen werden im eigenen Workspace erfasst oder geändert.
3. Vor Pull oder Publish projiziert die Anwendung das relationale Portfolio automatisch in `architecture.taxdsl`.
4. Pull und Publish verwenden einen dreiwegigen, blocksemantischen Merge.
5. Die zusammengeführte DSL wird anschließend in die Zielprojektion materialisiert.
6. Nur echte Eigenschaftskonflikte müssen fachlich geprüft werden.

## Git-basierte Blöcke

Der dauerhafte Kollaborationsvertrag enthält:

- `project`
- `projectRequirement`
- `requirementVersion`
- kanonische `requirement`-Blöcke
- kanonische `mapping`-Blöcke zwischen Anforderung und Taxonomieknoten

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
  x-portfolio-managed: true;
}

requirementVersion P-001 REQ-A-001 2 {
  text: "Die Lösung muss verschlüsselte Sprachkommunikation bereitstellen.";
  contentHash: "...";
  createdBy: "alice";
  x-portfolio-managed: true;
}

requirement P-001__REQ-A-001 {
  title: "Sichere Sprachkommunikation";
  text: "Die Lösung muss verschlüsselte Sprachkommunikation bereitstellen.";
  x-project-key: "P-001";
  x-requirement-key: "REQ-A-001";
  x-portfolio-managed: true;
}

mapping P-001__REQ-A-001 -> CR-1047 {
  score: 83;
  source: "analysis-snapshot-id";
  x-review-status: "CONFIRMED";
  x-action-status: "REUSE";
  x-portfolio-managed: true;
}
```

## Mergeverhalten

Unabhängige Anforderungen werden automatisch vereinigt. Gleichzeitig geänderte, aber unterschiedliche Eigenschaften desselben Projekts oder derselben Anforderung können ebenfalls automatisch kombiniert werden.

Eine fachliche Prüfung ist erforderlich bei:

- unterschiedlichen Texten derselben Anforderungsversion,
- Löschen auf einer Seite und Ändern auf der anderen,
- widersprüchlichen Prüf- oder Maßnahmenentscheidungen desselben Mappings,
- unterschiedlichen Werten derselben Projekt- oder Anforderungseigenschaft.

Der Konflikt benennt den exakten Block und die Eigenschaft:

```text
projectRequirement P-001 REQ-A-001:text
mapping P-001__REQ-A-001 -> CR-1047:x-action-status
```

## Pull und Publish zwischen Workspaces

Für getrennte Workspace-Repositories verwaltet die Anwendung einen privaten Branch `sync-base`. Dieser enthält den zuletzt gemeinsam integrierten semantischen Stand und dient beim nächsten Pull oder Publish als Merge-Basis.

Ein Pull verwendet:

```text
Basis   = workspace/sync-base
Unser   = workspace/main
Andere  = shared/draft
```

Beim Publish werden Quelle und Ziel vertauscht. Das frühere vollständige Ersetzen der Zieldatei wird nicht mehr verwendet.

## REST-Operationen

```text
GET  /api/projects/git/export
POST /api/projects/git/commit?branch=draft
POST /api/projects/git/materialize?branch=draft
POST /api/projects/git/merge
```

Merge-Anfrage:

```json
{
  "fromBranch": "alice-requirements",
  "intoBranch": "integration"
}
```

Befinden sich beide Branches im selben Repository, erzeugt auch der semantische Fallback einen echten Merge-Commit mit zwei Eltern.

## Was nicht in Git gespeichert wird

Operative und umfangreiche Daten bleiben relational:

- laufende Analysejobs,
- Retry- und Rate-Limit-Zustände,
- vollständige große Analyse-Payloads,
- Caches und Diagnosedaten.

Die DSL enthält stabile Identitäten, aktuelle Architekturentscheidungen, Snapshot-Referenzen und Hashes. Dadurch bleibt das gemeinsame Architekturmodell nachvollziehbar und überprüfbar.
