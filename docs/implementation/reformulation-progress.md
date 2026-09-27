# Reformulation completion progress

Plan: [reformulation-completion.md](reformulation-completion.md)

## 2026-09-27 — remote recovery checkpoint

- Base: `025197b3193f4a86815408a61ed6fb3368967259`.
- Remote branch created before implementation: `feature/reformulation-durable-completion`.
- Previous local-only completion work is not being counted as delivered or verified.
- Tasks 1, 2a and 2b are complete and independently reviewed. Task 3
  (DOCX) and Task 4 (civilian acceptance/quality) remain open. Existing merged
  functionality is retained.
- Current operation: Task 3 from docs/implementation/reformulation-task3.md.
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

## Task 2b — durable in-progress checkpoints

- Task review base: `8e1a1103276600a3127759488fe8bd6c04b463ae`.
- Strict import, exact local/imported adopted-source freezing and nonrecursive v2
  ancestry are implemented. Covering Spring runs before the prompt/provenance
  additions: round-trip 10/10 plus positive-review guard 14/14, no failures/errors/skips.
- Current remote implementation checkpoint:
  `ef9db85f85b0fdc0ae3a59e20fcdbaa8bb98af39`. Its complete tree and local alignment
  were verified. It adds inherited decision prompt context, protected ADOPTED_SOURCE
  provenance and cache encoding v3, including rejection of exact adopted text
  mislabeled ORIGINAL. Meaningful analysis RED evidence is in the task report;
  the corresponding GREEN and independent task review remain pending.
- The test Maven executable in another scratch checkout disappeared. No source or
  test work was lost. The repository wrapper restored the same Maven 3.9.16;
  the verification helper now uses this task's toolchain directory. This incidental
  runtime error is not counted as a feature RED.
- Next exact commands and newest checkpoint details live in
  `reformulation-task2b-report.md`; do not restart completed Tasks 1 or 2a.
- Task 2b: fix round 1/5 starting after independent review of
  `03e4208bbd8086c1649240ccb37e2cc2b0d6b76b` (0 addressed, 3 open: inherited
  rejection enforcement, per-call inherited context scope, genuine adopted-source
  reanalysis/checkpoint verification). See `reformulation-task2b-review.md`.
- Fix round 1 is remotely secured at
  `606936c3616697ac8c96a956301e1fa19feb2103`; covering tests: 41/41. Scoped review
  accepted inherited rejection enforcement and genuine post-adoption reanalysis.
  Fix round 2/5 is limited to the remaining reachable `scope=local` leak from a
  historical B-only edited statement into an A-node prompt. Tasks 3/4 remain open.
- Task 2b accepted after scoped fix-round-2 review at remote
  `b885b24724da54b8d32186b953997f4380745eab`, tree
  `8e33bb326bbedad7ff1d0c69fbae846c64914916`. Remaining finding addressed, no new
  Critical/Important breakage. Second correction: meaningful RED 1/1 failure,
  then focused GREEN 16/16; the earlier 41/41 covering run predates that correction.
  See task report/review for exact selectors, real-reanalysis limitations and
  distinct runs. Do not repeat Tasks 1/2. Next: frozen DOCX, then civilian/quality.

## Local verification environment

Java 21.0.12.1+1 (Temurin), Maven 3.9.16, project target release 21. Build cache is
disabled for evidence runs. Maven uses the configured execution HTTPS proxy through
a freshly generated temporary settings file per command (proxy ports change between
commands). Mockito 5.23.0 is preloaded as a Java agent because dynamic self-attach is
not permitted in this environment. No product build/dependency changes are made for
these environment requirements. The report records the exact focused selectors;
normal CI uses the repository's Maven wrapper and configured gates.
