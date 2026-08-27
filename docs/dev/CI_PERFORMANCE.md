# Authoritative CI performance

Taxonomy reduces CI wall-clock time by running independent verification lanes in
parallel while keeping one fail-closed required result. The optimisation changes
scheduling and evidence transport, not the set of product, quality or browser
checks.

## Validated result

The first complete green run of the parallel architecture was pull-request run
[`33057152744`](https://github.com/carstenartur/Taxonomy/actions/runs/33057152744)
on commit `06fa842a27935ef886fabce5ab3f9c3085ec7ce3`.

| Measurement | Sequential baseline | Parallel run | Change |
| --- | ---: | ---: | ---: |
| Complete `CI / CD` workflow | 75m 42s | 28m 43s | **46m 59s faster** |
| Relative wall-clock duration | 100% | 37.9% | **62.1% shorter** |
| Effective workflow speed | 1.00x | **2.64x** | — |
| Browser/UI critical path | about 45m 30s | 11m 40s | **about 74.4% shorter** |

The parallel run remained fully green. Its authoritative paths measured:

- commit-bound UI application package and upload: **1m 48s**;
- all six UI shards from workflow start through the last completed shard:
  **11m 40s**;
- core reactor verification: **27m 29s**;
- canonical Maven verification within the core lane: **24m 05s**;
- final fail-closed `Maven verification` gate: **56s**.

The core and UI lanes overlapped. Consequently, the complete workflow followed
the slower core path plus the short final evidence gate instead of adding the UI
matrix after the core reactor.

The same head also passed Database Compatibility, CodeQL Source Analysis,
Security Scan, Document Template Report E2E and Kubernetes Constrained Smoke.
The final gate accepted the complete 18-scenario inventory, application commit,
source tree, JAR digest and consolidated quality and coverage evidence.

This run proves the implemented topology and the material wall-clock reduction.
Median and p95 values still require multiple comparable green runs; hosted-runner
load and cache state can vary.

## Measured baseline

The successful `main` run
[`33010052124`](https://github.com/carstenartur/Taxonomy/actions/runs/33010052124)
took 75 minutes and 42 seconds overall and 75 minutes and 38 seconds in the
required `Maven verification` job. Its two dominant serial steps were:

- OpenTelemetry performance budget: 4 minutes and 5 seconds;
- canonical Maven verification: 70 minutes and 36 seconds.

Inside Maven, the browser matrix consumed approximately 45 minutes and 30
seconds. It executed 18 browser, accessibility, primary-workflow, role-state and
special-mode scenarios in eight application starts.

These numbers are the baseline for issue #906. They are not targets or a promise
for every hosted runner.

## Parallel evidence architecture

The `CI / CD` workflow separates work that has no correctness dependency:

1. **Commit-bound UI application** packages the executable application early and
   publishes a manifest containing the exact source commit, source tree, filename
   and SHA-256 digest.
2. **Core reactor verification** runs the canonical Maven reactor with browser
   execution disabled. Unit, Spring, architecture, container, ONNX, coverage,
   dependency, supply-chain and other Maven-owned checks remain enabled.
3. **OpenTelemetry performance budget** runs the existing Maven-owned script and
   thresholds in a parallel job. The existing path-scope rule is unchanged for
   pull requests; `main` and tag runs still execute the measurement.
4. **UI contract verification** runs the non-browser Node contracts through a
   standalone Maven POM and prepares the pinned browser binary cache while the
   application and core jobs are running.
5. **UI shards** start as soon as the application artifact and UI contracts are
   ready; they do not wait for the core reactor. Every shard verifies the JAR
   SHA-256, source commit and source tree before starting the application.
6. **Maven verification** is the final required job. It succeeds only after every
   lane succeeds and after the UI evidence gate proves complete, exact-once,
   commit-bound and JAR-digest-bound coverage.

The final job retains the established check name so branch rules, release gates
and the delivery workflow continue to consume one stable result.

## Authoritative UI shard plan

`.github/ui-shards.json` is the only CI shard assignment. It covers all 18
scenarios exactly once. `.github/scripts/ui-shard-plan.test.mjs` rejects:

- unknown scenario IDs;
- duplicate shard IDs;
- scenarios assigned to more than one shard;
- scenarios missing from the plan.

The workflow exports only shard IDs. Scenario selection remains repository-owned
and is executed through the Maven profile in `.github/ui-verification-pom.xml`.
GitHub Actions does not select Java tests or call browser scripts directly.

The six current shards were balanced from measured scenario timings. State
isolation remains unchanged: scenarios within a shard are still grouped by
`ui-suite-plan.mjs`, and groups that require separate application state keep
separate application starts.

## Commit-bound application contract

The independent application job stages one executable `taxonomy-app-*.jar` for
all UI shards and creates a manifest containing:

- the full source commit;
- the source tree ID;
- the JAR filename;
- the JAR SHA-256 digest.

Each shard recalculates the digest before execution. The final evidence gate
recalculates it again and rejects unsafe filenames, a mismatched commit, a
mismatched tree or a mismatched digest. The core lane remains independently
authoritative for its Maven, Docker, coverage and delivery evidence.

## Fail-closed UI evidence gate

Each shard writes its normal scenario evidence plus `timings.json`. The final
Maven-owned gate verifies:

- every authoritative lane completed successfully;
- every planned shard directory exists and no unexpected shard exists;
- every shard reports `passed`;
- every shard identifies the same source commit, source tree and JAR digest;
- every expected scenario reports `passed` exactly once;
- no scenario is missing, duplicated or unexpected;
- the per-shard scenario list equals the repository plan.

Only then is a consolidated `summary.json` produced and included in the final
quality report. Missing, skipped, cancelled, corrupted or mismatched evidence
therefore makes `Maven verification` fail.

## Artifacts and retention

Intermediate JAR, shard and core-report artifacts use short retention because
they exist only to connect jobs in one workflow. The final `quality-reports` and
`ui-verification` artifacts retain the existing 90-day policy and remain bound to
the workflow commit.

## Local reproduction

The traditional complete local command remains valid and sequential:

```bash
./mvnw -B verify -Pci
```

The new lanes can also be reproduced independently:

```bash
# Package the commit-bound UI application
./mvnw -B -ntp -DskipTests package
bash .github/scripts/stage-ui-application.sh

# Core reactor without browser execution
./mvnw -B verify -Pci -DrunOnnxTests=true -Dtaxonomy.ui.skip=true

# UI and transport contracts
./mvnw -B -f .github/ui-verification-pom.xml verify -Pcontracts

# One repository-defined UI shard; first stage the executable JAR in
# taxonomy-app/target and provide its exact source and digest.
TAXONOMY_UI_SHARD=architect-and-a11y-desktop \
TAXONOMY_UI_SOURCE_SHA=<40-character-commit> \
TAXONOMY_UI_ARTIFACT_SHA256=<64-character-sha256> \
TAXONOMY_UI_OUTPUT_ROOT=target/ui-verification/architect-and-a11y-desktop \
  ./mvnw -B -f .github/ui-verification-pom.xml verify -Pshard
```

The OpenTelemetry lane remains reproducible with:

```bash
TAXONOMY_OBSERVABILITY_PERFORMANCE_ENFORCE=true \
  bash .github/scripts/run-observability-performance.sh
```

## Performance acceptance

The first complete green comparison satisfies issue #906's material-speedup
criterion without reducing verification inventory. Future comparable runs should
be tracked for median and p95 durations. Parallelism may increase aggregate
hosted-runner minutes; further optimisation should target the current critical
path: the 27m 29s core lane, especially its 24m 05s canonical Maven verification.
