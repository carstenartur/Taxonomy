# ADR 0004: Analysis working-draft lifecycle and workspace pinning

- **Status:** Accepted
- **Date:** 2026-08-19
- **Related:** ADR 0001, ADR 0003

## Context

The original analysis page kept scores, explanations and the requirement-derived
architecture only in browser memory. Editing the requirement text marked the
scores as stale, but the reset action cleared only part of the state. Architecture
views, provisional relationships and secondary analysis panels could therefore
still display results from the previous text. Reloading the page had the opposite
problem: it silently discarded all unfinished work.

A second ambiguity concerned concurrent sessions. The server historically kept
one active workspace selection per username. A workspace change in another tab or
device could consequently influence later requests from an already open page.

The project portfolio already provides the authoritative long-lived model:
stable requirements, immutable text versions and immutable analysis snapshots.
The ad-hoc analysis page must not create a competing history model, but it still
needs a safe resumable working copy.

## Decision

### 1. One mutable working draft per exact tenant scope and user

The ad-hoc analysis page stores one `AnalysisWorkingDraft` for the exact
repository, workspace and branch scope plus the authenticated username. The
payload contains the requirement text and the core resumable analysis state,
including scores, reasons, discrepancies, architecture view, provisional
relationships and interactive expansion progress.

The working draft is not an immutable requirement version and is not part of the
project portfolio until the user explicitly promotes it.

### 2. Optimistic concurrency is mandatory

Every draft carries a JPA `@Version`. Updates and deletes must submit the version
last observed by the browser. A stale tab receives HTTP 409 and cannot silently
overwrite a newer state from another tab or device.

### 3. Browser tabs pin their workspace explicitly

Each tab remembers its workspace ID in `sessionStorage` and sends
`X-Taxonomy-Workspace-Id` with same-origin API requests. The server validates
ownership and resolves repository context from this explicit identity instead of
using another session's current workspace selection. An intentional workspace
switch updates the per-tab identity and reloads the page.

### 4. Requirement-text changes invalidate every derived result

Discarding the previous analysis clears the complete requirement-derived state:

- scores, reasons and discrepancies,
- architecture and summary views,
- provisional relationship hypotheses,
- analysis and LLM logs,
- Copilot, gap, pattern, recommendation and requirement-impact panels,
- analysis provenance and export availability.

It does **not** delete taxonomy data, projects, requirement versions, Git history,
confirmed relationships, portfolio solutions or products.

### 5. The UI exposes business actions rather than one ambiguous reset

When edited text makes the displayed analysis stale, the user can:

- discard only the text edit,
- discard the previous ad-hoc analysis while keeping the new text,
- create an additional requirement in an existing project,
- create a new empty project,
- or, when an explicit project/requirement context is present, create a new
  immutable version of that requirement.

Creating a new empty architecture therefore means creating a new project, not
resetting or deleting a workspace.

## Consequences

### Positive

- No view can continue to present the previous requirement's architecture after
  the user has confirmed invalidation.
- Reloading the page resumes unfinished architecture work.
- Concurrent sessions fail visibly instead of losing data.
- A second requirement is kept separate and is aggregated only through the
  project portfolio model established by ADR 0001.
- Persisted architecture decisions remain outside the destructive reset boundary.

### Trade-offs

- Draft payloads consume database space and require a PostgreSQL migration.
- Secondary panels are cleared on invalidation and may be recomputed; their
  rendered HTML is deliberately not persisted as authoritative data.
- Workspace pinning introduces one documented request header that API clients may
  also use when they need tab-stable context.
