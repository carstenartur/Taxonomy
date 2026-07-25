# UI, role, workflow, state, and accessibility acceptance

The checked contract lives in `.github/ui-acceptance-matrix.json`. CI rejects undeclared or drifted profile combinations instead of silently reducing coverage.

## Test architecture

Three complementary browser gates are authoritative:

1. **UI Role and State Acceptance** exercises repeated analysis, stale and provider-error states, proposal feedback, accessible dialogs, keyboard operation, responsive reflow, Reduced Motion, Forced Colors, and representative Chromium, Firefox, and WebKit profiles.
2. **UI Primary Workflow Acceptance** runs the real product workflows as USER, ARCHITECT, and ADMIN. It covers analysis validation, role-aware controls, exports, proposals, relations, document import, framework import, password replacement, workspace conflicts, remote failures, and publish success.
3. **UI Special Modes Acceptance** renders a PARTIAL analysis response, enforces WCAG text-spacing overrides, verifies reflow, and proves that a failed workspace status poll produces visible, accessible offline guidance.

The existing standard UI and Accessibility workflows remain independent regression gates. They are not replaced by the expanded matrix.

## Roles and capability surfaces

- **USER** can read, analyse, search, and export. Architecture mutation controls are hidden or disabled, and direct mutation attempts must still return HTTP 403.
- **ARCHITECT** can create and review proposals, manage relations, and execute architecture imports. Administrator-only workspace controls remain unavailable.
- **ADMIN** receives architecture and administrative capabilities, including workspace publication and conflict-resolution controls.

`GET /api/account/me` exposes a read-only normalized role context. The browser uses that context to align the visible capability surface with the same server-side authorization rules that remain authoritative for every request.

Each browser job provisions deterministic local accounts through the real administrator API using pre-emptive HTTP Basic authentication. End-user authentication remains keyboard-operated form login, preserving browser-session CSRF protection.

## Primary workflows and states

The matrix covers the following product families:

| Workflow | Representative checked states |
|---|---|
| Authentication and navigation | keyboard login, role-specific navigation, unavailable mutation controls |
| Requirement analysis | empty input, loading, success, PARTIAL, stale result, provider failure, live announcement |
| Proposal review | pending, accept, reject, revert/undo, bulk accept, USER denial |
| Relation management | read-only, create, duplicate conflict, confirmed delete, USER denial |
| Framework and ArchiMate import | preview success, materialization capability, invalid-file error |
| Document import | upload, candidate review, selection surface, resource-limit error |
| Export | local download, backend failure, accessible non-blocking feedback |
| Password replacement | confirmation error, focusable alert, successful replacement |
| Workspace synchronization | diverged conflict, offline poll, remote error, publish success, administrator-only controls |
| Dialogs | focus entry, contained action, close, focus restoration |

Expected error states are explicit evidence, not ignored console noise. The role/state runner permits only the deliberately simulated analysis 503 and USER proposal 403; every other HTTP failure remains blocking.

## Browser, zoom, and visual modes

The checked profiles include:

- desktop Chromium / USER;
- desktop Firefox / ARCHITECT;
- mobile portrait WebKit / ADMIN;
- narrow landscape Firefox / USER;
- Chromium equivalents of 200% and 400% zoom;
- Chromium Forced Colors / ARCHITECT;
- Chromium WCAG text spacing / ADMIN;
- Reduced Motion in all role/state profiles.

Zoom profiles reduce the effective CSS viewport and fail on document-level horizontal scrolling. The text-spacing gate measures at least 1.5 line height, 0.12 em letter spacing, 0.16 em word spacing, and 2 em paragraph spacing before checking reflow and axe results.

## Dynamic accessibility checks

Axe runs after dynamic content is populated, including analysis success, PARTIAL and error states, proposal result states, import review/error panels, relation conflicts, dialogs, workspace conflicts, offline warnings, and operation toasts.

Critical, serious, and moderate findings fail. JavaScript exceptions, unexpected same-origin HTTP failures, external asset requests, missing focus restoration, and horizontal overflow also fail.

The expanded QA found and fixed production issues rather than suppressing them:

- score-dependent node colours and action buttons with insufficient contrast;
- hard-coded Git-status colours under Forced Colors;
- an analysis spinner removed after the first execution, breaking later error handling;
- role-inappropriate mutation controls;
- native blocking export alerts;
- silent workspace offline polling;
- screenshots exceeding browser bitmap-height limits.

## Evidence retention

Every relevant state archives a combination of:

- PNG screenshots; pages above the browser bitmap limit are split into deterministic, non-overlapping segments with a JSON manifest;
- serialized DOM HTML;
- ARIA snapshots where supported;
- axe result details;
- viewport, role, HTTP, console, and external-request evidence;
- application logs and the full failure stack;
- a machine-readable report summarized in the GitHub job.

Artifacts are retained for 30 days. All external Actions are commit-SHA pinned and are covered by the repository supply-chain gate. Normal CI additionally enforces reactor-wide coverage, CodeQL, Trivy, database/container compatibility, documentation integrity, frontend API boundaries, and the strict bounded-context architecture rule.
