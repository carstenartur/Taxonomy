# Server-owned Copilot Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Complete and persist Copilot work on the server and replace redundant managed-result draft uploads with durable references.

**Architecture:** Extend the existing finalization lifecycle. Share a deterministic retained-evidence report service between distributed, local and portfolio analysis. Restore compact drafts through existing scoped result APIs.

**Tech Stack:** Java 25, Spring/JPA, Artemis, Maven Wrapper, modular JavaScript and Node behavioral fixtures.

**Spec:** `docs/superpowers/specs/2026-10-27-server-owned-copilot-design.md`

## Global Constraints

- Java 25 and the checked-in Maven Wrapper; no additional workflows.
- Keep authenticated user/workspace/repository/branch ownership checks.
- No remote provider calls in tests or new Copilot computations.
- Retain existing partial-result, cancellation, manual-edit and legacy-draft behavior.
- The two-million-character draft limit is not the architectural fix.

## Review Focus

- Browser closure after core completion must not strand server follow-up work.
- Database failure or completion redelivery must not lose or duplicate result effects.
- User/workspace changes during asynchronous restoration must fence stale responses.
- Manual edits and edited input must not be replaced by an older canonical result.
- Missing relationship evidence must not become a negative architectural assertion.

### Task 1: Retained-evidence Copilot reports

**Files:** Create domain `AnalysisCopilotResult`, extend `AnalysisResult`, create architecture `AnalysisCopilotService` and focused behavioral tests.

**Interfaces:** Produces `AnalysisCopilotService.analyze(AnalysisResult analysis, String businessText)` returning the spec's `AnalysisCopilotResult`; JSON field `copilot`.

- [x] Write failing tests for complete verified evidence, partial/scope exclusion, unknown relationships, deterministic retained-node recommendations and serialization.
- [x] Verify the missing behavior, implement the shared service without global reads, and run the focused architecture/domain tests.
- [x] Review the service's evidence and recommendation boundaries.

### Task 2: Server completion integration

**Files:** `FrozenClusterAnalysisFinalizer`, `ClusterAnalysisStore`, `AnalyzeRequirementUseCase`, Artemis composition, `ProjectRequirementAnalysisService`, and their focused tests.

**Interfaces:** Consumes Task 1's service and payload. Existing analysis/continuation response shape adds only `copilot`.

- [x] Write failing lifecycle tests for Copilot payload before completion and preservation through durable result/continuation reads.
- [x] Integrate within existing finalization; preserve retryable database errors, idempotence and cancellation fencing.
- [x] Apply the same computation in local completion; reuse the stored payload in portfolio snapshots.
- [x] Run targeted lifecycle and portfolio tests and review cross-module wiring.

### Task 3: Browser rendering and compact drafts

**Files:** Existing core analysis, scoring, recovery, operation-coordinator and session JavaScript; focused `.github/scripts` behavioral tests.

**Interfaces:** Consumes `result.copilot`; writes `payload.analysisReference` and resolves via existing scoped server APIs.

- [x] Write failing tests for zero managed Copilot calculation POSTs and bounded saves with a multi-megabyte canonical result.
- [x] Render retained server reports in every result hydration path.
- [x] Compact only authoritative reconstructible results; keep full manual/legacy evidence.
- [x] Restore references under existing asynchronous guards, preserving edited input/view and tombstones; test missing/foreign references and late responses.
- [x] Run relevant existing and new browser fixtures; review preservation of manual actions.

### Task 4: Integration verification and documentation

**Files:** Relevant architecture/analysis docs and this plan's verification record.

- [ ] Run focused Maven/Node tests, then the repository's broader required verification command; record actual failures/prerequisites.
- [x] Review the combined diff independently and fix material findings with regression coverage.
- [ ] Update German/English behavior and limitations, commit on the isolated branch, and open one reviewable PR.

## Verification record before publication

Fresh verification on baseline `908b5aee022cb93377d01fa01fb534842ff95aad`
compiled and exercised the recovered implementation and review fixes. Earlier
checkout logs are not used as acceptance for these sources.

The combined Maven `verify -Ptest-local,architecture-tests` selection passed
**222 Java cases, all 1,496 nonbrowser JavaScript cases and seven real Chrome
continuation cases**, with zero failures, errors or skips. The explicit selection
covers Copilot derivation, durable finalization/redelivery, continuation and
cancellation, portfolio reuse, compact drafts, report reference authority,
portable JSON roundtrip and report rendering. A separate Maven invocation of
`JavascriptContractTest` in browser mode passed all **44 analysis UX cases**,
including portable Copilot import/export and canonical versus manual report
requests. The project coverage agents and existing test deadlines remained active.
After the final evidence-preservation fixes, all 1,496 JavaScript cases passed
again, and 28 focused report cases passed, including literal supporting evidence
and qualifiers for matched and incomplete patterns in Markdown, HTML and DOCX.

The unchanged root `architecture-tests` selector passed **563 executions** across
the complete 21-module reactor, including its required repeated guards, with no
failures, errors or skips. The reactor uses
`-Dsurefire.failIfNoSpecifiedTests=false` for modules that own none of the selected
tests. Its exact dependency baseline records the two reviewed composition-to-analysis
read boundaries; cycle, ownership and module assertions remain unchanged.

Review regressions first reproduced the missing JSON envelope, absent owned report
reference route, misleading legacy draft status, and omitted report qualifiers.
The fixes retain an explicit choice before replacing differing local evidence;
late edits and workspace changes fence that read. Canonical reports resolve an
authenticated saved reference, validate both identifiers, and use saved reasons,
language, provenance, partial/skipped status and pattern support. Portable JSON
remains display evidence and cannot authorize a server lookup.

This is focused verification, **not a full CI or coverage pass**. The required
`./mvnw -B verify -Pci -DrunOnnxTests=true` remains a separately recorded check.
Docker is absent from this execution
environment; container/database deployment acceptance remains an external gate.

Java 25, the existing Maven Wrapper, cached dependencies and Chrome for Testing
149 were used. Mockito was attached as a startup agent because this environment
does not permit runtime self-attachment. Chrome's matching headless shell was
used because ordinary Chrome cannot open its profile singleton socket here.
These are environment settings, not project or test-policy changes. Raw logs,
JUnit reports and summaries are under `target/jms-verification/` and are not
committed.
