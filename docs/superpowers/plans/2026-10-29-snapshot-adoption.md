# Controlled frozen snapshot adoption implementation plan

> For agentic workers: execute inline using superpowers:executing-plans; controller has already authorized implementation.

**Goal:** Preview and explicitly adopt retained analysis into the existing private architecture journal, then edit, invert, checkpoint and export normally.

**Architecture:** An application composition service authorizes independent source/target identities, retrieves the retained full snapshot and exact requirement version, and generates an internal `AdoptAnalysisSnapshot`. The existing editor preview/execute boundary remains authoritative. The DSL command preserves existing objects and creates one canonical provenance view with explicit relation membership.

**Tech Stack:** Java 25, Spring MVC/Security, existing DSL/journal/JGit, Thymeleaf and modular JavaScript.

**Spec:** ../specs/2026-10-28-architecture-workflow-design.md and controller adoption brief.

## Global constraints

No renderer payload, model call, review approval, parallel graph/history store or Git writes on adoption. No public wire graph command. Exact source tuple and workspace revision are mandatory. Preview is read-only; normal editor acceptance owns idempotency, history and checkpoint safety. EN/DE and base paths remain supported.

## Review focus

- Old snapshots retain old requirement versions after later edits.
- Private sources belonging to another user and unrelated repositories fail closed.
- Reused titles, descriptions, ownership and relation status/evidence survive.
- Snapshot relation membership excludes later edges between included nodes.
- Later dependent edits prevent unsafe undo; unsupported sources fail atomically.

## Tasks

- [x] Add failing domain adoption tests: exact reuse, proposed new edges, provenance/view identity, changed-source collision, invalid types/endpoints, undo/redo and dependency safety. Run RED, implement internal bounded command and narrow view inversion, run GREEN.
- [x] Add journal tests for preview/no writes, single revision, replay/restart and checkpoint/inversion. Fingerprint every internal command field. Run RED/GREEN.
- [x] Add composition/controller tests for exact immutable source/version, source/target authorization, preview fingerprint, HTTP revision and security filters. Implement application composition endpoints with existing Context/Metadata and existing editor responses.
- [x] Add Maven-owned JavaScript contracts for source preservation through copy, preview/explicit acceptance, uncertain transport identity, selected snapshot view/full model and provenance navigation. Implement accessible EN/DE editor/workbench flow.
- [x] Run focused module/JS verification, review full diff, record exact evidence and limits, commit locally. Controller owns canonical whole-reactor verification and publication.

## Verification record

Meaningful RED failures established missing adoption, unbound replay payload, missing selected-view export, uncaught source not-found, collision preview behavior and malformed source handling. GREEN: 419 DSL tests; full workspace/upstream run (244 domain, 418 DSL at that run, 21 extension API, 573 export, 1158 workspace); 17 focused application API/composition/security/export tests; 1602 JavaScript contracts. Startup Byte Buddy agent and escalated native Git/GPG execution were needed locally; product tests were retained unchanged.

Normal package succeeded after the controller restored the verified Ant dependency. The Failsafe-owned `AnalysisSnapshotAdoptionUiIT` passed with the existing BrowserSession adapter and exact Chrome/ChromeDriver149.0.7827.55: historical central source on a nondefault branch → private copy on its own target branch → preview without writes → explicit acceptance and exact-ID uncertain-response replay → checkpoint → usable pre-adoption history → undo/redo → old source text return. A completely new Spring context then reopened the same file database within the same test JVM and proved replay, revision and canonical provenance retention. This is a Spring-context restart, not a separate JVM restart.

Independent review reproduced and corrected selected-provenance rendering, source A/B view selection and stale view IDs on historical links. Additional native evidence exposed missing full source transport pins and central resolver routing; both now have RED/GREEN regressions with explicit central read membership while private routing and central write isolation remain intact. Full JavaScript owner:1607 cases green; final central routing/guard selection:48 cases green. The canonical full-reactor command remains controller-owned and has not been claimed by these focused runs.
