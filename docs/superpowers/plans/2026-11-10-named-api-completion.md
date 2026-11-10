# Named API Client Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Complete the remaining authenticated and context-sensitive feature migrations in issue #626 without changing operation admission or source authority.

**Architecture:** Extend the existing canonical transport with an idempotent registry of shared lifecycle adapters. The same factory implements legacy and named request admission. Named feature clients retain immutable source context through response bodies, structured authentication failures, and non-retrying writes; SSE and large downloads keep their purpose-built adapters.

**Tech Stack:** Existing browser JavaScript globals, Node VM/browser contract drivers, Maven-owned JUnit JavaScript bridge, Java 25.

**Spec:** [Taxonomy issue #626](https://github.com/carstenartur/Taxonomy/issues/626).

## Global Constraints

- Preserve operation lifecycle #1354 and atomic context switching #746.
- No automatic retry of non-idempotent writes.
- Preserve URL/base-path, credential, CSRF, request-ID, structured error and safe download-name policy in the shared boundary.
- Preserve purpose-built EventSource/large-download adapters; do not force streams through JSON buffering.
- No second transport or generic frontend reorganization.
- Keep functional verification Maven-owned; do not weaken assertions, coverage, security rules, or timeouts.

## Review Focus

- Adapters loaded after the canonical client must still run once for named calls, preserving durable job registration and admission.
- Navigation or analysis changes while a body is loading must reject stale delivery even if workspace identity is unchanged.
- Caller cancellation and timeout must cover the entire JSON/blob body, including wrappers that suppress a stale result.
- Expected domain responses such as 202/404/409/412 must remain inspectable; 401/403 must always dispatch authentication transitions and reject.
- Explicit central pins and retained operation scopes must survive later workspace changes without accidental rerouting or resubmission.

---

### Task 1: Canonical lifecycle and context boundary

**Files:** `static/js/api/taxonomy-api-client.js`; operation coordinator and analysis-session routing/core; `.github/scripts/test-taxonomy-api-client.mjs`.

**Interfaces:** `installTransportAdapter(name, factory(nextFetch))` registers once and never calls the latest global fetch. `requestJson(url, init, options)` owns full JSON bodies. Raw `request()` keeps Response status/header access and guards body methods. `options.acceptedStatuses` allows domain statuses except 401/403. `options.contextSensitive:false` explicitly preserves durable/global work. Existing `pinnedWorkspaceId`, abort, timeout, and retry options remain supported.

- [x] Add failing real adapter-admission, body-timeout, expected-status and same-workspace stale-navigation tests.
- [x] Run the owned callbacks through `JavascriptContractTest` and record the expected failures.
- [x] Implement one shared adapter chain over captured base fetch; preserve the same factory on legacy paths.
- [x] Keep request/body cancellation and context guards active through parsing, including raw body methods.
- [x] Verify focused transport and operation-lifecycle callbacks before migrating features.

### Task 2: Named feature requests and lifecycle preservation

**Files:** Existing API and feature files for Relations, Search, DSL and infrastructure reads (security_graph_fixes); Workspace and Versioning (taxonomy_open_work); Portfolio, Import, Analysis, Export plus portfolio async and analysis-generation adapter factories (template_fixes).

**Interfaces:** Consume Task 1 APIs. Feature operations have named methods; API methods may use canonical `request` for expected domain statuses, `requestJson` for parsed bodies, and `requestBlob` for downloads. Durable monitors use explicit retained pins and `contextSensitive:false`; interactive calls keep immutable active context.

- [x] Inventory remaining direct calls and wrapper aliases, including inline page handlers and API modules that still call raw fetch.
- [x] Add feature regressions for 401/403, rapid workspace/navigation changes, whole-body cancellation, and one durable admission event.
- [x] Migrate calls while retaining domain status handling, document revisions, source pins, background operation registration, and download/stream contracts.
- [x] Run each affected existing driver and new named feature callbacks through the Maven owner.

### Task 3: Close bypasses and verify complete issue coverage

**Files:** `FrontendApiBoundaryPolicy.java` and tests; `.mvn/javascript-junit-sources.txt`; existing JS contract drivers.

**Interfaces:** No direct network transport or acquired aliases remain in migrated feature files. Every surviving infrastructure exception has a concrete reason and removal condition.

- [x] Remove obsolete allowlist entries and add behavioral coverage for real alias/wrapper bypass patterns.
- [x] Compare all required issue domains against the final caller inventory and record any genuine remaining exception.
- [x] Run focused Java boundary tests plus the complete affected JavaScript contracts through Maven/JUnit.
- [x] Review the combined change against all five review-focus risks; obtain root review before remote publication.

## Focused verification ledger

- Canonical/domain RED: 10 assertion failures, no errors/skips, across actual Search/DSL/Relations/Proposals/Hypotheses authentication and stale body methods.
- Raw stream RED: direct reader stale delivery; then two derived-stream regressions for a buffered tee branch and delayed transform. Both now retain the original deadline, context and cancellation fence.
- Header-auth RED: a stalled 403 problem body delayed invalidation of another interactive body. Invalidation now occurs at headers; optional error detail parsing cannot erase the auth status.
- Actual Search UI RED: a departed workspace result replaced the newer panel with an empty graph result. Actual Relations UI RED: an obsolete read error replaced the newer workspace panel.
- Core/domain and existing DSL/Search/base-path/role fixtures: Maven `JavascriptContractTest`, 208 callbacks, zero failures/errors/skips (`/tmp/api-core-feature-fixtures.log`).
- Portfolio/Import/Analysis/Export and shared factories: peer checkpoint `6df0b02c`; focused 272 callbacks green, with additional existing browse fixtures being completed.
- Workspace/Versioning: 10-callback RED with 8 assertion failures; 31 focused actual UI/navigation callbacks green; final policy inventory remains owned by taxonomy_open_work.
- Native Chromium is unavailable locally because its ProcessSingleton AF_UNIX socket cannot be created. No local browser pass is claimed; the combined exact-head browser gate remains hosted CI.
- Root will merge the complete committed #626 tree with #629 and execute the full tooling owner once there, followed by exact-head hosted CI. No remote publication from this worktree.

## Final committed-tree handoff

The complete affected-contract gate passed 995 Maven-owned callbacks with zero failures, errors or skips. It selected 26 sources: the 25 changed non-browser contract drivers, including both new actual-client/UI drivers, and the existing operation lifecycle contract. Command inputs are recorded in `/tmp/api-final-focused-sources.txt`; the complete Maven output is `/tmp/api-aggregate-focused-green.log`.

Additional review regressions were red-proven before their fixes: idle raw-stream cancellation after actual accepted ContextBar navigation; child body-scope cancellation after another clone completes; same-project source-snapshot changes; the real session router overwriting explicit query/central pins; and mutable Copilot reads losing their existing no-store/JSON negotiation metadata. A dedicated `taxonomy:repository-context-changed` event preserves AppChrome entity-event semantics. Shared context capture remains strict across authentication epochs.

The final policy owner executed 20 unit cases and one checkout integration test, all passing without skips (`target/api-policy-final-owner.log`). The ordinary native transport inventory and legacy allowlist are empty; only the explicit bounded infrastructure/SSE/public-asset exceptions remain in `docs/dev/FRONTEND_API_BOUNDARY.md`.

Independent review found no remaining concrete source blocker after these corrections. The source tree is ready for root integration with #629. The one combined full tooling owner, exact-head hosted native-browser/runtime gates, publication and issue closure remain root's integration responsibility; no local browser or full-CI completion is claimed.
