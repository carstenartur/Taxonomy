# Copilot diagnostics implementation plan

> Use the systematic debugging, test-driven development and subagent review workflows. Independent tasks have disjoint file ownership; only the coordinating agent commits or runs Maven.

**Goal:** Resolve the identified workspace conflict/polling and prompt display defects while retaining privacy and exact diagnostic evidence.
**Architecture:** Extend existing workspace metadata/error contracts; reuse the canonical client and runtime lifecycle; share a safe prompt display helper.
**Spec:** `docs/superpowers/specs/2026-11-24-copilot-diagnostics-design.md`

## Global constraints

- Java 25 and existing quality gates; one Maven process at a time.
- Preserve workspace authorization, explicit repository pinning, model inputs and literal source text.
- No private request content in logs, no prompt HTML interpretation, no implicit workspace provisioning on passive reads.
- Use focused failing regressions before implementation and preserve existing coverage.
- Shared checkout is isolated on `fix/copilot-diagnostics-workspace-polling`; disjoint parallel work is coordinated below.

## Task 1: Workspace lifecycle contract

**Owner/files:** Backend implementer; `taxonomy-workspace` readiness exceptions, resolver, synchronization controller and relevant tests.
**Interface:** ErrorResponse properties `code=WORKSPACE_NOT_READY`, `workspaceStatus=NOT_PROVISIONED|PROVISIONING|FAILED`; initial source code `INITIAL_SOURCE_PENDING`. Root owns serialization in the global handler.

- [x] Add and demonstrate failing lifecycle/status and access-control regressions.
- [x] Expose typed conflicts and permit the existing safe synchronization metadata observation.
- [x] Verify targeted Java regressions and review authorization boundaries.

## Task 2: Passive polling and conflict feedback

**Owner/files:** Frontend polling implementer; Git status, context bar, workspace sync, browse startup poll, API client/helper and related tests. Root coordinates template script registration and inline startup status.
**Interface:** Preserve cancellation and runtime identity fences; parse existing response bodies including the new error code, with compatible fallback for older servers.

- [x] Demonstrate repeated failure, misleading server contact and discarded conflict details with failing tests.
- [x] Implement bounded adaptive scheduling and meaningful conflict/lifecycle feedback.
- [x] Verify recovery, context switching, disposal and existing relevant contracts.

## Task 3: Readable prompt diagnostics

**Owner/files:** Prompt implementer; shared display helper, analysis progress/scoring/recovery renderers, prompt styles and tests. Root coordinates template registration.
**Interface:** Render safe readable text and retain unchanged raw prompt/copy value; fallback for malformed or truncated data.

- [x] Demonstrate escaped structured input and exact-copy regressions.
- [x] Implement shared renderer across all prompt displays.
- [x] Verify parser boundaries, hostile markup, literal escapes, truncation and existing diagnostic contracts.

## Task 4: Safe server diagnostics and integration

**Owner/files:** Coordinator; global exception handler and privacy/error tests, template wiring, documentation and QA evidence.

- [x] Demonstrate missing actionable safe metadata and typed lifecycle serialization.
- [x] Include trusted metadata and classify known expected readiness conflicts separately from genuine client errors.
- [x] Run focused Java/JavaScript suites and obtain independent whole-diff review, including the integrated race fixes.
- [ ] Verify native desktop/mobile browser cases in hosted CI; the local host/browser policies prevent this execution (see QA record).
- [x] Update user/developer documentation and commit the verified fix with evidence and remaining runtime limits.
- [ ] Publish the reviewed fix and inspect the required hosted checks.

## Review focus

Authorization and source selection must not be bypassed by metadata access. Polling must not hide errors, race across workspace changes or endlessly retry unauthorized requests. Structured rendering must decode JSON only once and always retain exact original text. Logs must retain privacy at TRACE as well as INFO/WARN. Do not describe the user's production 409 source as confirmed without an actual request sample.

## Independent review follow-up

- [x] Preserve the workspace conflict explanation through the actual session/draft subscriber, without triggering a redundant draft read from a Git 409.
- [x] Reset stopped or backed-off observations on a real source selection before first successful context hydration; clear the previous source's decorations immediately.
- [x] Prevent passive startup confirmation from bypassing context backoff or authentication stop; explicit refresh remains available.
- [x] Resume Git observation when the user cancels navigation after `beforeunload`.
- [x] Keep an older successful draft observation from reopening writes after a newer conflict, and fence deferred Git rendering against a newer authentication failure.
- [x] Re-review the integrated follow-up, rebase on the current main branch and rerun the complete non-browser JavaScript inventory with the targeted Java and architecture tests before publication.
