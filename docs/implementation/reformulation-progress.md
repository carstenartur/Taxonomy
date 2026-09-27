# Reformulation completion progress

Plan: [reformulation-completion.md](reformulation-completion.md)

## 2026-09-27 — remote recovery checkpoint

- Base: `025197b3193f4a86815408a61ed6fb3368967259`.
- Remote branch created before implementation: `feature/reformulation-durable-completion`.
- Previous local-only completion work is not being counted as delivered or verified.
- Tasks 1 and 2a are complete and independently reviewed. Task 2b (lineage), Task 3
  (DOCX) and Task 4 (civilian acceptance/quality) remain open. Existing merged
  functionality is retained.
- Current operation: start Task 2b from docs/implementation/reformulation-task2b.md.
  Task 2a corrections and report are remote at
  `426287fdab65ce0c1c76aff4f74eb111f5a8c7a2`. Scoped independent re-review accepted
  both fixes. Post-fix focused suite: 23/23; earlier broader suite: 48/48, explicitly
  before correction. See reformulation-task2a-review.md for the corrected reachable
  case-folded import premise and exact RED/GREEN evidence.
  Task 1 fix round 1 was accepted by a scoped
  independent re-review, with both findings addressed and no new breakage found.
  The reviewed code, tests and report are remote at
  `b2c9a4c24acf950100674ca594b53ff045a28451`.
- Verified Task 1 evidence: four initial meaningful RED regressions and two review
  correction RED regressions. Post-correction focused suite: 34/34 GREEN. Before
  review corrections, all 588 selected analysis-module tests passed. These are
  distinct runs; the broader suite has not been claimed as a post-correction run.
- Code, tests and evidence are remote; see reformulation-task1-report.md.
- Next: Task 2b ancestry/prompt portability, then Tasks 3 and 4. Task 2a is a checkpoint of
  completion Task 2, not a new independent feature.
- No provider credentials or Docker availability assumed. External gates will be reported explicitly.

## Local verification environment

Java 21.0.12.1+1 (Temurin), Maven 3.9.16, project target release 21. Build cache is
disabled for evidence runs. Maven uses the configured execution HTTPS proxy through
a freshly generated temporary settings file per command (proxy ports change between
commands). Mockito 5.23.0 is preloaded as a Java agent because dynamic self-attach is
not permitted in this environment. No product build/dependency changes are made for
these environment requirements. The report records the exact focused selectors;
normal CI uses the repository's Maven wrapper and configured gates.
