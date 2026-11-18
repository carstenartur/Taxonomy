# Incremental Taxonomy Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement the tasks with independent review. Steps use checkbox syntax for tracking.

**Goal:** Retain accepted taxonomy assessments and evaluate only selected missing work after changing the analysis scope.

**Architecture:** A new version-bound continuation copies authorized node checkpoints and replays them through the existing evaluator. Its effective scope is the union of results while a separate requested scope controls new node attempts. Both browser start buttons share admission and recovery.

**Tech Stack:** Java 25, Spring/JPA, Jackson 3, native browser JavaScript, JUnit and Node test contracts.

**Spec:** `docs/superpowers/specs/2026-11-18-incremental-taxonomies-design.md`

## Global Constraints
- Same source basis; source revision, workspace and branch are authorized server-side.
- Existing accepted scores and reasons remain exact; reused questions cause no new provider call.
- Result scope and requested work scope are distinct.
- Running or uncertain work keeps its current recovery identity.
- Preserve ordinary architecture opt-out and manual evidence edits.
- Use existing evaluator, DAG, journal bounds and per-operation budgets.
- Normal CI gates, reviews and exact-head squash merge; no release or deployment.
- Work only in `/workspace/scratch/c1f4c160b643/taxonomy-analysis-extension`, initially main `74bf4151d89c0b54ea50b7cedd18c6380ef67b4a`.

## Review Focus
- Partly complete unselected roots: retained diagnostics must not trigger extra calls or turn unknowns into zeroes.
- Relations-source ancestry: accepted values must have exact authorized question evidence.
- Restore: union scope must never broaden the originally requested work on retry.
- Lost admission response: keep one target ID and preserve old evidence.
- Changed provider/language/model or local scores: no silent reuse or erasure.

## Task 1: Durable taxonomy extension and provider-call regressions
**Ownership:** Java production/tests only; no JavaScript or guide changes.
**Files:** domain `AnalysisRequest`, `AnalysisModelBinding`; analysis `AnalysisApiController`, `AnalysisContinuationService`, `AnalysisContinuationStore`, existing local/distributed checkpoint-session adapters; focused new helper(s) for extension evidence if they keep the large services readable; JUnit recovery/cluster tests.
**Interfaces:**
- Consume public `taxonomySourceContinuationId:String`, `taxonomySourceVersion:Long`, desired `analysisScope`.
- Store `taxonomyRequestedScope:AnalysisScope`, effective union `analysisScope`; clear client-supplied server metadata.
- Return normal full `AnalysisResult`, effective union and normal recovery receipt. Existing API transport is unchanged.

- [x] Write a regression that starts a completed BP source then extends CP and asserts BP provider calls remain 1, CP calls become 1, and BP score/reason remain exact.
- [x] Run it on the baseline and record the meaningful failure.
- [x] Implement authorized source resolution, signature/replay contract, source evidence copy/seed and union/requested-scope separation.
- [x] Restrict new node attempts to requested roots in both journal adapters, preserving accepted replay and unselected open diagnostics.
- [x] Add regressions for partial groups/all versus CP-only, complete zero branches, chained/relations sources, scope/version/authority conflicts, no-op, local/distributed cancellation and duplicate/retry.
- [x] Run focused Java tests through `python /workspace/scratch/c1f4c160b643/tmp/analysis-extension-qa/run.py <FQCN...>`, with actual current sources compiled. Baseline 15 RelationsExtension tests are green.
- [x] Self-review and record RED/GREEN evidence; hand off for independent review.

## Task 2: Shared browser start decision and user guidance
**Ownership:** JavaScript, browser templates/i18n and existing user guides only; no Java production edits.
**Files:** core `taxonomy-analysis-recovery.js`, `taxonomy-scoring.js`, `taxonomy-analysis-scope.js`, `taxonomy-analysis-guidance.js`; coordinator/relations UI only as required; `index.html`; existing EN/DE message bundles; `docs/en/USER_GUIDE.md`, `docs/de/USER_GUIDE.md`; existing Node recovery/scope/readiness/lifecycle harnesses.
**Interfaces:**
- Send new target ID and the Task 1 public source reference for an extension.
- Read `request.taxonomyRequestedScope` on restored extensions when constructing continuation POSTs.
- Treat final result scope and source evidence as authoritative; share a single start-plan function for controls/guidance.

- [x] Write failing harness cases for completed BP → CP/all through both buttons, retaining old evidence before admission.
- [x] Add actual serialized transport assertions and run RED.
- [x] Implement the shared current-request decision, durable automatic starts, scope-normalization helpers and recovery-safe extension admission/rollback.
- [x] Preserve manual edits and source identity on running/uncertain operations; preserve architecture opt-out; handle current provider/language/model expectations.
- [x] Ensure newly accepted wider/nodes-only results cannot retain an old relation/architecture view merely because text matches.
- [x] Add no-op, mixed missing-root, changed-settings, lost-response and persisted-reload regressions.
- [x] Update concise German/English hints and guides for retained results, selected open work and changed analysis basis.
- [x] Run the focused Node suites plus their relevant lifecycle neighbors; record RED/GREEN and self-review.

## Task 3: Integration review, CI and merge
**Ownership:** root coordinates; independent reviewer reads complete diff.
- [x] Check Task 1/2 interface parity and read actual test reports.
- [x] Independently review accepted evidence, authority checks, scope/replay and both entry paths; resolve concrete findings.
- [x] Run `git diff --check`, combined focused verification, and scoped regression checks after review corrections.
- [ ] Recheck live main/open PRs, publish one focused PR, and monitor actual required jobs/logs on its exact head.
- [ ] Resolve review threads and required gate failures; do not bypass protections or send review requests.
- [ ] Squash merge with expected head after required gates; verify main contains the tested tree.
- [ ] Report resulting behavior, validation and the need to update a running installation.
