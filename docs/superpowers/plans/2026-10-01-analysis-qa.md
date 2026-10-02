# Analysis scope and resource QA implementation plan

> Use executing-plans or subagent-driven-development. Preserve the design contract.

Spec: `docs/superpowers/specs/2026-10-01-analysis-qa-design.md`.
Branch: `refactor/analysis-scope-performance`.

## Tasks

1. Backend: domain scope, commands/controller validation, shared traversal, selected
   coverage, phase gating, recovery identity and exchange metadata.
2. UI: accessible root/mode selection, explicit-none validation, frozen result metadata,
   independent draft options, continuation and scoped Copilot handling.
3. Relations: per-search scalar cache, parse-once fresh responses, indexed progress.
4. Export: containment repair after selection and regression tests.
5. Verify the restored work, finish independent review, record QA findings and open a draft PR.

## Review focus

- Invalid selections fail before paid work.
- Canonical identity and old journals remain compatible.
- Architecture cannot bypass taxonomy-only scope.
- Invalid answers never become successful checkpoints.
- Scoped work never silently becomes a full run or a global completeness claim.
- Hierarchy filtering cannot leave dangling parents.
- Preserve legacy SSE partial-evidence order and avoid unused streaming context maps.

## Execution note

The initial implementation passed 712 UI contracts and all Java modules through portfolio.
Independent review found a stale-scope transition in manual/interactive replacement;
three red/green regressions fixed it. The broad Java run also exposed a legacy SSE order
regression and an invalid null-request recovery fixture; both were corrected, and the
45 targeted follow-up tests passed.

An execution-environment replacement discarded local task files and commits before
the application/quality stages completed. The implementation has been restored from
retained patches and secured in draft PR #1160. Fresh verification passed 712 UI
contracts and 3,784 Java tests through portfolio. The restored scope fixture required
the existing child-assessment response format; four focused tests confirmed the fix.

The application architecture ratchet exposed a new controller-to-catalogue-entity
dependency. The controller now consumes scalar root codes from TaxonomyService;
the architecture baseline is unchanged. All 22 controller tests and 38 application
follow-up tests (architecture ratchet, catalogue fingerprints, recovery exchange) pass.
The subsequent CI at a4a6b024 passed all 2,259 application unit tests, all six UI
shards and the database/auxiliary workflows, but exposed a first-search heap budget
failure. Browser profiling reproduced it on the unchanged base: hidden CodeMirror
startup loaded and parsed the full catalogue concurrently with search. The editor
now initializes when opened and preserves its document on return. The same review
also exposed a missing validation lifecycle transition; explicit page activation
now handles it independently of browser pagehide/pageshow. Heap measurement uses
precise values with unchanged sampling and limits, and CI retains raw heap reports
even when aggregate coverage is unavailable. Local Chromium/Selenium replays can
verify these scenarios; full Docker CI still has to run remotely.

Evidence and remaining findings are recorded in
`docs/testing/analysis-qa-2026-10-01.md` and the PR check list.

## Task 6: Preserve saved-analysis scope integrity

Address the two final PR-review findings together at the JSON exchange boundary.
Base for this follow-up is `550b80ebdc28ba676afa624a17c30971b8001fdc`.

- Preserve null/no frozen automatic scope through manual or interactive evidence
  export/import. `SavedAnalysis` must not invent FULL. Automatic request/result
  defaults remain all/FULL. Legacy files without scope remain readable; their
  absent metadata must not claim a completed automatic relation run.
- Validate every declared scope against real catalogue root codes on both export
  and import, including files without version-3 coverage. Use the shared exchange
  service/facade boundary rather than duplicate controller validation.
- When roots are explicitly selected, effective scores, raw scores and all
  coverage nodes must belong to those roots. Derive membership from real catalogue
  identities, not code-prefix guesses. Partial coverage remains valid; do not
  require every selected node/root to have been evaluated.
- Preserve existing v1/v2 compatibility and unknown-code warnings for unscoped
  legacy evidence. Keep raw/effective-score, coverage, state, and recovery contracts.
  Do not add new architecture dependencies or change a quality baseline.
- Reproduce the null-scope and mismatched/unknown-root bugs with failing behavioral
  tests, then fix them and run relevant domain/service/controller exchange tests.
  Include valid selected scopes, taxonomy-only mode, null scope, unknown roots,
  other-root raw/coverage evidence and a browser/import round trip as applicable.
- No provider calls, quota/prompt changes, commits, pushes or merges from the
  implementer. Root coordinates independent review and publication.

The scope-null rule above refines the request/result default contract for saved
evidence: an optional provenance field must preserve absence, independently of
the defaults used to start a new analysis. Manual/interactive replacement already
clears this field in the browser; exchange must retain that state.

CI follow-up on `8b0b6a94`: functional exchange tests and all six browser shards
passed, but the new membership inspection directly called a catalogue entity.
The unchanged architecture ratchet rejected growth of
`analysis.service -> catalog.model` from four class dependencies to five. Keep
membership inspection inside `TaxonomyService`, using scalar arguments at the
analysis boundary. Re-run the unchanged ratchet together with exchange/recovery
tests after this correction; preserve unknown-code errors and one identity
resolution per operation. Do not update the architecture baseline.

## Task 7: Keep completed analysis status across delayed input checks

Close the real UI regression exposed by the final Firefox shard at `550b80eb`.
The delayed legacy input handler in `taxonomy-browse.js` clears `statusArea`
unconditionally when text matches `lastAnalyzedText`, so an analysis that finishes
before that pending callback loses its freshly rendered completion feedback.

- Reproduce the actual registered input/debounce behavior with a failing behavioral
  test: edit text, complete an analysis before the pending input callback runs,
  then run the callback and assert the completion status remains visible. Cover
  success, partial/error feedback and genuine stale-text/revert behavior as
  appropriate. A getter-only or source-regex test is insufficient.
- Make status ownership explicit or otherwise consolidate the overlapping stale
  status handling with the existing session lifecycle. Delayed stale checks may
  remove stale-text feedback they own, but must preserve newer completion, warning
  or failure feedback. Preserve stale action controls, keyboard behavior, accessible
  announcements, imports, and the no-session fallback. Keep the change cohesive;
  no broad browser/controller rewrite.
- Do not weaken `.github/scripts/ui-acceptance.mjs`, add artificial input delays,
  relax timeouts, or retry away the failure. Verify the existing real browser
  analysis flow and relevant UI contracts once after the focused regression passes.
- Do not change analysis semantics, prompts, quotas, or saved-exchange scope rules.
  No provider calls, commits, pushes, merges, or additional subagents. Root owns
  documentation, independent review and publication.

Diagnosis evidence: CI job `110670526655` failed waiting for `#statusArea`; its
Firefox screenshot and application log show a successfully completed analysis and
no HTTP/console errors. Files are under
`/workspace/scratch/63d23a8e14d1/qa-firefox-550b80eb/mobile-admin-and-browser/`.
A VM replay of the actual installed handler records erased completion feedback in
`/workspace/scratch/63d23a8e14d1/qa-status-validation/repro-before.json`.
