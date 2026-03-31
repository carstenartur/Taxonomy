# Decision Pipeline — Documentation Gap Analysis

This document identifies gaps in the existing documentation for the analysis
and architecture-view decision pipeline, measured against the actual behaviour
implemented in the codebase as of 2026-03-31.

---

## 1. What Is Already Documented

| Topic | Where | Quality |
|-------|-------|---------|
| High-level pipeline overview (7-step flowchart) | `docs/en/ARCHITECTURE.md` § Architecture View Generation Pipeline | Correct but very shallow — omits constants, suppression logic, and the impact/trace/seed distinction |
| Concept definitions (score, anchor, view, hypothesis) | `docs/en/CONCEPTS.md` | Brief glossary entries; no algorithm detail |
| Relation hypothesis lifecycle concept | `docs/en/CONCEPTS.md` § Relation Hypothesis | Mentions PENDING/ACCEPTED/REJECTED but uses wrong status name (PENDING vs actual PROVISIONAL) |
| Export format list | `docs/en/ARCHITECTURE.md` § Export Formats | Lists formats but does not describe selection policies or container-node semantics |
| Relation seed overview | `docs/en/RELATION_SEEDS.md` | Describes CSV format; does not explain how seeds flow into analysis results |

---

## 2. Identified Gaps

### 2.1 LLM Scoring Phase

**Gap:** No documentation describes _when_ the LLM is called, how budget
propagation works (parent score = budget for children), what the priority
order of roots is, or how partial results are handled when rate-limited.

**Impact:** Developers cannot reason about cost, latency, or partial-failure
behaviour without reading `LlmService.java`.

### 2.2 Anchor Selection

**Gap:** The thresholds (70 primary, 50 fallback, min 3) are mentioned in
`ARCHITECTURE.md` but the fallback mechanism (top-3-by-score when < 3
high anchors) is undocumented.

### 2.3 Relevance Propagation Constants

**Gap:** `CONCEPTS.md` mentions propagation exists but does not document:
- Algorithm: BFS with hop frontier
- `MAX_HOPS = 2`
- `MIN_RELEVANCE = 0.35`
- `HOP_DECAY = 0.70`
- Per-type weights (REALIZES 0.80, SUPPORTS 0.75, FULFILLS 0.70, USES 0.65, DEPENDS_ON 0.60)

### 2.4 Impact Selection vs Trace

**Gap:** No existing doc explains the three relation categories
(`impact`, `trace`, `seed`) or the composite impact score formula
(LLM 30%, specificity 25%, cross-category 20%, leaf 15%, readability 10%).

The distinction between "selected for impact" and "scoring trace" is
undocumented.

### 2.5 Relation Lifecycle

**Gap:** The hypothesis lifecycle is oversimplified in `CONCEPTS.md`.
The actual states are `PROVISIONAL → ACCEPTED | REJECTED`, plus
a transient `appliedInCurrentAnalysis` flag. The status `PROPOSED` exists
in the enum but is not set by the current analysis pipeline
(it is reserved for relation proposals, a separate feature).

No document describes what happens on accept (TaxonomyRelation created,
DSL committed to `accepted` branch) or reject (DB update only, no Git commit).

### 2.6 Seed Relations

**Gap:** `RELATION_SEEDS.md` describes the CSV format but not how seeds
propagate into the architecture view. The classification of root-to-root
relations as `TAXONOMY_SEED` / `CATEGORY_SEED` and their rendering as
collapsed "Seed Context" in the UI is undocumented.

### 2.7 Persistence Model

**Gap:** No document explains what is stored where:
- `taxonomy_node`, `taxonomy_relation` tables (HSQLDB) — catalog data
- `relation_hypothesis`, `relation_evidence` tables — provisional relations
- `git_packs`, `git_reflog` tables — JGit repository objects
- `SavedAnalysis` — JSON DTO, NOT persisted server-side (client downloads)

### 2.8 Export / Diagram Policy

**Gap:** The four diagram selection configs (`defaultImpact`, `leafOnly`,
`clustering`, `trace`) and the 11-step curation pipeline in
`ConfigurableDiagramSelectionPolicy.apply()` are undocumented.

Container-node semantics (Mermaid renders as subgraphs; ArchiMate and
Structurizr skip entirely) are undocumented.

### 2.9 Leaf Enrichment

**Gap:** The post-propagation leaf enrichment step (top 3 leaf nodes per
root with score ≥ 5, marked as `ENRICHED_LEAF`) is not documented anywhere.

### 2.10 Terminology Inconsistencies

| Term in docs | Actual code | Fix needed |
|---|---|---|
| "PENDING" hypothesis status | `HypothesisStatus.PROVISIONAL` | Yes — use PROVISIONAL |
| "relation lifecycle: PENDING → ACCEPTED/REJECTED/APPLIED" | Actual: PROVISIONAL → ACCEPTED \| REJECTED; `appliedInCurrentAnalysis` is a transient flag, not a status | Yes |
| "Architecture View Generation Pipeline" (7 steps) | Actual pipeline has 11+ internal steps | Expand |

---

## 3. Recommendations

1. **Create `docs/en/DECISION_PIPELINE.md`** — detailed technical reference for
   the full pipeline from LLM scoring through diagram export.
2. **Fix terminology in `CONCEPTS.md`** — correct PENDING → PROVISIONAL.
3. **Do not expand `ARCHITECTURE.md`** — it serves as a high-level overview;
   link to the new detailed pipeline doc instead.
4. **Mirror to `docs/de/`** — maintain the German translation.

---

## 4. Scope of Current Changes

This PR delivers items 1, 2, and 4. Item 3 (cross-reference from
`ARCHITECTURE.md`) is included as a minimal link addition.
