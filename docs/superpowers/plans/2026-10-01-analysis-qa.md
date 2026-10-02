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
