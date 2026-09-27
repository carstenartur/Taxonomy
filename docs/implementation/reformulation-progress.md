# Reformulation completion progress

Plan: [reformulation-completion.md](reformulation-completion.md)

## Intermediate merge confirmed — 2026-09-27 21:00 UTC

PR #1135 was squash-merged after all 29 current-head checks were terminal: 27
successes and the two explicitly allowed skipped CodeQL Source Analysis jobs.
All ten workflows were successful except the explicitly skipped CodeQL Source
Analysis workflow. Maven verification, Core, all UI/export/civilian/security/transport
jobs and PostgreSQL, Oracle and SQL Server succeeded. All five review threads were
resolved, there was no CHANGES_REQUESTED, and GitHub reported mergeable/clean.

Merged=true was read back with merge SHA
`13c0a345f4f8de3d56d52e1de54cb2723e20a8d2`. Archive
`archive/pr-1135-reviewed-a571e779` retains its authorized a571e779 commit; the
separate follow-up branch is retained. The completed merge watch is disabled.

GitHub retargeted PR #1136 to main. The squash commit has exactly the same tree as
its already-contained 43992e1d ancestor. A normal two-parent merge reconciles that
history without dropping follow-up code; full CI is now required on this base.
The remaining live-provider/human-quality evidence stays explicitly open.

## Follow-up implementation — 2026-09-27 20:56 UTC

PR #1136 (`feature/reformulation-civilian-acceptance`) now contains Task 4's complete
deterministic application/browser acceptance harness, independent document gate,
DE/EN documentation and separate five-case real-model comparison. Actual end-to-end
execution exposed and corrected duplicated inherited prompt context without raising
provider limits. Independent review and its corrections are accepted.

Final local evidence: 18/18 HTTP/race/restart/authored/playback tests, 10/10 document
tooling tests, and independent rendering of the current 25/26-page historical exports.
Browser execution and full current-head CI remain required. Real-model comparison
was explicitly NOT_RUN because no generative provider is configured; human quality
is NOT_REVIEWED. See the [Task 4 report](reformulation-task4-report.md) and
[final review](reformulation-task4-final-review.md).

The user requested PR #1135 merged when all gates succeed and the missing parts in
this second PR. Its updated head is `43992e1d3784641a3bff76433f403f1fe90df546`; all three
database jobs succeeded, but Core/Maven verification was still running at this
checkpoint. No merge or overall product-quality completion is claimed.

## Current status — 2026-09-27

Recovery is complete; the historical outage note below remains available for audit.
Source `1cdfac339540e1f6098382bb6b8973d7fb2b4ee6` is secured remotely and includes
current main `946e5bdb0900ee190ae0ed72da12e3896407e1c1` through a normal merge.
Tasks 1/2 remain accepted. Task 3's two independent review findings are resolved
and its scoped rereview is approved. The final focused Maven reactor completed
at 16:39:57 UTC: 60 tests, zero failures/errors/skips, BUILD SUCCESS.
Fresh DE/EN DOCX files from that run were independently rendered and all eight
pages inspected. See the review-fixes report and Task 3 rereview for exact evidence.

The whole-intermediate-branch review found one further current-question DOCX
omission. Its test-first correction is secured at `0da2fdba` and independently
accepted. Post-fix covering verification: 61/61, BUILD SUCCESS at 16:50:37 UTC;
all eight freshly generated DE/EN document pages passed visual inspection.
See `reformulation-final-question-fix.md` for hashes and exact test counts.
Applicable current-head CI remains the final merge gate.
The user authorized useful intermediate merges; the boundary is recorded
in `reformulation-intermediate-scope.md`. Task 4 remains required after this merge;
the complete original product specification is not yet accepted.

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
- Next: finish Task3, then Task4 and integrated verification. Tasks1/2 are accepted.
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
