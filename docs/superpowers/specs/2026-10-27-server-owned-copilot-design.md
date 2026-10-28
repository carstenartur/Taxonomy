# Server-owned Copilot completion

## Intent and approval

The user requested implementation after reviewing the incomplete JMS migration:
the ordinary analysis page must not orchestrate or persist the Copilot tail in a
browser tab. Multiple authenticated users must be able to observe their own
server-owned work and recover its results. The reported failure was
`Invalid analysis draft: status=400, type=ANALYSIS_DRAFT_VALIDATION` while saving
Copilot follow-up results after the main analysis. The screenshot did not identify
the rejected field. Exceeding the separate 2,000,000-character working-draft limit
is a reproducible failure mode, not a confirmed cause of that individual request.

## Approach

Extend the existing durable `FINALIZING` lifecycle. Its completion consumer already
redelivers after failures and its store fences cancellation and source authority.
Compute deterministic Copilot results before the final result is stored. Reuse the
same result-scoped computation in local analysis and portfolio snapshots.

Alternatives considered: increasing the draft limit leaves browser ownership in
place; adding another job/queue framework duplicates existing durable admission
and recovery. Neither addresses this change as directly as the existing finalizer.

## Result contract

`AnalysisResult.copilot` is an `AnalysisCopilotResult` with `schemaVersion`, `state`,
`gaps`, `patterns`, `recommendation`, and `notes`. States are `COMPLETED`, `PARTIAL`,
`SKIPPED_PARTIAL`, and `SKIPPED_SCOPE`. A missing value identifies historical data;
clients must not start new provider work or pretend that it contains a completed
Copilot result. The existing three report DTOs remain the rendering contract.

The shared `AnalysisCopilotService.analyze(AnalysisResult, String)` reads only
retained analysis/catalogue/relation evidence. It does not query a newer global
catalogue, use a global embedding index, or make provider requests. Confirmed
patterns require retained verified relationship evidence. Missing or unsearched
evidence remains unknown; an exhausted pruning policy is not proof of completeness.
Recommendations may use retained scored nodes and clearly identified hypotheses.

## Lifecycle and ownership

Artemis finalization stores the architecture and Copilot payload together before
terminal success. Database failures retain the existing retry semantics. Redelivery
of an already completed operation is harmless. Cancellation/source checks remain
at the store boundary. Local analysis computes the same reports before its existing
continuation persistence. Portfolio snapshots reuse this canonical payload.

The existing optional Artemis deployment remains explicit. This change must not
silently require a broker for local installations or claim deployment validation.

## Browser drafts and compatibility

For evidence that can be reconstructed from an owned durable run, the working
draft stores `analysisReference` (`operationId`, `continuationId`, `workspaceId`)
plus editable input, view/options and small metadata. It does not upload copies of
scores, reasons, coverage, architecture, relations or Copilot results. Presence of
an operation ID alone is insufficient: manual/imported changes must retain their
full working-draft representation unless they are independently persisted.

Reference restoration uses existing authorized server result/continuation reads.
It preserves edited draft text and selected view, so stale-result detection still
works. Restore remains guarded against workspace changes, reset, newer loads and
typing. A missing or inaccessible reference is an explicit recoverable error, not
an empty authoritative draft. Legacy full drafts and empty tombstones remain valid.

The managed Copilot button renders/observes server results; it never chains the
three calculation HTTP endpoints or depends on a successful browser checkpoint.
Existing standalone manual report actions remain available. Historical results
without a canonical Copilot payload are identified honestly and must not trigger
a silent repeat of the LLM analysis.

If a legacy full draft differs from a newer saved server result, status refresh
keeps its local evidence and explicitly offers **Load saved server result**. The
choice performs an authorized read, never a new analysis. An edit or workspace
change during that read prevents replacement.

Portable score JSON retains the Copilot envelope for display and export but never
establishes an authoritative execution reference. For an unedited managed result,
report export sends the owned durable reference and renders its saved Copilot,
reasons, language and provenance. Both supplied identifiers must agree. Historical
absence returns an explicit unavailable result, rather than a completed empty
report or a fresh global computation. Legacy/manual report requests keep their
existing behavior.

## Acceptance

- A durable main result includes Copilot reports before it becomes terminal.
- Repeated completion, temporary database failure and cancellation preserve the
  established ownership and retry contracts.
- A browser receiving that result sends no Copilot calculation POSTs.
- A canonical result larger than the working-draft limit saves as a bounded
  reference and restores through authorized reads without a new analysis.
- Edited input, manually changed evidence and historical drafts remain recoverable.
- Late responses cannot overwrite another workspace or reset draft.
- New tests use deterministic fixtures; Maven owns functional verification.

## Constraints

Java 25 and the existing Maven Wrapper; one application; no new workflow, broker
framework, external LLM calls, invented live catalogue entries, or weakened gates.
Source baseline: `908b5aee022cb93377d01fa01fb534842ff95aad`, including unresolved
root relevance clarification. Continuation completion must reconcile the Copilot
state after that clarification can downgrade a result to partial.

## Reviewed dependency baseline changes

The existing package dependency ratchet remains enforced. The following exact
class-count changes represent the shared report computation and its completion
boundaries; they do not add a context or relax a dependency rule.

| From package | To package | Before → after | Reason |
|---|---|---:|---|
| `analysis.cluster` | `architecture.service` | 2 → 4 | Finalizer and terminal-store fallback derive reports. |
| `analysis.recovery` | `architecture.service` | 0 → 1 | Canonical continuation completion reconciles reports. |
| `analysis.usecase` | `architecture.service` | 2 → 3 | Local completion uses the same computation. |
| `composition.analysis.artemis` | `architecture.service` | 2 → 3 | Application wiring supplies the finalizer computation. |
| `composition.report` | `analysis.cluster` | 0 → 2 | Read authenticated retained operation input and result for report export. |
| `composition.report` | `analysis.recovery` | 0 → 2 | Read authenticated continuation snapshots for report export. |
| `architecture.service` | `relations.service` | 2 → 3 | Pure report logic uses existing static compatibility metadata. |
| `portfolio.service` | `architecture.service` | 3 → 4 | Snapshot computation reuses retained evidence; legacy constructor remains compatible. |
