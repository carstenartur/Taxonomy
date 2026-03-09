# Concepts & Glossary

This document explains the key terms used throughout the NATO NC3T Taxonomy Browser.

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

---

## Taxonomy Node

A single element in the NATO C3 Taxonomy hierarchy. Each node has:

- **Code** — a hierarchical identifier such as `CP-3` (Capability, third entry) or `CR-3-1` (Core Service, third group, first child).
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

A node selected as a starting point for building an architecture view. By default, nodes with a score ≥ 70 are chosen as anchors. If fewer than two anchors are found, the threshold falls back to ≥ 50.

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
