# Concepts & Glossary

This document explains the key terms used throughout the Taxonomy Architecture Analyzer.

---

## Table of Contents

- [Taxonomy Node](#taxonomy-node)
- [Taxonomy Sheet](#taxonomy-sheet)
- [Relation](#relation)
- [Requirement Analysis](#requirement-analysis)
- [Score](#score)
- [Anchor Node](#anchor-node)
- [Relevance Propagation](#relevance-propagation)
- [Architecture View](#architecture-view)
- [Architecture Knowledge Graph](#architecture-knowledge-graph)
- [Requirement Impact](#requirement-impact)
- [Failure Impact](#failure-impact)
- [Gap Analysis](#gap-analysis)
- [Pattern Detection](#pattern-detection)
- [Relation Proposal](#relation-proposal)
- [Leaf Justification](#leaf-justification)
- [View Modes](#view-modes)
- [Relation Type](#relation-type)
- [Architecture DSL](#architecture-dsl)
- [Relation Hypothesis](#relation-hypothesis)
- [Relation Evidence](#relation-evidence)
- [Explanation Trace](#explanation-trace)
- [Canonical Architecture Model](#canonical-architecture-model)
- [Framework Mapping](#framework-mapping)
- [Quality Analysis](#quality-analysis)
- [Gap Analysis](#gap-analysis)
- [Workspace](#workspace)
- [Variant](#variant)
- [Workspace Projection](#workspace-projection)
- [Synchronisation (Sync)](#synchronisation-sync)
- [Conflict Resolution](#conflict-resolution)
- [Diverged State](#diverged-state)
- [Operation Result Feedback](#operation-result-feedback)

---

## Taxonomy Node

A single element in the C3 Taxonomy hierarchy. Each node has:

- **Code** — a hierarchical identifier from the centrally published C3 Taxonomy Catalogue workbook, e.g. `CP-1022` (Command and Control Capabilities) or `CR-1047` (Infrastructure Services). The eight root categories use two-letter codes (`BP`, `BR`, `CP`, `CI`, `CO`, `CR`, `IP`, `UA`); all child codes are defined in the workbook and cannot be invented locally.
- **Title** — a short English name, for example _"Secure Voice Service"_.
- **Description** — a longer English description explaining the node's purpose.
- **Level** — its depth in the hierarchy (0 = root, 1 = first-level child, etc.).

The catalogue contains approximately **2,500 nodes**.

---

## Taxonomy Sheet

The catalogue is divided into eight top-level sheets, each representing a distinct category:

| Code | Sheet Name |
|---|---|
| **BP** | Business Processes |
| **BR** | Business Roles |
| **CP** | Capabilities |
| **CI** | COI Services |
| **CO** | Communications Services |
| **CR** | Core Services |
| **IP** | Information Products |
| **UA** | User Applications |

Every node belongs to exactly one sheet.

---

## Relation

A directed link between two taxonomy nodes. Relations express how one element depends on, supports, or is associated with another. For example, a _Core Service_ may _support_ a _Capability_.

Relations form the edges of the [Architecture Knowledge Graph](#architecture-knowledge-graph).

---

## Requirement Analysis

The process of scoring every taxonomy node against a free-text business requirement. An AI language model evaluates how relevant each node is and returns a match percentage (0–100) for each.

---

## Score

A numeric value (0–100) that represents how relevant a taxonomy node is to a given requirement. Scores are colour-coded in the UI:

| Range | Colour | Meaning |
|---|---|---|
| **70–100** | Dark green | Strong match |
| **50–69** | Medium green | Moderate match |
| **1–49** | Light green | Weak match |
| **0** | No highlight | No match |

---

## Anchor Node

A node selected as a starting point for building an architecture view. By default, nodes with a score ≥ 70 are chosen as anchors. If fewer than three anchors are found, the threshold falls back to ≥ 50 (top 3).

---

## Relevance Propagation

After anchor nodes are selected, the system follows taxonomy relations outward from each anchor and assigns derived relevance scores to connected nodes. This expands the architecture view to include elements that are indirectly but meaningfully related to the original requirement.

---

## Architecture View

A structured model generated from scored analysis results. It contains:

- **Elements** — the taxonomy nodes selected as relevant (anchors plus propagated nodes).
- **Relationships** — the relations connecting those elements.

Architecture views can be exported to ArchiMate XML, Visio `.vsdx`, Mermaid flowcharts, or JSON.

---

## Architecture Knowledge Graph

The full graph formed by all taxonomy nodes (vertices) and their relations (edges). The graph explorer lets you traverse this graph by querying upstream dependencies, downstream dependants, or failure-impact neighbourhoods.

---

## Requirement Impact

An analysis that determines which parts of the architecture knowledge graph are affected by a given requirement. Starting from scored nodes, the system traces relations to identify the broader impact area.

---

## Failure Impact

A neighbourhood query that answers: _"If this node fails, what else is affected?"_ The query follows outgoing relations from the selected node to discover every element that directly or transitively depends on it.

---

## Gap Analysis

An analysis that compares the scored taxonomy results against the full relation graph to identify:

- **Missing relations** — pairs of elements that are both relevant but have no connecting relation.
- **Incomplete patterns** — standard architecture patterns that are only partially present.

---

## Pattern Detection

Verification of standard architecture patterns within scored results. Supported patterns include:

| Pattern | Description |
|---|---|
| **Full Stack** | A complete vertical slice from User Application through Core/COI Service down to Communications Service |
| **App Chain** | A chain of User Applications linked through shared Information Products |
| **Role Chain** | A sequence of Business Roles connected through Business Processes |

---

## Relation Proposal

An AI-generated suggestion for a new relation between two taxonomy nodes. Proposals enter a review queue where a human reviewer can **accept** or **reject** each one before it becomes part of the knowledge graph.

---

## Leaf Justification

A natural-language explanation generated by the AI that describes _why_ a specific leaf node received a high score for a given requirement. Useful for auditing and validating analysis results.

---

## View Modes

The taxonomy tree can be displayed in five different visual layouts:

| Mode | Description |
|---|---|
| **List** | Traditional collapsible tree with indented nodes |
| **Tabs** | Each taxonomy sheet in a separate tab |
| **Sunburst** | Radial hierarchical chart showing the full tree at a glance |
| **Tree** | Horizontal dendrogram layout |
| **Decision Map** | Treemap-style layout for comparing node sizes and scores |

---

## Relation Type

Each relation has a type that defines the nature of the connection between two taxonomy nodes. The system defines 10 relation types, each corresponding to a standard architecture framework concept:

| Type | Source → Target | Standard |
|---|---|---|
| **REALIZES** | Capability → Core Service | NAF NCV-2 |
| **SUPPORTS** | Core Service → Business Process | TOGAF Business Architecture |
| **CONSUMES** | Business Process → Information Product | TOGAF Data Architecture |
| **USES** | User Application → Core Service | NAF NSV-1 |
| **FULFILLS** | COI Service → Capability | NAF NCV-5 |
| **ASSIGNED_TO** | Business Role → Business Process | TOGAF Org mapping |
| **DEPENDS_ON** | Core Service → Core Service | Technical dependency |
| **PRODUCES** | Business Process → Information Product | Data flow |
| **COMMUNICATES_WITH** | Communications Service → Core Service | NAF NSOV |
| **RELATED_TO** | Any → Any | Generic fallback |

The `RelationCompatibilityMatrix` enforces which source and target root categories are valid for each type.

---

## Architecture DSL

A text-based domain-specific language for describing architecture models. DSL documents use the `.taxdsl` format with brace-delimited blocks (`element ... {`, `relation ... {`, `meta {`) and `key: value;` property syntax for elements, relations, and domain metadata.

DSL documents are version-controlled using JGit with all Git objects stored in the database (not the filesystem). The system supports branching, merging, cherry-picking, and semantic diffs between versions.

---

## Relation Hypothesis

A provisional relation generated during LLM analysis. Unlike [Relation Proposals](#relation-proposal) (which are generated on demand), hypotheses are created automatically as part of the analysis pipeline.

Hypotheses follow a lifecycle: **PENDING** → **ACCEPTED** (creates a confirmed `TaxonomyRelation`), **REJECTED** (discarded), or **APPLIED** (applied for the current session only, without persisting).

---

## Relation Evidence

Supporting data for a relation hypothesis or proposal. Evidence records capture why a relation was suggested, including the LLM response text, the analysis context, and any confidence scores.

---

## Explanation Trace

A structured reasoning chain that explains why a taxonomy node received a particular score for a given requirement. Traces include the LLM's reasoning, contributing factors, and confidence level.

---

## Canonical Architecture Model

The internal representation of an architecture described in DSL. It contains:

- **Elements** — architecture building blocks (capabilities, services, applications, etc.)
- **Relations** — directed connections between elements
- **Requirements** — the business requirements that motivated the architecture
- **Mappings** — links between requirements and architecture elements
- **Views** — named subsets of elements and relations
- **Evidence** — justification data for relations

### Data Model Layers

The system separates **imported canonical taxonomy data** from **user-managed architecture overlays**:

| Layer | Content | Mutability |
|---|---|---|
| **Imported Taxonomy Baseline** | Node codes, titles, descriptions, hierarchy (from C3 Catalogue) | Read-only in normal workflows |
| **Architecture Overlays** | Relations, mappings, views, evidence | Freely editable by users |
| **Local Extensions** | `x-*` attributes (aliases, notes, ownership, criticality) | Freely editable by users |
| **Taxonomy Maintenance** | Corrections to canonical titles/descriptions | Restricted to administrators |

Normal user workflows primarily create and modify architecture overlays (Layer 2) and local extensions (Layer 3). Imported taxonomy titles and descriptions (Layer 1) are treated as read-only unless the user is performing explicit taxonomy maintenance. See [Git Integration — Data Model Layers](GIT_INTEGRATION.md#data-model-layers) for details.

---

## Framework Mapping

A framework mapping converts elements and relations from an external architecture framework (such as UAF, APQC, or C4/Structurizr) into the taxonomy's internal representation. Each supported framework has a **mapping profile** that defines how external types correspond to taxonomy root codes and relation types.

For example, the UAF profile maps `Capability` → CP, `OperationalActivity` → BP, `System` → UA. The APQC profile maps hierarchy levels (1–5) to different root codes. The C4 profile maps `SoftwareSystem` → SY, `Container` → UA, `Component` → CM.

Imported elements carry an `x-source-framework` extension attribute that records which framework they originated from.

See [Framework Import](FRAMEWORK_IMPORT.md) for full mapping tables and the import workflow.

---

## Quality Analysis

Quality analysis evaluates the completeness and consistency of the architecture model. It examines:

- **Relation completeness** — whether elements have the expected outgoing and incoming relations based on their type
- **Orphan detection** — elements with no relations at all
- **Type consistency** — whether relation source/target types match the compatibility matrix

The Quality Dashboard (📊) in the right panel displays these metrics for the current relation proposals.

---

## Gap Analysis

Gap analysis identifies missing architecture coverage by comparing the current model against expected patterns:

- **Missing relations** — expected relations that do not exist between related elements
- **Incomplete patterns** — partial matches of known architecture patterns (Full Stack, App Chain, Role Chain)
- **APQC coverage** — how well the taxonomy maps to APQC Process Classification Framework categories

API endpoint: `GET /api/gap/apqc-coverage` returns `ApqcCoverageResult` with coverage statistics.

See [User Guide](USER_GUIDE.md) § 11e for the Gap Analysis panel.

---

## Workspace

An isolated editing environment for a single user. Each workspace provides:

- **Independent context navigation** — browser-like back/forward through architecture versions
- **Projection tracking** — per-user materialization state so one user's refresh doesn't affect another
- **Operation isolation** — merge, cherry-pick, and revert operations are tracked per workspace
- **Branch-level isolation** — the underlying Git repository is shared, but each user works on their own branch

Workspaces are created lazily on first access and persist across sessions. The in-memory state (current context, navigation history) is held by `WorkspaceManager`; persistent metadata (branch, timestamps) is stored in the `user_workspace` table.

See [Architecture](ARCHITECTURE.md) § Multi-User Workspace for the design overview.

---

## Variant

A named branch in the Git-backed DSL repository. Variants allow users to explore alternative architecture designs without affecting the main (shared) branch. Key operations:

| Operation | Description |
|---|---|
| **Create** | Fork a new branch from the current context |
| **Switch** | Open a different variant for editing |
| **Compare** | Semantic diff between two variants |
| **Merge** | Integrate changes from one variant into another |
| **Copy Back** | Selectively transfer elements from a read-only variant to the editable workspace |

Variants are displayed in the **Variants** sub-tab of the Versions tab. Each variant shows its branch name, latest commit, and commit count.

---

## Workspace Projection

A per-user materialized view of the DSL model at a specific commit. The projection converts the text-based DSL into database entities (taxonomy nodes, relations) that power the UI and search index.

Each user's projection is tracked independently:

- **Projection commit** — the SHA of the commit that was last materialized
- **Index commit** — the SHA of the commit the search index was last built from
- **Staleness** — the projection is stale when HEAD has advanced beyond the projection commit

This allows multiple users to work on different branches without interfering with each other's materialized state.

---

## Synchronisation (Sync)

The process of exchanging changes between a user's workspace and the shared integration repository. Two main operations:

| Operation | Direction | Description |
|---|---|---|
| **Sync from Shared** | Shared → User | Merges the latest shared branch changes into the user's branch |
| **Publish** | User → Shared | Merges the user's branch changes into the shared branch |

The sync state tracks:
- **Status**: `UP_TO_DATE`, `BEHIND` (shared has newer commits), `AHEAD` (user has unpublished commits), `DIVERGED` (both have changed)
- **Unpublished commit count**: Number of user commits not yet merged into shared

See [User Guide](USER_GUIDE.md) § 11 for the Sync sub-tab in the Versions tab.

## Conflict Resolution

When a merge or cherry-pick operation cannot be completed automatically (because both sides have modified the same DSL content), the system enters a **conflict state**. The conflict resolution UI displays:

- **Ours (target)** — the DSL content on the target branch
- **Theirs (source)** — the DSL content from the source branch or cherry-picked commit
- **Resolved** — an editable area where the user manually composes the final content

Quickfire options **Use Ours** / **Use Theirs** let the user pick one side entirely; the
**Resolve & Commit** button commits the resolved content to the target branch.

API support: `GET /api/dsl/merge/conflicts`, `POST /api/dsl/merge/resolve`,
`GET /api/dsl/cherry-pick/conflicts`, `POST /api/dsl/cherry-pick/resolve`.

## Diverged State

When both the user's branch and the shared integration branch have commits that the other doesn't, the sync state is **DIVERGED**. Three resolution strategies are available:

| Strategy | Description |
|---|---|
| **MERGE** | Attempt to merge shared changes into the user's branch. May fail if content conflicts exist. |
| **KEEP_MINE** | Publish the user's version to the shared repository, overwriting shared changes. |
| **TAKE_SHARED** | Replace the user's branch with the shared version, discarding local changes. |

API support: `POST /api/workspace/resolve-diverged?strategy=MERGE|KEEP_MINE|TAKE_SHARED`.

## Operation Result Feedback

Every Git operation (merge, cherry-pick, publish, sync, branch delete) produces visual feedback via a **Bootstrap toast notification** in the bottom-right corner. The toast shows:
- ✅ Success with operation summary
- ❌ Failure with error details
- ⚠️ Warnings (e.g. conflict detected)
