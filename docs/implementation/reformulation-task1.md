# Task 1 — rejected wording cannot return as active text

Read this brief as the complete task scope. Main specification remains
`docs/superpowers/plans/2026-09-20-requirement-reformulation.md`.

## Requirements

- Rejecting an addition preserves its statement ID, text and provenance as evidence.
- Rejected text is omitted from active statements and parent summaries.
- Exact replay of rejected wording in a later generated statement or summary is
  rejected, not silently published, across node parsing, complete synthesis and
  reconciliation. Keep visible partial/failure/conflict behavior.
- Coverage validation must not count rejected statements as coverage of the source.
- A new ID does not make the same rejected wording acceptable.
- Do not claim protection against semantic paraphrases; document this limit.
- Do not alter originals, versions, adoption, scope, permissions, architecture,
  provider budgets, module dependencies, or unrelated code.
- Human edits and late-result fencing retain their existing behavior.

## Work and evidence

1. Inspect the relevant existing implementation and tests only.
2. Run a focused existing baseline test and record the result.
3. Write minimal regressions and observe meaningful RED before production edits.
4. Implement the smallest consistent correction and run focused GREEN.
5. Self-review, record commands, counts, limitations and changed files in
   `docs/implementation/reformulation-task1-report.md`.
6. Independent reviewer is dispatched by the controller, never by the implementer.

## Mandatory durability boundary

- Use apply_patch for edits; no Python/shell file rewriting.
- Work at `/workspace/scratch/38625e9262ff/Taxonomy-reformulation` only.
- Branch: `feature/reformulation-durable-completion`.
- Before long tests and at least every 10 minutes of editing, make a local
  checkpoint commit and message the controller. Pause new edits until it confirms
  remote persistence. Red tests/WIP are allowed on this unmerged feature branch.
- The controller publishes through the configured GitHub connector; plain git push
  currently lacks credentials. Never extract credentials or publish yourself.
- Do not spawn subagents. Do not modify a shared/main branch or merge.
- Stop on infrastructure failure after one safe confirmation; no polling loops.
- Existing merged packages are not to be reimplemented. No unfiltered full reactor
  after each small change: focused suite now; integrated broad gate by controller.

Report back with status, checkpoint SHA, test summary, concerns, and report path.
