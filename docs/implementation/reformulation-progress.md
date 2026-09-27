# Reformulation completion progress

Plan: [reformulation-completion.md](reformulation-completion.md)

## 2026-09-27 — remote recovery checkpoint

- Base: `025197b3193f4a86815408a61ed6fb3368967259`.
- Remote branch created before implementation: `feature/reformulation-durable-completion`.
- Previous local-only completion work is not being counted as delivered or verified.
- Tasks 1–4 remain open; existing merged functionality is retained.
- Current operation: Task 1 fix round 1, two review findings open; see
  reformulation-task1-review.md. The pre-fix reviewed code is remote at
  `47f72a9ce973045b49b83d864ce3fa9c2bc37bc4`.
- Verified Task 1 evidence: four meaningful RED regressions; focused 32/32 GREEN;
  all 588 selected analysis-module tests GREEN, zero failures/errors/skips.
- Code, tests and evidence are remote; see reformulation-task1-report.md.
- Next: resolve any Task 1 review findings, then Task 2a baseline/review guard,
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
