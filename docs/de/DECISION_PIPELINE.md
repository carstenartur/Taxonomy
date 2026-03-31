# Entscheidungspipeline — Technische Referenz

> **Zielgruppe:** Entwickler und Systemintegratoren, die verstehen müssen,
> wie eine Geschäftsanforderung in bewertete Knoten, Architekturansichten
> und exportierbare Diagramme umgewandelt wird.
>
> Dieses Dokument beschreibt **implementiertes Verhalten** zum Stand
> 31.03.2026. Mit ⚠️ markierte Abschnitte beschreiben unvollständige
> oder geplante Funktionen.

---

## Inhaltsverzeichnis

- [Terminologie](#terminologie)
- [Pipeline-Überblick](#pipeline-überblick)
- [Phase 1 — LLM-Bewertung](#phase-1--llm-bewertung)
- [Phase 2 — Erzeugung von Beziehungshypothesen](#phase-2--erzeugung-von-beziehungshypothesen)
- [Phase 3 — Aufbau der Architekturansicht](#phase-3--aufbau-der-architekturansicht)
  - [Schritt 1: Ankerauswahl](#schritt-1-ankerauswahl)
  - [Schritt 2: Relevanzpropagierung](#schritt-2-relevanzpropagierung)
  - [Schritt 3: Elementaufbau](#schritt-3-elementaufbau)
  - [Schritt 4: Blattknoten-Anreicherung](#schritt-4-blattknoten-anreicherung)
  - [Schritt 5: Beziehungsaufbau](#schritt-5-beziehungsaufbau)
  - [Schritt 6: Injektion provisorischer Beziehungen](#schritt-6-injektion-provisorischer-beziehungen)
  - [Schritt 7: Knotenlimit-Trunkierung](#schritt-7-knotenlimit-trunkierung)
  - [Schritt 8: Erzeugung von Wirkungsbeziehungen](#schritt-8-erzeugung-von-wirkungsbeziehungen)
  - [Schritt 9: Aufbau des Scoring-Trace](#schritt-9-aufbau-des-scoring-trace)
  - [Schritt 10: Wirkungsauswahl](#schritt-10-wirkungsauswahl)
  - [Schritt 11: Anwesenheitsgründe und Elternknoten-Codes](#schritt-11-anwesenheitsgründe-und-elternknoten-codes)
- [Phase 4 — Diagrammprojektion und Export](#phase-4--diagrammprojektion-und-export)
- [Beziehungslebenszyklus](#beziehungslebenszyklus)
- [Persistenzmodell](#persistenzmodell)
- [Trace vs. Wirkung vs. Export](#trace-vs-wirkung-vs-export)
- [Seed-Beziehungen](#seed-beziehungen)
- [Knoten-Herkünfte](#knoten-herkünfte)
- [Beziehungs-Herkünfte](#beziehungs-herkünfte)
- [Konstantenreferenz](#konstantenreferenz)
- [Bekannte Einschränkungen und unvollständige Bereiche](#bekannte-einschränkungen-und-unvollständige-bereiche)

---

## Terminologie

| Begriff | Definition |
|---------|-----------|
| **Bewertung (Score)** | Ganzzahl 0–100, die das LLM einem Taxonomie-Knoten zuweist und die Relevanz für eine Geschäftsanforderung angibt. 0 = „bewertet, aber nicht relevant"; Abwesenheit = „noch nicht bewertet". |
| **Anker** | Ein Knoten, dessen LLM-Bewertung die Ankerschwelle erreicht (≥ 70, oder ≥ 50 als Rückfall). Anker sind die Ausgangspunkte für die Relevanzpropagierung. |
| **Relevanz** | Gleitkommawert 0,0–1,0, abgeleitet aus der Ankerbewertung geteilt durch 100 und durch BFS-Sprünge gedämpft. |
| **Propagierung** | BFS-Durchlauf von Taxonomie-Beziehungen ausgehend von Ankerknoten. Jeder Sprung multipliziert die Relevanz mit einem typspezifischen Gewicht und einem Dämpfungsfaktor. |
| **Wirkungsbeziehung** | Eine kategorieübergreifende Blatt-zu-Blatt-Beziehung, die aus einer Root-Level-Trace-Beziehung abgeleitet wird. Repräsentiert eine konkrete architektonische Abhängigkeit. |
| **Trace-Beziehung** | Eine während der BFS-Propagierung entdeckte Beziehung. Zeigt den Pfad, über den die Relevanz geflossen ist. |
| **Seed-Beziehung** | Eine aus den Taxonomie-CSV-Seeds geladene Beziehung. Beide Endpunkte sind Root-Codes (zwei Buchstaben, kein Bindestrich). |
| **Hypothese** | Eine provisorische Beziehung, die während der Analyse erzeugt wird. Wird in der Datenbank mit Status PROVISIONAL gespeichert. Benutzer können akzeptieren (→ bestätigte TaxonomyRelation) oder ablehnen. |
| **Container-Knoten** | Ein rein visueller Gruppierungsknoten im Diagramm. Kein echtes Architekturelement. Mermaid rendert Container als verschachtelte Subgraphen; ArchiMate- und Structurizr-Exporter überspringen sie vollständig. |
| **Diagramm-Auswahlrichtlinie** | Eine konfigurierbare Kuratierungspipeline, die Knoten und Kanten vor dem Export filtert, unterdrückt und begrenzt. |

---

## Pipeline-Überblick

Wenn ein Benutzer eine Geschäftsanforderung über `POST /api/analyze` einreicht, werden die folgenden Phasen der Reihe nach ausgeführt:

```
Phase 1: LLM-Bewertung
  └─ LlmService.analyzeWithBudget()
       └─ Erzeugt: Map<nodeCode, score 0–100>

Phase 2: Beziehungshypothesen-Erzeugung
  └─ AnalysisRelationGenerator.generate()
       └─ Erzeugt: List<RelationHypothesisDto>
  └─ HypothesisService.persistFromAnalysis()
       └─ Persistiert in: relation_hypothesis Tabelle + Git „draft" Branch

Phase 3: Aufbau der Architekturansicht (falls angefordert)
  └─ RequirementArchitectureViewService.build()
       └─ 11 interne Schritte (siehe unten)
       └─ Erzeugt: RequirementArchitectureView

Phase 4: Diagrammprojektion und Export (falls angefordert)
  └─ DiagramProjectionService.project()
  └─ DiagramSelectionPolicy.apply()
  └─ Formatspezifischer Exporter (Mermaid / ArchiMate / Structurizr / Visio)
```

---

## Phase 1 — LLM-Bewertung

**Einstiegspunkt:** `LlmService.analyzeWithBudget(String businessText)`

Das LLM bewertet Taxonomie-Knoten nach einem Top-Down-Budget-Propagierungsmuster:

1. **Root-Level-Bewertung:** Jede der 8 Taxonomie-Wurzeln wird unabhängig
   durch das LLM auf einer Skala von 0–100 bewertet. Die Reihenfolge ist:
   `BP, CP, CR, CO, CI, UA, BR, IP`.

2. **Budget-Propagierung:** Wenn die Bewertung einer Wurzel > 0 ist,
   wird diese Bewertung zum _Budget_ für ihre Level-1-Kinder. Das LLM
   wird erneut mit den Kindern aufgerufen, deren Summe das Budget des
   Elternknotens nicht überschreiten sollte.

3. **Rekursiver Abstieg:** Wenn ein Kind eine Bewertung > 0 hat, wird
   der Prozess rekursiv auf dessen Kinder angewandt.

4. **Überspringen:** Knoten mit Bewertung 0 werden nicht weiter expandiert.

5. **Ratenlimit-Behandlung:** Bei einer `LlmRateLimitException` werden
   verbleibende Wurzeln übersprungen. Der Ergebnisstatus wird `PARTIAL`.

**Ausgabe:** `AnalysisResult` mit `Map<String, Integer>` nodeCode → Bewertung.

**Was das LLM _nicht_ tut:** Das LLM wählt keine Anker aus, propagiert
keine Relevanz, erzeugt keine Wirkungsbeziehungen und entscheidet nicht,
welche Knoten in der Architekturansicht erscheinen. Das sind alles
deterministische Schritte _nach_ Abschluss der Bewertung.

---

## Phase 2 — Erzeugung von Beziehungshypothesen

**Einstiegspunkt:** `AnalysisRelationGenerator.generate(Map<String, Integer> scores)`

Nach der LLM-Bewertung erzeugt das System deterministisch provisorische
Beziehungshypothesen:

1. Alle Knoten mit Bewertung ≥ 50 auswählen.
2. Qualifizierende Knoten nach Taxonomie-Root gruppieren.
3. Überspringen, falls weniger als 2 Roots qualifizierende Knoten haben.
4. Für jeden Quell-Root und jeden `RelationType`: erlaubte Ziel-Roots
   aus der `RelationCompatibilityMatrix` nachschlagen.
5. Den bestbewerteten Knoten aus jedem Root auswählen.
6. Konfidenz berechnen: `(scoreA × scoreB) / 10000` (Bereich 0,0–1,0).
7. Deduplizierung nach (Quelle, Ziel, Typ)-Tripel; höchste Konfidenz behalten.
8. Nach Konfidenz absteigend sortieren.

**Persistenz:** Hypothesen werden sofort persistiert:
- **Datenbank:** `relation_hypothesis` Tabelle mit Status `PROVISIONAL`
- **Git:** DSL-Darstellung auf den `draft` Branch committet

Das LLM ist _nicht_ an der Hypothesenerzeugung beteiligt. Dies ist
vollständig regelbasiert über die Kompatibilitätsmatrix.

---

## Phase 3 — Aufbau der Architekturansicht

**Einstiegspunkt:** `RequirementArchitectureViewService.build(scores, businessText, maxNodes, provisionalRelations)`

Diese Phase ist vollständig deterministisch. Es werden keine LLM-Aufrufe gemacht.

### Schritt 1: Ankerauswahl

- **Primärschwelle:** Bewertung ≥ 70 → Anker
- **Rückfall:** Bei weniger als 3 primären Ankern: Top-3-Knoten mit Bewertung ≥ 50
- Relevanz jedes Ankers = `directScore / 100,0`

### Schritt 2: Relevanzpropagierung

BFS ausgehend von Ankerknoten durch bestätigte Taxonomie-Beziehungen:

| Konstante | Wert | Bedeutung |
|-----------|------|-----------|
| `MAX_HOPS` | 2 | Maximale Sprünge von jedem Anker |
| `MIN_RELEVANCE` | 0,35 | Propagierte Werte darunter verwerfen |
| `HOP_DECAY` | 0,70 | Multiplikator ab Sprung 2+ |

**Gewichte je Beziehungstyp:**

| Beziehungstyp | Gewicht |
|---------------|---------|
| `REALIZES` | 0,80 |
| `SUPPORTS` | 0,75 |
| `FULFILLS` | 0,70 |
| `USES` | 0,65 |
| `DEPENDS_ON` | 0,60 |

**Propagierungsformel:**
```
propagierte_relevanz = quell_relevanz × typ_gewicht × (HOP_DECAY falls sprung > 1)
```

### Schritt 3: Elementaufbau

Propagierungsergebnisse werden in `RequirementElementView`-Einträge umgewandelt:

- Ankerknoten: Herkunft = `DIRECT_SCORED`, Sprung = 0
- Root-Codes (kein Bindestrich) via Propagierung: Herkunft = `SEED_CONTEXT`
- Andere propagierte Knoten: Herkunft = `PROPAGATED`

### Schritt 4: Blattknoten-Anreicherung

Für jeden bereits vertretenen Root werden bis zu 3 Blattknoten
(Score ≥ 5) mit Herkunft `ENRICHED_LEAF` hinzugefügt.

### Schritt 5: Beziehungsaufbau

- Beide Endpunkte Root-Codes: **Seed-Beziehung** (`CATEGORY_SEED`, Herkunft `TAXONOMY_SEED`)
- Sonst: **Trace-Beziehung** (`CATEGORY_TRACE`, Herkunft `PROPAGATED_TRACE`)

### Schritt 6: Injektion provisorischer Beziehungen

Falls keine bestätigten Beziehungen existieren, aber provisorische
Hypothesen vorliegen, werden diese als virtuelle Kanten injiziert.

### Schritt 7: Knotenlimit-Trunkierung

Bei Überschreitung von `maxArchitectureNodes` werden nur die
Top-N-Elemente (nach Relevanz) behalten.

### Schritt 8: Erzeugung von Wirkungsbeziehungen

Kartesisches Produkt von Blattknoten über kategorieübergreifende
Trace-Beziehungen: `CATEGORY_IMPACT`, Herkunft `IMPACT_DERIVED`.

### Schritt 9: Aufbau des Scoring-Trace

Rekonstruktion des hierarchischen Bewertungspfads
(Root → Zwischenknoten → Blatt) für jeden Anker.

### Schritt 10: Wirkungsauswahl

Kompositbewertungsformel:

| Komponente | Gewicht | Berechnung |
|------------|---------|------------|
| LLM-Bewertung | 0,30 | `allScores.getOrDefault(code, 0) / 100,0` |
| Spezifität | 0,25 | `min(taxonomyDepth / 5,0, 1,0)` |
| Kategorieübergreifend | 0,20 | 1,0 falls in kategorieübergreifenden Beziehungen, sonst 0,0 |
| Blatt-Konkretheit | 0,15 | 1,0 falls Code Bindestrich enthält, sonst 0,2 |
| Lesbarkeit | 0,10 | `min(titelLänge / 40,0, 1,0)` |

Maximum 5 Knoten pro Kategorie. Unterdrückt werden:
- Taxonomie-Gerüst (Tiefe ≤ 1 bei tieferen Knoten)
- Generische schwache Knoten
- Redundante Zwischenknoten

### Schritt 11: Anwesenheitsgründe und Elternknoten-Codes

Für jedes Element und jede Beziehung wird ein lesbarer
Anwesenheitsgrund erzeugt. Elternknoten-Codes werden für die
UI-Containment-Darstellung zugewiesen.

---

## Phase 4 — Diagrammprojektion und Export

Die Architekturansicht durchläuft die `DiagramProjectionService` und
eine `DiagramSelectionPolicy` mit 11 Kuratierungsschritten bevor sie
exportiert wird.

### Konfigurationsvoreinstellungen

| Voreinstellung | Root-Unterdr. | Gerüst-Unterdr. | Kollabieren | Nur-Blatt | Cluster | minRelevanz | maxKnoten | maxKanten |
|---|---|---|---|---|---|---|---|---|
| `defaultImpact` | ✓ | ✓ | ✓ | ✗ | ✓ | 0,35 | 25 | 40 |
| `leafOnly` | ✓ | ✓ | ✓ | ✓ | ✗ | 0,0 | 25 | 12 |
| `clustering` | ✓ | ✓ | ✓ | ✗ | ✓ | 0,35 | 30 | 40 |
| `trace` | ✗ | ✗ | ✗ | ✗ | ✗ | 0,0 | 50 | 60 |

### Container-Knoten-Behandlung je Exporter

| Exporter | Verhalten |
|----------|-----------|
| **Mermaid** | Container als verschachtelter `subgraph` mit Kindern darin |
| **ArchiMate** | Container **ausgeschlossen** — kein Element, keine Beziehungen |
| **Structurizr** | Container **ausgeschlossen** — nicht im Workspace-Modell |
| **Visio** | Container als Gruppenform |

---

## Beziehungslebenszyklus

```
                    ┌──────────────────────┐
                    │  Analyse abschlossen │
                    └──────────┬───────────┘
                               │
                               ▼
                ┌──────────────────────────┐
                │  PROVISIONAL  (autom.)   │
                │  DB: relation_hypothesis │
                │  Git: „draft" Branch     │
                └────┬──────────┬──────────┘
                     │          │
              ┌──────▼───┐  ┌──▼──────────┐
              │ ACCEPTED  │  │  REJECTED   │
              │ DB: Hyp.  │  │  DB: Hyp.   │
              │ + erzeugt │  │  (kein Git) │
              │ taxonomy  │  └─────────────┘
              │ _relation │
              │ Git:      │
              │ „accepted"│
              └───────────┘
```

**Statusübergänge:**

| Von | Nach | Auslöser | Seiteneffekte |
|-----|------|----------|---------------|
| `PROVISIONAL` | `ACCEPTED` | Benutzer klickt Akzeptieren | Erzeugt `TaxonomyRelation` in DB; committet DSL auf `accepted` Branch |
| `PROVISIONAL` | `REJECTED` | Benutzer klickt Ablehnen | Aktualisiert Status in DB; kein Git-Commit |

**Sitzungsbasierte Anwendung:** Das `appliedInCurrentAnalysis`-Flag
ermöglicht die Anwendung für die aktuelle Sitzung ohne permanente
`TaxonomyRelation`.

**Hinweis:** Der Status `PROPOSED` existiert im Enum, wird aber nicht
von der Analyse-Pipeline gesetzt (er wird vom separaten
Beziehungsvorschlag-Feature verwendet).

---

## Persistenzmodell

| Daten | Speicher | Tabelle/Ort | Lebensdauer |
|-------|---------|-------------|-------------|
| Taxonomie-Knoten (~2.500) | HSQLDB | `taxonomy_node` | Aus Excel beim Start geladen |
| Bestätigte Beziehungen | HSQLDB | `taxonomy_relation` | Permanent |
| Beziehungshypothesen | HSQLDB | `relation_hypothesis` | Permanent bis gelöscht |
| Hypothesen-Nachweise | HSQLDB | `relation_evidence` | Permanent; mit Hypothese verknüpft |
| DSL-Snapshots | HSQLDB (JGit) | `git_packs`, `git_reflog` | Permanent; versioniert |
| Analyse-Bewertungen | **Nicht serverseitig** | In-Memory `SavedAnalysis` DTO | Client lädt als JSON herunter |
| Architekturansichten | **Nicht persistiert** | In-Memory | Pro Anfrage |
| Diagrammmodelle | **Nicht persistiert** | In-Memory | Pro Anfrage |

---

## Trace vs. Wirkung vs. Export

| Konzept | Was es ist | Kategorie | Herkunft | UI-Darstellung |
|---------|-----------|-----------|----------|----------------|
| **Trace** | Pfad, der erklärt, wie eine Bewertung einen Knoten erreicht hat | `trace` | `PROPAGATED_TRACE` | Eingeklappter „🔍 Scoring Trace"-Abschnitt |
| **Wirkung** | Kuratierte Menge architektonisch relevanter Blatt-zu-Blatt-Beziehungen | `impact` | `IMPACT_DERIVED` | Offener „🎯 Architecture Impact"-Abschnitt |
| **Export** | Diagrammmodell nach Richtlinien-Filterung | — | — | Mermaid / ArchiMate / Structurizr / Visio |

---

## Seed-Beziehungen

Seed-Beziehungen werden aus CSV-Dateien geladen und repräsentieren
bekannte Root-zu-Root-Taxonomie-Beziehungen.

- Klassifiziert als `CATEGORY_SEED` mit Herkunft `TAXONOMY_SEED`
- UI zeigt sie im eingeklappten „Seed Context Relationships"-Abschnitt
- Prioritätsstufe 5 (niedrigste) in der Beziehungsrangfolge
- Seeds bilden den Beziehungsgraphen für die BFS-Propagierung

---

## Knoten-Herkünfte

| Herkunft | Bedeutung |
|----------|-----------|
| `DIRECT_SCORED` | LLM-Bewertung ≥ Ankerschwelle |
| `TRACE_INTERMEDIATE` | Auf dem hierarchischen Pfad zwischen Root und Anker |
| `PROPAGATED` | Via BFS-Beziehungstraversal erreicht |
| `SEED_CONTEXT` | Root-Code via Propagierung erreicht |
| `ENRICHED_LEAF` | Als konkreter Blattknoten nachträglich hinzugefügt |
| `IMPACT_SELECTED` | Für finale Wirkungsdarstellung durch Kompositbewertung ausgewählt |

---

## Beziehungs-Herkünfte

| Herkunft | Bedeutung |
|----------|-----------|
| `TAXONOMY_SEED` | Aus Seed-CSV geladen; Root-zu-Root |
| `PROPAGATED_TRACE` | Durch BFS-Traversal entdeckt |
| `IMPACT_DERIVED` | Kategorieübergreifende Blatt-zu-Blatt-Ableitung |
| `SUGGESTED_CANDIDATE` | Durch Lückenanalyse oder Embedding-Ähnlichkeit vorgeschlagen |
| `LLM_SUPPORTED` | ⚠️ Durch LLM-Inferenz bestätigt (derzeit nicht gesetzt) |

---

## Konstantenreferenz

| Konstante | Wert | Ort | Zweck |
|-----------|------|-----|-------|
| `ANCHOR_THRESHOLD_HIGH` | 70 | RequirementArchitectureViewService | Primäre Ankerschwelle |
| `ANCHOR_THRESHOLD_LOW` | 50 | RequirementArchitectureViewService | Rückfall-Ankerschwelle |
| `MIN_ANCHORS` | 3 | RequirementArchitectureViewService | Minimum vor Rückfall |
| `MAX_LEAF_ENRICHMENT` | 3 | RequirementArchitectureViewService | Max Anreicherungs-Blätter pro Root |
| `LEAF_ENRICHMENT_MIN_SCORE` | 5 | RequirementArchitectureViewService | Min-Score für Blattanreicherung |
| `MAX_HOPS` | 2 | RelevancePropagationService | BFS-Sprunglimit |
| `MIN_RELEVANCE` | 0,35 | RelevancePropagationService | Propagierungsschwelle |
| `HOP_DECAY` | 0,70 | RelevancePropagationService | Sprung-Dämpfung |
| `MAX_IMPACT_PER_CATEGORY` | 5 | ArchitectureImpactSelector | Wirkungsauswahl-Limit pro Kategorie |
| `W_LLM_SCORE` | 0,30 | ArchitectureImpactSelector | LLM-Gewicht im Komposit |
| `W_SPECIFICITY` | 0,25 | ArchitectureImpactSelector | Tiefenbasiertes Spezifitätsgewicht |
| `W_CROSS_CATEGORY` | 0,20 | ArchitectureImpactSelector | Gewicht für kategorieübergreifende Teilnahme |
| `W_LEAF_CONCRETENESS` | 0,15 | ArchitectureImpactSelector | Blatt- vs. Root-Gewicht |
| `W_READABILITY` | 0,10 | ArchitectureImpactSelector | Titel-Lesbarkeitgewicht |
| `MIN_SCORE` (Beziehungen) | 50 | AnalysisRelationGenerator | Min-Score für Hypothesenerzeugung |

---

## Bekannte Einschränkungen und unvollständige Bereiche

1. **`PROPOSED`-Status von Analyse-Pipeline ungenutzt.** Wird nur vom
   separaten Beziehungsvorschlag-Feature verwendet.

2. **`LLM_SUPPORTED`-Herkunft ungenutzt.** Reserviert für zukünftige
   LLM-Evaluierung von Hypothesen.

3. **SavedAnalysis nicht serverseitig persistiert.** Analyseergebnisse
   müssen clientseitig gespeichert werden.

4. **Kompatibilitätsmatrix unvollständig.** Nicht alle Root-Kombinationen
   sind abgedeckt.

5. **Einzelanbieter-Bewertung.** Keine Ensemble-Bewertung oder
   Kreuzvalidierung zwischen Anbietern.

6. **Budget-Propagierung nicht strikt erzwungen.** Das LLM kann
   Bewertungen liefern, die das Budget über- oder unterschreiten.
