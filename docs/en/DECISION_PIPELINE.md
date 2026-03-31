# Decision Pipeline — Technical Reference

> **Audience:** developers and system integrators who need to understand
> exactly how a business requirement is transformed into scored nodes,
> architecture views, and exportable diagrams.
>
> This document describes **implemented behaviour** as of 2026-03-31.
> Sections marked with ⚠️ describe incomplete or planned features.

---

## Table of Contents

- [Terminology](#terminology)
- [Pipeline Overview](#pipeline-overview)
- [Phase 1 — LLM Scoring](#phase-1--llm-scoring)
- [Phase 2 — Relation Hypothesis Generation](#phase-2--relation-hypothesis-generation)
- [Phase 3 — Architecture View Construction](#phase-3--architecture-view-construction)
  - [Step 1: Anchor Selection](#step-1-anchor-selection)
  - [Step 2: Relevance Propagation](#step-2-relevance-propagation)
  - [Step 3: Element Building](#step-3-element-building)
  - [Step 4: Leaf Enrichment](#step-4-leaf-enrichment)
  - [Step 5: Relationship Building](#step-5-relationship-building)
  - [Step 6: Provisional Relation Injection](#step-6-provisional-relation-injection)
  - [Step 7: Node Limit Truncation](#step-7-node-limit-truncation)
  - [Step 8: Impact Relation Generation](#step-8-impact-relation-generation)
  - [Step 9: Scoring Trace Construction](#step-9-scoring-trace-construction)
  - [Step 10: Impact Selection](#step-10-impact-selection)
  - [Step 11: Presence Reasons and Parent Codes](#step-11-presence-reasons-and-parent-codes)
- [Phase 4 — Diagram Projection and Export](#phase-4--diagram-projection-and-export)
- [Relation Lifecycle](#relation-lifecycle)
- [Persistence Model](#persistence-model)
- [Trace vs Impact vs Export](#trace-vs-impact-vs-export)
- [Seed Relations](#seed-relations)
- [Node Origins](#node-origins)
- [Relation Origins](#relation-origins)
- [Constants Reference](#constants-reference)
- [Known Limitations and Incomplete Areas](#known-limitations-and-incomplete-areas)

---

## Terminology

| Term | Definition |
|------|-----------|
| **Score** | Integer 0–100 assigned by the LLM to a taxonomy node, indicating relevance to a business requirement. A score of 0 means "evaluated but not relevant"; absence means "not yet evaluated". |
| **Anchor** | A node whose LLM score meets the anchor threshold (≥ 70, or ≥ 50 as fallback). Anchors are the starting points for relevance propagation. |
| **Relevance** | Floating-point 0.0–1.0 value derived from an anchor's score divided by 100, then decayed through BFS hops. |
| **Propagation** | BFS traversal of taxonomy relations starting from anchor nodes. Each hop multiplies relevance by a type-specific weight and a decay factor. |
| **Impact relation** | A cross-category leaf-to-leaf relation derived from a root-level trace relation. Represents a concrete architectural dependency. |
| **Trace relation** | A relation discovered during BFS propagation. Represents the path relevance flowed through. |
| **Seed relation** | A relation loaded from the taxonomy CSV seeds. Both endpoints are root codes (two-letter, no hyphen). |
| **Hypothesis** | A provisional relation generated during analysis. Stored in the database with status PROVISIONAL. Users can accept (→ confirmed TaxonomyRelation) or reject. |
| **Container node** | A diagram-only visual grouping node. Not a real architecture element. Mermaid renders containers as nested subgraphs; ArchiMate and Structurizr exporters skip them entirely. |
| **Diagram selection policy** | A configurable curation pipeline that filters, suppresses, and limits nodes and edges before export. |

---

## Pipeline Overview

When a user submits a business requirement via `POST /api/analyze`, the following phases execute in order:

```
Phase 1: LLM Scoring
  └─ LlmService.analyzeWithBudget()
       └─ Produces: Map<nodeCode, score 0–100>

Phase 2: Relation Hypothesis Generation
  └─ AnalysisRelationGenerator.generate()
       └─ Produces: List<RelationHypothesisDto>
  └─ HypothesisService.persistFromAnalysis()
       └─ Persists to: relation_hypothesis table + Git "draft" branch

Phase 3: Architecture View Construction (if requested)
  └─ RequirementArchitectureViewService.build()
       └─ 11 internal steps (see below)
       └─ Produces: RequirementArchitectureView

Phase 4: Diagram Projection and Export (if requested)
  └─ DiagramProjectionService.project()
  └─ DiagramSelectionPolicy.apply()
  └─ Format-specific exporter (Mermaid / ArchiMate / Structurizr / Visio)
```

---

## Phase 1 — LLM Scoring

**Entry point:** `LlmService.analyzeWithBudget(String businessText)`

The LLM scores taxonomy nodes in a top-down budget-propagation pattern:

1. **Root-level scoring:** Each of the 8 taxonomy roots is scored
   independently by the LLM on a 0–100 scale. Roots are processed in
   priority order: `BP, CP, CR, CO, CI, UA, BR, IP`.

2. **Budget propagation:** If a root's score is > 0, that score becomes
   the _budget_ for its Level-1 children. The LLM is called again with
   the children, and their scores must sum to (at most) the parent's score.

3. **Recursive descent:** If a child has score > 0, the process recurses
   to its children, with the child's score as the new budget.

4. **Skipping:** Nodes with score 0 are not expanded further. The LLM
   is never called for their children.

5. **Rate-limit handling:** If an `LlmRateLimitException` is caught,
   remaining roots are skipped. The result status becomes `PARTIAL`
   instead of `SUCCESS`. Warnings list which roots were skipped.

**Output:** `AnalysisResult` containing `Map<String, Integer>` of
nodeCode → score for all evaluated nodes.

**What the LLM does _not_ do:** The LLM does not select anchors,
propagate relevance, generate impact relations, or decide which nodes
appear in the architecture view. Those are all deterministic steps that
happen _after_ scoring is complete.

---

## Phase 2 — Relation Hypothesis Generation

**Entry point:** `AnalysisRelationGenerator.generate(Map<String, Integer> scores)`

After LLM scoring, the system deterministically generates provisional
relation hypotheses:

1. Select all nodes with score ≥ 50.
2. Group qualifying nodes by taxonomy root.
3. Skip if fewer than 2 roots have qualifying nodes.
4. For each source root, for each `RelationType`, look up allowed target
   roots in `RelationCompatibilityMatrix`.
5. Pick the best-scoring node from each root.
6. Compute confidence: `(scoreA × scoreB) / 10000` (range 0.0–1.0).
7. Deduplicate by (source, target, type) triple, keeping highest confidence.
8. Sort by confidence descending.

**Persistence:** Hypotheses are immediately persisted:
- **Database:** `relation_hypothesis` table with status `PROVISIONAL`
- **Git:** DSL representation committed to the `draft` branch

The LLM is _not_ involved in hypothesis generation. This is entirely
rule-based using the compatibility matrix.

---

## Phase 3 — Architecture View Construction

**Entry point:** `RequirementArchitectureViewService.build(scores, businessText, maxNodes, provisionalRelations)`

This phase is entirely deterministic. No LLM calls are made. The 11 steps
are:

### Step 1: Anchor Selection

Select nodes whose LLM score qualifies them as anchors:

- **Primary threshold:** score ≥ 70 → anchor with reason "high direct match"
- **Fallback:** if fewer than 3 primary anchors, select top-3 nodes
  with score ≥ 50
- Each anchor's relevance = `directScore / 100.0`

### Step 2: Relevance Propagation

`RelevancePropagationService.propagate(anchorRelevances)` runs BFS
from anchor nodes through confirmed taxonomy relations:

| Constant | Value | Meaning |
|----------|-------|---------|
| `MAX_HOPS` | 2 | Maximum hops from any anchor |
| `MIN_RELEVANCE` | 0.35 | Discard propagated values below this |
| `HOP_DECAY` | 0.70 | Multiplier applied on hop 2+ |

**Per-relation-type weights:**

| Relation Type | Weight |
|---------------|--------|
| `REALIZES` | 0.80 |
| `SUPPORTS` | 0.75 |
| `FULFILLS` | 0.70 |
| `USES` | 0.65 |
| `DEPENDS_ON` | 0.60 |

**Propagation formula:**
```
propagated_relevance = source_relevance × type_weight × (HOP_DECAY if hop > 1)
```

If multiple paths reach the same node, the highest relevance wins.
Nodes with `propagated_relevance < MIN_RELEVANCE` are discarded.

**Output:** `PropagationResult` containing relevance map, hop distances,
reasons, and traversed relations.

### Step 3: Element Building

Convert propagation results into `RequirementElementView` entries:

- Anchor nodes: origin = `DIRECT_SCORED`, hop = 0
- Root codes (no hyphen) reached via propagation: origin = `SEED_CONTEXT`
- Other propagated nodes: origin = `PROPAGATED`

Each element records: nodeCode, title, relevance, hopDistance, taxonomySheet,
taxonomyDepth, hierarchyPath, and includedBecause.

### Step 4: Leaf Enrichment

`enrichWithLeafNodes()` ensures each represented taxonomy root has concrete
named leaf nodes, not just abstract roots:

- For each root already in the element list, find leaf nodes (codes containing
  a hyphen) with score ≥ 5 that are not yet included.
- Add up to 3 leaf nodes per root, sorted by score descending.
- Leaf nodes with score ≥ 70 are marked as anchors.
- Origin: `ENRICHED_LEAF`.

### Step 5: Relationship Building

Convert traversed relations from the propagation result into
`RequirementRelationshipView` entries.

**Classification:**
- If both endpoints are root codes (no hyphen): **seed relation**
  (`CATEGORY_SEED`, origin `TAXONOMY_SEED`)
- Otherwise: **trace relation** (`CATEGORY_TRACE`, origin `PROPAGATED_TRACE`)

### Step 6: Provisional Relation Injection

If no confirmed relationships were found but provisional hypotheses exist
(from Phase 2), inject them as virtual edges so the architecture view is
usable from day one.

Both endpoints are ensured to be in the element list (added if missing).

### Step 7: Node Limit Truncation

If `maxArchitectureNodes > 0` and element count exceeds the limit, keep
only the top-N elements (by relevance) and remove relationships whose
endpoints were removed.

### Step 8: Impact Relation Generation

`generateImpactRelations()` derives cross-category leaf-to-leaf relations
from root-level trace relations:

1. Group leaf elements by taxonomy root.
2. For each trace relation between two roots: select all qualified
   leaf endpoints in each root.
3. Generate the Cartesian product of source-leaves × target-leaves.
4. Each impact relation gets: `CATEGORY_IMPACT`, origin `IMPACT_DERIVED`,
   relevance = `min(source.relevance, target.relevance)`.
5. Deduplicate by signature; keep highest confidence.

### Step 9: Scoring Trace Construction

`ScoringTraceSelector.buildTrace()` reconstructs the hierarchical
scoring path (root → intermediate → leaf) for each anchor:

- Walk `taxonomyService.getPathToRoot(anchorCode)` for each anchor.
- Build a scoring path string: `"CO(0%) > CO-1000(25%) > CO-1023(70%)"`.
- Anchor nodes: origin = `DIRECT_SCORED`.
- Intermediate path nodes: origin = `TRACE_INTERMEDIATE`.

### Step 10: Impact Selection

`ArchitectureImpactSelector.selectForImpact()` marks the most valuable
nodes for the final impact presentation using a composite score:

**Composite impact score formula:**

| Component | Weight | Calculation |
|-----------|--------|-------------|
| LLM score | 0.30 | `allScores.getOrDefault(code, 0) / 100.0` |
| Specificity | 0.25 | `min(taxonomyDepth / 5.0, 1.0)` |
| Cross-category | 0.20 | 1.0 if node participates in cross-category relations, else 0.0 |
| Leaf concreteness | 0.15 | 1.0 if code contains hyphen (leaf), else 0.2 |
| Readability | 0.10 | `min(titleLength / 40.0, 1.0)`, reduced for generic names |

**Selection per category:**
- Maximum 5 nodes per category (`MAX_IMPACT_PER_CATEGORY`).
- Suppressed before selection:
  - **Taxonomy scaffolding:** depth ≤ 1 when deeper nodes exist in category
  - **Generic weak nodes:** depth ≤ 1 with generic name and a descendant ≥ 50% of score
  - **Redundant intermediates:** depth > 1 with exactly 1 strong child (≥ 50% of score)
- Selected nodes receive origin `IMPACT_SELECTED`.

### Step 11: Presence Reasons and Parent Codes

- `populatePresenceReasons()`: Builds a human-readable string for each
  element and relation explaining why it is present.
- `populateParentNodeCodes()`: Walks hierarchy paths to link each element
  to its nearest ancestor also in the view. Used by the UI for
  containment/cluster rendering.

---

## Phase 4 — Diagram Projection and Export

When export is requested (Mermaid, ArchiMate, Visio, Structurizr), the
architecture view goes through two additional stages:

### Diagram Projection

`DiagramProjectionService.project()` converts `RequirementArchitectureView`
into a format-neutral `DiagramModel` (nodes + edges + title).

### Diagram Selection Policy

`ConfigurableDiagramSelectionPolicy.apply()` curates the diagram model
in 11 sequential steps:

1. **Relevance filter** — remove nodes below `minRelevance` (anchors and
   impact-selected nodes are exempt)
2. **Concrete category index** — identify categories with depth > 1 nodes
3. **Root suppression** — remove two-letter root codes with concrete descendants
4. **Scaffolding suppression** — remove first-level containers (XX-1000 pattern)
5. **Leaf-only mode** — if enabled, keep only deepest nodes per layer;
   re-route edges via `EdgeRerouteStrategy`
6. **Clustering** — mark intermediates with ≥ 2 children as `container=true`;
   collapse single-child parents
7. **Sort nodes** — anchors first, then by relevance descending
8. **Limit nodes** — truncate to `maxNodes`
9. **Filter edges** — remove edges to/from removed nodes
10. **Sort edges** — cross-category (impact) first, then by relevance
11. **Limit edges** — truncate to `maxEdges`

### Selection Config Presets

| Preset | suppressRoot | suppressScaffold | collapse | leafOnly | clusters | minRelevance | maxNodes | maxEdges |
|--------|---|---|---|---|---|---|---|---|
| `defaultImpact` | ✓ | ✓ | ✓ | ✗ | ✓ | 0.35 | 25 | 40 |
| `leafOnly` | ✓ | ✓ | ✓ | ✓ | ✗ | 0.0 | 25 | 12 |
| `clustering` | ✓ | ✓ | ✓ | ✗ | ✓ | 0.35 | 30 | 40 |
| `trace` | ✗ | ✗ | ✗ | ✗ | ✗ | 0.0 | 50 | 60 |

### Container Node Handling by Exporter

| Exporter | Behaviour |
|----------|-----------|
| **Mermaid** | Container rendered as nested `subgraph` with children inside |
| **ArchiMate** | Container **excluded** — not an element, not in relationships, not in view |
| **Structurizr** | Container **excluded** — not in workspace model, edges filtered |
| **Visio** | Container rendered as group shape |

---

## Relation Lifecycle

```
                    ┌──────────────────────┐
                    │  Analysis completes  │
                    └──────────┬───────────┘
                               │
                               ▼
                ┌──────────────────────────┐
                │  PROVISIONAL  (auto)     │
                │  DB: relation_hypothesis │
                │  Git: "draft" branch     │
                └────┬──────────┬──────────┘
                     │          │
              ┌──────▼───┐  ┌──▼──────────┐
              │ ACCEPTED  │  │  REJECTED   │
              │ DB: hyp   │  │  DB: hyp    │
              │ + creates │  │  (no Git)   │
              │ taxonomy  │  └─────────────┘
              │ _relation │
              │ Git:      │
              │ "accepted"│
              └───────────┘
```

**State transitions:**

| From | To | Trigger | Side effects |
|------|----|---------|--------------|
| `PROVISIONAL` | `ACCEPTED` | User clicks Accept | Creates `TaxonomyRelation` in DB; commits DSL to `accepted` branch |
| `PROVISIONAL` | `REJECTED` | User clicks Reject | Updates status in DB; no Git commit |

**Session-only application:** The `appliedInCurrentAnalysis` flag on a
hypothesis allows applying it for the current architecture view only,
without creating a permanent `TaxonomyRelation`. This is a transient
flag, not a status.

**Note:** The `PROPOSED` status exists in the `HypothesisStatus` enum
but is not set by the analysis pipeline. It is used by the separate
relation-proposal feature.

---

## Persistence Model

### What Is Stored Where

| Data | Storage | Table/Location | Lifetime |
|------|---------|----------------|----------|
| Taxonomy nodes (~2,500) | HSQLDB | `taxonomy_node` | Loaded from Excel at startup |
| Confirmed relations | HSQLDB | `taxonomy_relation` | Permanent; created from seeds or accepted hypotheses |
| Relation hypotheses | HSQLDB | `relation_hypothesis` | Permanent until deleted |
| Hypothesis evidence | HSQLDB | `relation_evidence` | Permanent; linked to hypothesis |
| DSL snapshots | HSQLDB (JGit) | `git_packs`, `git_reflog` | Permanent; versioned on `draft` / `accepted` branches |
| Analysis scores | **Not persisted server-side** | In-memory `SavedAnalysis` DTO | Client may download as JSON |
| Architecture views | **Not persisted** | In-memory `RequirementArchitectureView` | Per-request only |
| Diagram models | **Not persisted** | In-memory `DiagramModel` | Per-request only |

### SavedAnalysis Semantics

The `SavedAnalysis` DTO (version 2) captures scores and reasons for
client-side storage. It is **not** stored in the database.

- Node code present with value `0` → evaluated and scored 0% (explicitly not relevant)
- Node code absent → not yet evaluated

### JGit Repository Architecture

DSL content is stored in a JGit repository backed by Hibernate (not the
filesystem). Two repositories exist:

- **System repository** (`taxonomy-dsl`): shared across all users
- **Workspace repositories** (`ws-{workspaceId}`): per-workspace, seeded from system

Branches:
- `draft` — PROVISIONAL hypotheses committed here
- `accepted` — ACCEPTED hypotheses committed here
- Custom branches — created by users via the UI

Each commit stores a single file (`architecture.taxdsl`) containing the
DSL representation of hypotheses with their status and confidence.

---

## Trace vs Impact vs Export

These three concepts represent different stages of the pipeline:

### Trace (Scoring Trace)

**What it is:** The path through the taxonomy hierarchy that explains
_how_ a score reached a particular node.

**Where it lives:** `RequirementRelationshipView` with
`relationCategory = "trace"` and origin `PROPAGATED_TRACE`.

**UI rendering:** Collapsed "🔍 Scoring Trace" section showing trace
elements and trace/seed relations.

### Impact

**What it is:** The curated set of nodes and cross-category leaf-to-leaf
relations that represent the concrete architectural dependencies implied
by the requirement.

**Where it lives:** `RequirementRelationshipView` with
`relationCategory = "impact"` and origin `IMPACT_DERIVED`. Elements
marked with `selectedForImpact = true`.

**UI rendering:** Open "🎯 Architecture Impact" section showing impact
elements and impact relations.

### Export

**What it is:** The diagram model after the selection policy has filtered,
suppressed, clustered, and limited the architecture view.

**Where it lives:** `DiagramModel` with `DiagramNode` and `DiagramEdge`
records. Container nodes exist only here (not in the architecture view).

**Output formats:** Mermaid, ArchiMate XML, Structurizr DSL, Visio .vsdx.

**Key difference:** The export layer applies its own curation (root
suppression, scaffolding suppression, clustering, limits) which may
discard nodes and relations that exist in the architecture view. The
architecture view is the _source of truth_; the export is a filtered
projection.

---

## Seed Relations

Seed relations are pre-loaded from CSV files and represent known
root-to-root taxonomy relationships (e.g., CR → BP via SUPPORTS).

**In the architecture view:**
- Seed relations appear when both endpoint roots are in the element list.
- They are classified as `CATEGORY_SEED` with origin `TAXONOMY_SEED`.
- The UI renders them in a collapsed "Seed Context Relationships" section.
- They rank at priority tier 5 (lowest) in relationship ranking.

**Seed types:**
- `TYPE_DEFAULT` — standard seed from CSV
- `FRAMEWORK_SEED` — derived from framework mapping
- `SOURCE_DERIVED` — derived from source analysis

**How seeds affect results:** Seeds provide the relation graph that BFS
traverses during relevance propagation (Step 2). Without seeds or other
confirmed relations, propagation cannot expand beyond anchor nodes, and
the architecture view contains only direct matches.

---

## Node Origins

Each element in the architecture view carries an `origin` enum explaining
how it entered the view:

| Origin | Meaning | Set by |
|--------|---------|--------|
| `DIRECT_SCORED` | LLM score ≥ anchor threshold | Anchor selection + scoring trace |
| `TRACE_INTERMEDIATE` | On the hierarchical path between root and anchor | Scoring trace |
| `PROPAGATED` | Reached via BFS relation traversal | Element building |
| `SEED_CONTEXT` | Root code reached via propagation (not a leaf) | Element building |
| `ENRICHED_LEAF` | Added as a concrete leaf during post-propagation enrichment | Leaf enrichment |
| `IMPACT_SELECTED` | Selected for final impact presentation by composite scoring | Impact selection |

**Origin upgrade path:** During impact selection, nodes with origin
`null`, `PROPAGATED`, or `TRACE_INTERMEDIATE` may be upgraded to
`IMPACT_SELECTED`. Origins `SEED_CONTEXT` and `ENRICHED_LEAF` are
preserved (not overwritten).

---

## Relation Origins

| Origin | Meaning | Set by |
|--------|---------|--------|
| `TAXONOMY_SEED` | Loaded from seed CSV; root-to-root | Relationship building |
| `PROPAGATED_TRACE` | Discovered through BFS traversal | Relationship building |
| `IMPACT_DERIVED` | Cross-category leaf-to-leaf derivation | Impact relation generation |
| `SUGGESTED_CANDIDATE` | Proposed by gap analysis or embedding similarity | ⚠️ Relation proposal feature |
| `LLM_SUPPORTED` | Confirmed by LLM inference | ⚠️ Not currently set by analysis pipeline |

---

## Constants Reference

| Constant | Value | Location | Purpose |
|----------|-------|----------|---------|
| `ANCHOR_THRESHOLD_HIGH` | 70 | RequirementArchitectureViewService | Primary anchor selection threshold |
| `ANCHOR_THRESHOLD_LOW` | 50 | RequirementArchitectureViewService | Fallback anchor threshold |
| `MIN_ANCHORS` | 3 | RequirementArchitectureViewService | Minimum anchors before fallback |
| `MAX_LEAF_ENRICHMENT` | 3 | RequirementArchitectureViewService | Max enrichment leaves per root |
| `LEAF_ENRICHMENT_MIN_SCORE` | 5 | RequirementArchitectureViewService | Min score for leaf enrichment |
| `MAX_HOPS` | 2 | RelevancePropagationService | BFS hop limit |
| `MIN_RELEVANCE` | 0.35 | RelevancePropagationService | Propagation floor |
| `HOP_DECAY` | 0.70 | RelevancePropagationService | Per-hop decay multiplier |
| `MAX_IMPACT_PER_CATEGORY` | 5 | ArchitectureImpactSelector | Impact selection limit per category |
| `W_LLM_SCORE` | 0.30 | ArchitectureImpactSelector | LLM score weight in composite |
| `W_SPECIFICITY` | 0.25 | ArchitectureImpactSelector | Depth-based specificity weight |
| `W_CROSS_CATEGORY` | 0.20 | ArchitectureImpactSelector | Cross-category participation weight |
| `W_LEAF_CONCRETENESS` | 0.15 | ArchitectureImpactSelector | Leaf vs root weight |
| `W_READABILITY` | 0.10 | ArchitectureImpactSelector | Title readability weight |
| `MIN_SCORE` (relations) | 50 | AnalysisRelationGenerator | Min score for hypothesis generation |
| `ANALYSIS_PRIORITY` | BP,CP,CR,CO,CI,UA,BR,IP | LlmService | Root scoring order |

---

## Known Limitations and Incomplete Areas

1. **`PROPOSED` status unused by analysis pipeline.** The
   `HypothesisStatus.PROPOSED` enum value exists but is only used by
   the separate relation-proposal feature, not by the analysis pipeline.

2. **`LLM_SUPPORTED` origin unused.** The `RelationOrigin.LLM_SUPPORTED`
   enum value exists but no code path currently sets it. It is reserved
   for a future feature where the LLM evaluates and confirms hypotheses.

3. **`SUGGESTED_CANDIDATE` origin.** Set only by the relation-proposal
   service, not by the analysis pipeline.

4. **SavedAnalysis not server-persisted.** Analysis scores exist only
   in the response JSON and must be saved client-side. There is no
   server-side history of past analyses.

5. **Relation compatibility matrix coverage.** The matrix defines 26+
   rules but does not cover all possible root combinations. Missing
   pairs produce no hypotheses. The `RELATED_TO` type has no rules
   configured (all pairs allowed, but generator skips empty rule sets).

6. **Single-provider scoring.** Each analysis uses exactly one LLM
   provider. There is no ensemble scoring or cross-validation between
   providers.

7. **Budget propagation constraint.** Children's scores should sum to
   the parent's budget, but this is not strictly enforced — the LLM
   may return scores that exceed or undercount the budget. The system
   uses the raw LLM output.
