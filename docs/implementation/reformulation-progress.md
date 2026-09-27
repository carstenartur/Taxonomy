# Reformulation completion progress

Plan: [reformulation-completion.md](reformulation-completion.md)

## 2026-09-27 — remote recovery checkpoint

- Base: `025197b3193f4a86815408a61ed6fb3368967259`.
- Remote branch created before implementation: `feature/reformulation-durable-completion`.
- Previous local-only completion work is not being counted as delivered or verified.
- Task 1 is complete and independently reviewed; Tasks 2–4 remain open. Existing
  merged functionality is retained.
- Current operation: Task 2a review fix round 1; see reformulation-task2a-review.md.
  The pre-correction code and report are remote at
  `cbe2aa28ef54c5ca0323309a68ea789b4e064d7c`, with 48 focused tests green. Independent
  review found a case-insensitive imported-identity bypass and invalid-baseline
  error-status mismatch; these remain open, with tests-first corrections next.
  Task 1 fix round 1 was accepted by a scoped
  independent re-review, with both findings addressed and no new breakage found.
  The reviewed code, tests and report are remote at
  `b2c9a4c24acf950100674ca594b53ff045a28451`.
- Verified Task 1 evidence: four initial meaningful RED regressions and two review
  correction RED regressions. Post-correction focused suite: 34/34 GREEN. Before
  review corrections, all 588 selected analysis-module tests passed. These are
  distinct runs; the broader suite has not been claimed as a post-correction run.
- Code, tests and evidence are remote; see reformulation-task1-report.md.
- Next: finish Task 2a review corrections,
  followed by Task 2b ancestry/prompt portability. Task 2a is a small checkpoint of
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
