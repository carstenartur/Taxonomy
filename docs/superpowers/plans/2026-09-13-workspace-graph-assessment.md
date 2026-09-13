# Workspace Graph Assessment Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` for the single bounded assessment task. Root owns the isolated worktree, evidence generation, Git, reviews and publication.

**Goal:** Complete the exhaustive remaining-edge assessment required by Slice E of #1043.

**Architecture:** Reuse the actual compiled production inventory and canonical dependency collector. Add a human-readable assessment and one structured inventory; introduce no runtime or enforcement change.

**Tech Stack:** Existing Java 21/ArchUnit measurement, JSON and Markdown.

**Spec:** `docs/superpowers/specs/2026-09-13-workspace-graph-assessment-design.md`.

## Global Constraints

- Change documentation and its structured evidence only; no production/test implementation, POM, selector, workflow, baseline, context map, exception, coverage floor, schema or review-policy change.
- Enumerate all remaining measured pairs exactly once and use only `owner-api`, `composition-orchestration`, and `residual-violation`. Never hide a pair to make the result look ready.
- Do not proceed to physical Maven extraction until the proposed module DAG is acyclic. Do not create a feature module or claim #628/#1043 complete.
- Current-head external review and main-targeted canonical CI, database, JGit consumer, UI, security, product and recovery gates remain required. No agent may post a human review attestation.

## Task 1: Classify every remaining workspace dependency and publish the assessment

Read the spec identified above and the root-provided ignored inputs in this plan's SDD directory: `measurement.json`, `measurement-metadata.json`, `canonical-baseline.json`, `architecture-module-graph.txt`, `provisional-classification.json`, and `implementation-context-notes.md`. The measurement is authoritative for pair membership; the provisional categories are unapproved suggestions. Production snapshot is `bd430ff41788289567de65d78d7d8b8f4adc02a8`.

Create only `docs/dev/WORKSPACE_DEPENDENCY_ASSESSMENT.md` and `docs/dev/workspace-dependency-classification.json`. The assessment explains the current graph, classification definitions and counts, relevant source-backed decisions, a small grouped table of remaining work, identity/branch/lazy-initialization constraints, evidence provenance and extraction/merge limits. Link the structured inventory for the exhaustive pair list. Do not duplicate all dependency descriptions into the prose or create speculative interfaces.

The JSON must have `schemaVersion:1`, `sourceCommit`, `baselineSha256`, `contextsSha256`, `inventory` (the measured production source/class and managed package/class-pair counts), `workspaceOutgoing` (an array, explicitly empty when measured empty), `knowledgeToWorkspace` (one entry per measured pair, sorted by origin then target), and `classificationCounts` (computed across both arrays). Each entry has exact `origin`, `target`, `originFile`, `targetFile`, one `classification`, a concrete `rationale`, and nonempty `evidence` listing relevant observed member/location descriptions. Each evidence string must come from the corresponding measured pair's dependency descriptions. Do not conflate class pairs with individual bytecode dependency occurrences.

- [x] Compare source responsibilities for every measured origin/target pair. Group identical owner contracts for efficient inspection, but inspect every caller's use. Neither a concrete target type nor an HTTP caller alone establishes a violation. Mark all 117 current reverse pairs exactly once; do not preserve provisional 85/25/7 counts unless the evidence supports them. Record changed provisional categories and reasons in the report.
- [x] Write the complete JSON and concise assessment. Distinguish the zero outgoing managed-context result and workspace readiness from the still-cyclic complete proposal. Describe concrete remaining work without moving source or promising an unreviewed API design. Keep configured primary/default-branch identity separate from literal `WorkspaceContext.SHARED` and preserve lazy/fail-closed built-in seed semantics in future-work notes.
- [x] Parse JSON and independently compare exact origin/target pair sets with `measurement.json`; reject duplicates, missing/extra pairs, unsupported categories, nonexistent source files, evidence not present in the measured pair, or counts that do not sum. Verify every referenced source still matches the snapshot. Check document links and whitespace. No new test or Maven run is needed for documentation-only changes; root has fresh native architecture evidence and exact regenerated-baseline comparison.
- [x] Write the full report to `task-1-report.md` in this plan's ignored SDD directory: source inspection coverage, classification decisions and provisional changes, commands/results, changed paths, evidence and limits. Freeze. No Git/index/HEAD/branch mutation, Maven, external API, network or subagents by the implementer; only the two deliverables and own ignored evidence/report are writable.

Root records the task BASE, commits the two frozen deliverables, obtains independent task and final branch reviews, and publishes a bounded draft stacked on the reviewed module gate. Existing source and policy hashes must remain identical; no repeated runtime test sweep for unchanged inputs. The main integration chain and genuine human review gate remain separate requirements.
