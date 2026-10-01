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

An execution-environment replacement then discarded local task files and commits before
the application/quality stages completed. The implementation is being restored from
retained patches; a new verification pass and remote checkpoint are required.
Authoritative completion evidence is recorded in `docs/testing/analysis-qa-2026-10-01.md`.
