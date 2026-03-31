# Git-Integration

Der Taxonomy Architecture Analyzer verwendet **JGit**, um eine vollständige Git-Versionskontrolle für Architecture-DSL-Dokumente bereitzustellen. Das Git-Repository wird in der Anwendungsdatenbank gespeichert (kein Dateisystem erforderlich), was Ihnen Branching, Commit-Historie, Diff, Merge und Cherry-Pick-Funktionen für Ihre Architekturmodelle bietet.

> **📌 Für die grafische Benutzerführung** zu Varianten, Merge und Versionsverlauf siehe [Arbeitsbereich & Versionierung](WORKSPACE_VERSIONING.md).

## Inhaltsverzeichnis

- [Überblick](#überblick)
- [Datenmodell-Schichten](#datenmodell-schichten)
- [Repository-Architektur](#repository-architektur)
- [Branching](#branching)
- [Commit-Historie](#commit-historie)
- [Diff und Vergleich](#diff-und-vergleich)
- [Cherry-Pick](#cherry-pick)
- [Merge](#merge)
- [Konflikterkennung](#konflikterkennung)
- [Praxisnahe Workflow-Beispiele](#praxisnahe-workflow-beispiele)
- [Materialisierung](#materialisierung)
- [Veralterungsverfolgung](#veralterungsverfolgung)
- [Hypothesen-Lebenszyklus](#hypothesen-lebenszyklus)
- [Commit-Historie-Suche](#commit-historie-suche)
- [Taxonomie-Pflege](#taxonomie-pflege)
- [REST-API-Referenz (für Entwickler & Automatisierung)](#rest-api-referenz-für-entwickler--automatisierung)
- [Verwandte Dokumentation](#verwandte-dokumentation)

---

## Überblick

Architecture-DSL-Dokumente (`.taxdsl`-Dateien) werden in einem JGit-DFS-Repository (Distributed File System) gespeichert, das durch HSQLDB-Tabellen (`git_packs`, `git_reflog`) unterstützt wird. Jede Änderung an der DSL erzeugt einen Git-Commit mit Autor, Zeitstempel und Commit-Nachricht — und bietet damit eine vollständige Audit-Spur.

Der Git-Zustand wird über die UI-Statusleiste und die REST-API bereitgestellt, sodass Sie den Zustand des Repositorys überwachen, veraltete Projektionen erkennen und Merge-/Cherry-Pick-Operationen vor der Ausführung in der Vorschau betrachten können.

![Kontextleiste](../images/44-context-bar.png)

---

## Datenmodell-Schichten

Das System unterscheidet zwischen **importierten kanonischen Taxonomie-Daten** (in normalen Workflows schreibgeschützt) und **benutzergesteuerten Architektur-Overlays** (frei bearbeitbar). Das Verständnis dieser Trennung ist für produktives Arbeiten wesentlich.

### Schicht 1 — Importierte Taxonomie-Basislinie (schreibgeschützt)

| Attribut | Beschreibung |
|---|---|
| **Knoten-Codes** | Hierarchische Kennungen aus dem C3 Taxonomy Catalogue (z. B. `CP-1023`, `CR-1047`) |
| **Titel** | Offizielle englische Namen gemäß Katalogsveröffentlichung |
| **Beschreibungen** | Offizielle Beschreibungen aus dem Katalog |
| **Hierarchie** | Eltern-Kind-Struktur und Ebenen-Zuordnungen |

Diese Attribute werden beim Anwendungsstart aus dem Excel-Arbeitsbuch geladen. **Normale Benutzer-Workflows ändern sie nicht.** Wenn Taxonomie-Elemente in DSL-Dokumenten erscheinen, spiegeln ihre Titel und Beschreibungen die kanonischen Katalogswerte wider.

### Schicht 2 — Architektur-Beziehungen, Zuordnungen, Ansichten, Nachweise (benutzerseitig änderbar)

| Blocktyp | Zweck | Beispiel |
|---|---|---|
| `relation` | Gerichtete Verknüpfungen zwischen Elementen | `relation CP-1023 REALIZES CR-1047 { status: accepted; }` |
| `mapping` | Anforderung-zu-Element-Verknüpfungen | `mapping REQ-001 -> CP-1023 { score: 92; }` |
| `view` | Benannte Teilmengen für Diagramme | `view "CIS Overview" { include: CP-1023, CR-1047; }` |
| `evidence` | Begründung für eine Beziehung | `evidence E-001 { relation: CP-1023 REALIZES CR-1047; text: "..."; }` |

Dies sind die primären Objekte, die Benutzer in ihrer täglichen Architekturarbeit erstellen, ändern und versionskontrollieren.

### Schicht 3 — Lokale Erweiterungen und Annotationen (benutzerseitig änderbar)

Erweiterungsattribute mit dem Präfix `x-` können zu jedem Element- oder Beziehungsblock hinzugefügt werden. Sie werden bei der Round-Trip-Serialisierung beibehalten, aber vom System nicht validiert.

```
element CP-1023 type Capability {
  title: "Secure Voice";                // ← kanonisch (nicht ändern)
  x-alias: "SecVoice";                  // ← lokale Annotation (benutzerdefiniert)
  x-owner: "CIS Division";             // ← lokale Metadaten (benutzerdefiniert)
  x-criticality: "high";               // ← lokale Metadaten (benutzerdefiniert)
}
```

Häufige Verwendungszwecke: projektspezifische Aliase, Zuständigkeitsannotationen, Kritikalitätsbewertungen, Prüfnotizen.

### Schicht 4 — Taxonomie-Pflege (eingeschränkt)

Das Ändern kanonischer Taxonomie-Daten (Titel, Beschreibungen, Hierarchie) ist eine privilegierte Operation, die Taxonomie-Administratoren vorbehalten ist. Siehe [Taxonomie-Pflege](#taxonomie-pflege) für Details.

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

Navigieren Sie zu **Versionen → Varianten** und klicken Sie auf **🌿 Neue Variante**. Geben Sie den Namen ein (z.B. `feature/new-service`) und bestätigen Sie mit **Erstellen**.

![Varianten-Erstellung](../images/46-variant-creation-modal.png)

Das Varianten-Panel zeigt alle vorhandenen Varianten als Karten:

![Varianten-Browser](../images/47-variants-browser-tab.png)

Mit wachsendem Projekt existieren mehrere Branches nebeneinander — Feature-Branches, Review-Branches und Hotfix-Branches — jeder mit eigener Commit-Historie:

![Varianten-Browser mit mehreren Branches](../images/72-rich-variants-browser.png)

<details>
<summary>🔧 REST-API-Äquivalent (für Automatisierung)</summary>

```
POST /api/dsl/branches
{
  "name": "feature/new-service",
  "startPoint": "draft"
}
```

</details>

Der aktive Branch für die Materialisierung wird über die Einstellung `dsl.default-branch` konfiguriert (siehe [Einstellungen](PREFERENCES.md)).

---

## Commit-Historie

Jede DSL-Änderung erzeugt einen Git-Commit mit:

- **SHA** — Eindeutiger Commit-Bezeichner
- **Autor** — Authentifizierter Benutzer, der die Änderung vorgenommen hat
- **Nachricht** — Beschreibung der Änderung
- **Zeitstempel** — Wann der Commit erstellt wurde

Navigieren Sie zu **Versionen → Verlauf**. Die Zeitleiste zeigt alle Commits mit Nachricht, Autor, Zeitstempel und Hash.

![Versionsverlauf-Zeitleiste](../images/66-versions-timeline.png)

In einem realen Projekt wächst die Zeitleiste, während sich die Architektur weiterentwickelt — mit Commits mehrerer Teammitglieder über verschiedene Branches hinweg. Der folgende Screenshot zeigt eine realistische Zeitleiste mit zahlreichen Commits für Architekturverfeinerung, Beziehungsreviews und Feature-Branches:

![Reichhaltige Versionsverlauf-Zeitleiste mit mehreren Branches](../images/71-rich-version-timeline.png)

<details>
<summary>🔧 REST-API-Äquivalent (für Automatisierung)</summary>

```
GET /api/dsl/history?branch=draft&limit=20
```

</details>

---

## Diff und Vergleich

Zwei Diff-Modi stehen zur Verfügung:

Klicken Sie auf **🔍 Vergleichen** in der Kontextleiste oder im Verlauf. Die Vergleichsansicht zeigt drei Ebenen: Zusammenfassung, Drei-Spalten-Raster und DSL-Diff.

![Vergleichsdialog](../images/48-compare-modal-branches.png)

![Diff-Ansicht](../images/68-diff-view.png)

<details>
<summary>🔧 REST-API-Äquivalent (für Automatisierung)</summary>

| Modus | Endpunkt | Ausgabe |
|---|---|---|
| **Semantisch** | `GET /api/dsl/diff?from={sha}&to={sha}` | Strukturiertes JSON mit hinzugefügten, entfernten und geänderten Elementen und Beziehungen |
| **Unified-Text** | `GET /api/dsl/text-diff?from={sha}&to={sha}` | Standard-Unified-Diff-Format (Patch) |

Sie können auch zwischen Branches vergleichen:

```
GET /api/dsl/diff-branches?from=draft&to=main
```

</details>

---

## Cherry-Pick

Einen bestimmten Commit von einem Branch auf einen anderen portieren:

Im **Versionsverlauf** wählen Sie den gewünschten Commit und klicken Sie auf die Übertragen-Aktion. Das System zeigt eine Vorschau mit Konfliktprüfung.

### Schritt für Schritt: Cherry-Pick eines Commits

**1. Vorher** — Der draft-Branch hat seine eigene Historie. Sie möchten einen bestimmten geprüften Commit aus dem `review`-Branch übernehmen:

![Zeitleiste vor Cherry-Pick](../images/73-cherry-pick-before.png)

**2. Vorschau** — Klicken Sie auf die Übertragen-Aktion beim gewünschten Commit. Das Vorschau-Modal zeigt, was sich ändert:

![Cherry-Pick-Vorschau](../images/62-cherry-pick-preview-modal.png)

**3. Nachher** — Nach Bestätigung erscheint der cherry-gepickte Commit als neuer Commit auf dem draft-Branch:

![Zeitleiste nach Cherry-Pick](../images/74-cherry-pick-after.png)

<details>
<summary>🔧 REST-API-Äquivalent (für Automatisierung)</summary>

```
POST /api/dsl/cherry-pick
{
  "commitId": "abc1234...",
  "targetBranch": "draft"
}
```

</details>

Die Operation verwendet intern Drei-Wege-Merge-Logik. Verwenden Sie zuerst den Vorschau-Endpunkt, um auf Konflikte zu prüfen (siehe [Konflikterkennung](#konflikterkennung)).

---

## Merge

Zwei Branches mittels Drei-Wege-Merge zusammenführen:

Im **Varianten-Panel** klicken Sie auf **🔀 Integrieren** bei der gewünschten Variante. Ein Vorschau-Modal zeigt die Zusammenfassung der Änderungen.

![Merge-Vorschau](../images/60-merge-preview-modal.png)

![Fast-Forward-Merge](../images/61-merge-preview-fast-forward.png)

Nach einer erfolgreichen Zusammenführung wird ein Bestätigungs-Toast angezeigt:

![Erfolgreiche Zusammenführung](../images/58-merge-success-toast.png)

<details>
<summary>🔧 REST-API-Äquivalent (für Automatisierung)</summary>

```
POST /api/dsl/merge
{
  "fromBranch": "feature/new-service",
  "intoBranch": "draft"
}
```

</details>

Die Merge-Strategie ist RECURSIVE (Standard-Git-Verhalten). Fast-Forward-Merges werden durchgeführt, wenn der Ziel-Branch ein direkter Vorgänger des Quell-Branches ist.

---

## Konflikterkennung

Vor der Ausführung eines Merge oder Cherry-Pick können Sie die Operation in der Vorschau betrachten, um auf Konflikte zu prüfen:

Bei der Zusammenführung prüft das System automatisch auf Konflikte. Bei Konflikten öffnet sich der Konfliktlösungs-Dialog mit einer Seite-an-Seite-Ansicht der divergierenden Inhalte.

![Konflikt-Dialog](../images/52-merge-conflict-modal.png)

![Konflikt gelöst](../images/53-merge-conflict-resolved.png)

### Merge-Vorschau

<details>
<summary>🔧 REST-API-Äquivalent (für Automatisierung)</summary>

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

</details>

### Cherry-Pick-Vorschau

<details>
<summary>🔧 REST-API-Äquivalent (für Automatisierung)</summary>

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

</details>

### Sicherheitsprüfung für Operationen

<details>
<summary>🔧 REST-API-Äquivalent (für Automatisierung)</summary>

```
GET /api/dsl/operation/check?branch=draft
```

</details>

Der `RepositoryStateGuard` prüft, ob eine Schreiboperation auf dem gegebenen Branch sicher ausgeführt werden kann.

---

## Praxisnahe Workflow-Beispiele

Die folgenden Beispiele zeigen typische Benutzer-Workflows. Beachten Sie, dass **kanonische Taxonomie-Titel unverändert bleiben** — Benutzer ändern Beziehungen, Zuordnungen, Ansichten, lokale Erweiterungen und Beziehungsstatus.

### Beispiel: Feature-Branch mit neuen Beziehungen

Ein Benutzer erstellt einen Feature-Branch, um neue Architektur-Beziehungen vorzuschlagen:

**Basis (draft-Branch):**
```
element CP-1023 type Capability {
  title: "Secure Voice";
  description: "Encrypted voice communication";
}

element CR-1047 type CoreService {
  title: "Core Communication Services";
}

relation CP-1023 REALIZES CR-1047 {
  status: accepted;
}
```

**Feature-Branch (feature/voice-gateway):**
```
element CP-1023 type Capability {
  title: "Secure Voice";
  description: "Encrypted voice communication";
  x-alias: "SecVoice";
}

element CR-1047 type CoreService {
  title: "Core Communication Services";
}

element CO-1011 type Component {
  title: "Voice Gateway";
  description: "SIP/RTP gateway for voice traffic";
}

relation CP-1023 REALIZES CR-1047 {
  status: accepted;
}

relation CO-1011 USES CR-1047 {
  status: proposed;
}
```

**Was sich geändert hat** (typische Diff-Ausgabe):
- ✅ Lokaler Alias `x-alias: "SecVoice"` zu CP-1023 hinzugefügt
- ✅ Neues Element CO-1011 (Voice Gateway) hinzugefügt
- ✅ Neue Beziehung CO-1011 → CR-1047 hinzugefügt
- ❌ Keine kanonischen Titel oder Beschreibungen wurden geändert

### Beispiel: Cherry-Pick einer Beziehungsstatus-Änderung

Ein Reviewer akzeptiert eine vorgeschlagene Beziehung auf dem `review`-Branch. Die Änderung wird per Cherry-Pick auf `draft` übertragen:

Der Reviewer öffnet den **Versionsverlauf** des review-Branches, findet den Commit `a3f8c2d` und überträgt ihn per Klick auf die Übertragen-Aktion in den `draft`-Branch.

![Cherry-Pick-Erfolg](../images/59-cherry-pick-success-toast.png)

Der Cherry-Pick-Commit ändert nur den Beziehungsstatus:
```diff
 relation CO-1011 USES CR-1047 {
-  status: proposed;
+  status: accepted;
 }
```

### Beispiel: Merge mit Konflikt beim Beziehungsstatus

Wenn zwei Branches denselben Beziehungsstatus ändern, tritt ein Konflikt auf:

- **Ours (draft):** `status: proposed;`
- **Theirs (feature-voice):** `status: accepted;`

Die Benutzeroberfläche zur Konfliktlösung ermöglicht die Auswahl des gewünschten Status oder die manuelle Erstellung des endgültigen Inhalts. Kanonische Taxonomie-Elementtitel sind in normalen Workflows nie Teil solcher Konflikte.

### Beispiel: Ansichts- und Zuordnungsänderungen

Benutzer ändern häufig Architekturansichten und Anforderungszuordnungen:

```diff
+view "CIS Architecture" {
+  include: CP-1023, CR-1047, CO-1011;
+  layout: hierarchical;
+}
+
+mapping REQ-001 -> CP-1023 {
+  score: 92;
+  rationale: "Core secure voice capability";
+}
```

---

## Materialisierung

DSL-Dokumente werden in die Anwendungsdatenbank **materialisiert**. Dadurch werden `TaxonomyRelation`-Entitäten aus DSL-Beziehungen erstellt, die im Graph Explorer, in den Beziehungsvorschlägen und in der Architekturansicht sichtbar werden.

Nach Änderungen zeigt die **Git-Statusleiste** den Status ‚Projektion veraltet'. Klicken Sie auf **Materialisieren** in der Statusleiste, um die Datenbank zu aktualisieren.

<details>
<summary>🔧 REST-API-Äquivalent (für Automatisierung)</summary>

Zwei Materialisierungsmodi stehen zur Verfügung:

| Modus | Endpunkt | Beschreibung |
|---|---|---|
| **Vollständig** | `POST /api/dsl/materialize` | Ersetzt alle Beziehungen durch DSL-Inhalte |
| **Inkrementell** | `POST /api/dsl/materialize-incremental` | Wendet nur das Delta zwischen zwei Versionen an |

</details>

Nach der Materialisierung zeichnet der `RepositoryStateService` den Projektions-Commit auf, um zu verfolgen, ob die Datenbank mit dem Git-HEAD synchron ist.

---

## Veralterungsverfolgung

Das System verfolgt, ob die Datenbankprojektion und der Suchindex mit dem Git-HEAD synchron sind:

Die Git-Statusleiste am oberen Rand zeigt automatisch den Synchronisationsstatus. Bei Veralterung erscheint ein Warnindikator.

![Git-Statusleiste](../images/43-git-status-bar.png)

| Feld | Bedeutung |
|---|---|
| `projectionStale` | Datenbankbeziehungen weichen vom Git-HEAD ab |
| `indexStale` | Suchindex weicht vom Git-HEAD ab |

**Veralterungslogik:** Wenn der SHA des zuletzt materialisierten Commits mit dem SHA des aktuellen HEAD-Commits übereinstimmt, ist die Projektion **nicht veraltet**. Andernfalls ist sie veraltet und sollte erneut materialisiert werden.

<details>
<summary>🔧 REST-API-Äquivalent (für Automatisierung)</summary>

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

</details>

Die Benutzeroberfläche pollt `/api/git/state` alle 10 Sekunden, um einen Statusindikator anzuzeigen, wenn die Projektion veraltet ist.

---

## Hypothesen-Lebenszyklus

Beziehungen, die während der LLM-Analyse generiert werden, werden als **Hypothesen** gespeichert — vorläufige Beziehungen, die eine menschliche Überprüfung erfordern, bevor sie dauerhaft werden:

```
PROVISIONAL  →  ACCEPTED  (erstellt TaxonomyRelation)
             →  REJECTED  (als abgelehnt markiert)
```

Das Flag `appliedInCurrentAnalysis` ermöglicht es, eine Hypothese für die
aktuelle Analyse wirksam werden zu lassen, **ohne** dadurch eine permanente
`TaxonomyRelation` zu erzeugen oder einen Accepted-Branch-Commit auszulösen.
Das Flag selbst wird zusammen mit der `RelationHypothesis` in der Datenbank
gespeichert, ist aber kein zusätzlicher Lebenszyklus-Status wie
PROVISIONAL/ACCEPTED/REJECTED.

Die Hypothesen-API (`/api/dsl/hypotheses`) ermöglicht das Abfragen, Akzeptieren und Ablehnen von Hypothesen, wobei für jede unterstützende Nachweise verfügbar sind.

---

## Taxonomie-Pflege

Das Ändern kanonischer Taxonomie-Daten (Knotentitel, Beschreibungen, Hierarchiestruktur) ist eine **privilegierte administrative Operation**, die von normaler Architekturarbeit getrennt ist.

### Wann Taxonomie-Pflege erforderlich ist

- Korrektur eines Fehlers in einem importierten Katalogtitel
- Aktualisierung von Beschreibungen entsprechend einer neuen Katalogrevision
- Hinzufügen lokal definierter Erweiterungsknoten, die im veröffentlichten Katalog nicht vorhanden sind

### Unterschied zu normalen Workflows

| Aspekt | Normale Architekturarbeit | Taxonomie-Pflege |
|---|---|---|
| **Was sich ändert** | Beziehungen, Zuordnungen, Ansichten, Nachweise, lokale Erweiterungen | Knotentitel, Beschreibungen, Hierarchie |
| **Wer führt es durch** | Jeder Architekt oder Analyst | Taxonomie-Administrator |
| **Häufigkeit** | Täglich | Selten (pro Katalogrevision) |
| **Prüfprozess** | Standard-Branch-/Merge-Workflow | Administrative Prüfung erforderlich |
| **Umfang** | Benutzer-Arbeitsbereich oder Feature-Branch | Gemeinsame Basislinie für alle Benutzer |

### Empfohlener Prozess

1. Einen dedizierten Branch erstellen (z. B. `taxonomy-update/2026-q2`)
2. Element-Titel oder -Beschreibungen in der DSL ändern
3. Änderungen mit dem Taxonomie-Governance-Team prüfen
4. Nach Genehmigung in den gemeinsamen Branch zusammenführen
5. Erneut materialisieren, um Änderungen an alle Arbeitsbereiche zu propagieren

> **Wichtig:** Normale Benutzer-Workflows sollten `x-`-Erweiterungsattribute (z. B. `x-alias`, `x-note`) für lokale Anpassungen verwenden, anstatt kanonische Titel direkt zu ändern.

---

## Commit-Historie-Suche

Die Commit-Historie wird in Hibernate Search für die Volltextsuche indexiert. Sie können:

- Über alle Commit-Nachrichten und Änderungsinhalte suchen
- Alle Commits finden, die ein bestimmtes Element betroffen haben
- Alle Commits finden, die eine bestimmte Beziehung betroffen haben
- Aggregierte Änderungshistorie für ein Element anzeigen

---

## REST-API-Referenz (für Entwickler & Automatisierung)

Die folgenden Endpunkte sind für die programmatische Integration und Automatisierung gedacht. Für die tägliche Arbeit verwenden Sie die grafische Oberfläche (siehe [Arbeitsbereich & Versionierung](WORKSPACE_VERSIONING.md)).

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

- [Workspace- & Versionierungshandbuch](WORKSPACE_VERSIONING.md) — benutzerorientierter Leitfaden für die Workspace-UI (Kontextleiste, Verlauf, Varianten, Sync)
- [Repository-Topologie](REPOSITORY_TOPOLOGY.md) — Workspace-Provisionierungsmodell, Topologiemodi und Datenisolation
- [Benutzerhandbuch](USER_GUIDE.md) — Abschnitt Architecture DSL (§11g)
- [Architektur](ARCHITECTURE.md) — DSL-Speicherarchitektur
- [Konzepte](CONCEPTS.md) — DSL, Hypothesen und das kanonische Modell
- [Einstellungen](PREFERENCES.md) — Konfiguration von `dsl.default-branch` und Remote-Push-Einstellungen
- [Framework-Import](FRAMEWORK_IMPORT.md) — Wie importierte Dateien als DSL-Dokumente gespeichert werden
