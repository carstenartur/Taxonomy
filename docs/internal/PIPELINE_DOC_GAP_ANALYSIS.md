# Decision Pipeline — Documentation Gap Analysis

This document tracked gaps in the existing documentation for the analysis
and architecture-view decision pipeline. All gaps identified below have
been **resolved** through the creation of `docs/en/DECISION_PIPELINE.md`,
terminology fixes across the documentation, and code simplifications.

**Status as of 2026-03-31: ✅ All 10 gaps resolved.**

---

## 1. What Is Documented

| Topic | Where | Status |
|-------|-------|--------|
| High-level pipeline overview (7 high-level steps; 11 internal Phase 3 steps) | `docs/en/ARCHITECTURE.md` § Architecture View Generation Pipeline + `docs/en/DECISION_PIPELINE.md` § Phase 3 | ✅ Correct — high-level overview links to detailed reference |
| Concept definitions (score, anchor, view, hypothesis) | `docs/en/CONCEPTS.md` | ✅ Correct — uses PROVISIONAL terminology |
| Relation hypothesis lifecycle | `docs/en/CONCEPTS.md` § Relation Hypothesis + `docs/en/DECISION_PIPELINE.md` § Relation Lifecycle | ✅ Correct — PROVISIONAL → ACCEPTED \| REJECTED |
| Export format list and selection policies | `docs/en/ARCHITECTURE.md` § Export Formats + `docs/en/DECISION_PIPELINE.md` § Phase 4 + `docs/en/PREFERENCES.md` | ✅ Complete — 4 policies, 11-step curation, container semantics |
| Relation seed overview | `docs/en/RELATION_SEEDS.md` + `docs/en/DECISION_PIPELINE.md` § Seed Relations | ✅ Complete — CSV format and architecture view integration |

---

## 2. Resolved Gaps

### 2.1 LLM Scoring Phase — ✅ Resolved

**Resolution:** Fully documented in `DECISION_PIPELINE.md` § Phase 1 — LLM Scoring.
Covers: budget-propagation pattern, root priority order (BP, CP, CR, CO, CI, UA, BR, IP),
recursive descent, skip-on-zero, rate-limit handling (→ PARTIAL status).

### 2.2 Anchor Selection — ✅ Resolved

**Resolution:** Fully documented in `DECISION_PIPELINE.md` § Step 1: Anchor Selection.
Covers: primary threshold (≥ 70), fallback (top-3 with score ≥ 50), relevance = directScore / 100.
Constants centralised in `PipelineConstants`.

### 2.3 Relevance Propagation Constants — ✅ Resolved

**Resolution:** Fully documented in `DECISION_PIPELINE.md` § Step 2: Relevance Propagation.
All constants documented: MAX_HOPS=2, MIN_RELEVANCE=0.35, HOP_DECAY=0.70,
per-type weights (REALIZES 0.80, SUPPORTS 0.75, FULFILLS 0.70, USES 0.65, DEPENDS_ON 0.60).
Constants Reference table lists all values with their source locations.

### 2.4 Impact Selection vs Trace — ✅ Resolved

**Resolution:** Fully documented in `DECISION_PIPELINE.md` § Step 10: Impact Selection
and § Trace vs Impact vs Export. Composite impact score formula documented with all 5
weights. Three relation categories (impact, trace, seed) clearly distinguished.
`RelationOrigin.category()` auto-derives the display category from origin.

### 2.5 Relation Lifecycle — ✅ Resolved

**Resolution:** Fully documented in `DECISION_PIPELINE.md` § Relation Lifecycle
with ASCII state diagram. States: PROVISIONAL → ACCEPTED (creates TaxonomyRelation,
Git commit to `accepted` branch) or REJECTED (DB update only). `appliedInCurrentAnalysis`
documented as transient session-only flag. `PROPOSED` status documented as reserved
for relation-proposal feature. Terminology fixed in USER_GUIDE (en/de) and
GIT_INTEGRATION (en/de): PENDING → PROVISIONAL.

### 2.6 Seed Relations — ✅ Resolved

**Resolution:** Fully documented in `DECISION_PIPELINE.md` § Seed Relations.
Covers: how seeds provide the BFS relation graph, classification as CATEGORY_SEED
with origin TAXONOMY_SEED, UI rendering in collapsed section, seed types
(TYPE_DEFAULT, FRAMEWORK_SEED, SOURCE_DERIVED), priority tier 5 ranking.

### 2.7 Persistence Model — ✅ Resolved

**Resolution:** Fully documented in `DECISION_PIPELINE.md` § Persistence Model.
Complete table: taxonomy_node/taxonomy_relation (HSQLDB), relation_hypothesis/
relation_evidence (HSQLDB), git_packs/git_reflog (JGit), SavedAnalysis (not
server-persisted), architecture views and diagram models (per-request only).
JGit repository architecture documented (system vs workspace, draft/accepted branches).

### 2.8 Export / Diagram Policy — ✅ Resolved

**Resolution:** Fully documented in `DECISION_PIPELINE.md` § Phase 4 — Diagram
Projection and Export. All 4 presets (defaultImpact, leafOnly, clustering, trace)
with exact config values. 11-step curation pipeline documented. Container-node
semantics per exporter (Mermaid subgraph, ArchiMate/Structurizr excluded, Visio group).
`PREFERENCES.md` documents runtime diagram.policy setting.

### 2.9 Leaf Enrichment — ✅ Resolved

**Resolution:** Fully documented in `DECISION_PIPELINE.md` § Step 4: Leaf Enrichment.
Rule: up to 3 leaf nodes per root with score ≥ 5, origin ENRICHED_LEAF.
Constants (MAX_LEAF_ENRICHMENT=3, LEAF_ENRICHMENT_MIN_SCORE=5) centralised
in `PipelineConstants` and listed in Constants Reference.

### 2.10 Terminology Inconsistencies — ✅ Resolved

| Term (was) | Fix applied | Where |
|---|---|---|
| "PENDING" hypothesis status | Changed to PROVISIONAL | `CONCEPTS.md`, `USER_GUIDE.md`, `GIT_INTEGRATION.md` (en + de) |
| "APPLIED" as lifecycle status | Removed; documented `appliedInCurrentAnalysis` as transient flag | `GIT_INTEGRATION.md` (en + de), `DECISION_PIPELINE.md` |
| "IMPACT_SELECTED" node origin | Renamed to `IMPACT_PROMOTED` in code and docs | `NodeOrigin.java`, `DECISION_PIPELINE.md` |
| "7-step pipeline" without detail | Clarified as 7 high-level steps with 11 internal Phase 3 steps | `ARCHITECTURE.md` (en + de) |

---

## 3. Simplifications Applied

The following refactoring addressed root causes of conceptual drift
identified by the gap analysis:

| Change | What it resolves |
|--------|-----------------|
| **`PipelineConstants`** — centralised anchor/enrichment thresholds | Gaps 2.2, 2.3: scattered duplicate constants now have one source of truth |
| **`RelationOrigin.category()`** — each origin derives its display category | Gaps 2.4, 2.6: `relationCategory` string no longer maintained independently; origin is the single source of truth |
| **`setOrigin()` auto-syncs category** — `RequirementRelationshipView` keeps both fields consistent automatically | Gap 2.4: eliminates "seed vs trace vs impact" confusion in the code |
| **`IMPACT_SELECTED` → `IMPACT_PROMOTED`** — renamed to clarify semantics | Gap 2.10: the old name was confused with the `selectedForImpact` boolean; the new name makes the distinction self-explanatory |

---

## 4. Remaining Known Limitations

These are **not documentation gaps** but known architectural limitations
documented in `DECISION_PIPELINE.md` § Known Limitations and Incomplete Areas:

1. `PROPOSED` status unused by analysis pipeline (reserved for relation-proposal feature)
2. `LLM_SUPPORTED` origin unused (reserved for future LLM-based confirmation)
3. `SavedAnalysis` not server-persisted (client-side storage only)
4. Budget propagation not strictly enforced (LLM may over/under-count)
5. Single-provider scoring (no ensemble/cross-validation)
