# Authoritative CI performance

Taxonomy reduces CI wall-clock time by running independent verification lanes in
parallel while keeping one fail-closed required result. The optimisation changes
scheduling and evidence transport, not the set of product, quality or browser
checks.

## Measured baseline

The successful `main` run `33010052124` took 75 minutes and 38 seconds in the
`Maven verification` job. Its two dominant serial steps were:

- OpenTelemetry performance budget: 4 minutes and 5 seconds;
- canonical Maven verification: 70 minutes and 36 seconds.

Inside Maven, the browser matrix consumed approximately 45 minutes and 30
seconds. It executed 18 browser, accessibility, primary-workflow, role-state and
special-mode scenarios in eight application starts.

These numbers are the baseline for issue #906. They are not targets or a promise
for every hosted runner.

## Parallel evidence architecture

The `CI / CD` workflow now separates work that has no correctness dependency:

1. **Core reactor verification** runs the canonical Maven reactor with browser
   execution disabled. Unit, Spring, architecture, container, ONNX, coverage,
   dependency, supply-chain and other Maven-owned checks remain enabled.
2. **OpenTelemetry performance budget** runs the existing Maven-owned script and
   thresholds in a parallel job. The existing path-scope rule is unchanged for
   pull requests; `main` and tag runs still execute the measurement.
3. **UI contract verification** runs the non-browser Node contracts through a
   standalone Maven POM and prepares the pinned browser binary cache while the
   core reactor is still running.
4. **UI shards** consume one executable application JAR produced by the core
   reactor. Every shard verifies the JAR SHA-256 and source commit before starting
   the application.
5. **Maven verification** is the final required job. It succeeds only after every
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

## Build-once application contract

The core job stages exactly one executable `taxonomy-app-*.jar` and creates a
manifest containing:

- the full source commit;
- the source tree ID;
- the JAR filename;
- the JAR SHA-256 digest.

Each shard recalculates the digest before execution. The final evidence gate
recalculates it again and rejects unsafe filenames, a mismatched commit, a
mismatched tree or a mismatched digest.

## Fail-closed UI evidence gate

Each shard writes its normal scenario evidence plus `timings.json`. The final
Maven-owned gate verifies:

- every planned shard directory exists and no unexpected shard exists;
- every shard reports `passed`;
- every shard identifies the same source commit and JAR digest;
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

The change is accepted only if the full workflow remains green and the final
evidence inventory is unchanged. Wall-clock improvement is evaluated over
multiple comparable runs using median and p95 durations. Parallelism may increase
aggregate hosted-runner minutes; build-once artifacts, fewer application starts
per critical path and later removal of verified duplicate work are separate cost
optimisations.
