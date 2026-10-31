# Authoritative CI performance

Taxonomy reduces CI wall-clock time by running independent verification lanes in
parallel while keeping one fail-closed required result. Full-scope optimisation preserves the product, quality and browser inventory.
Reduced documentation scope is explicit and carries its own evidence contract.

## Current measured baseline and implementation status

The latest successful measured baseline is
[`41216530274`](https://github.com/carstenartur/Taxonomy/actions/runs/41216530274)
on source `e1e8d19e8d9be57ae98f3b448cd01233a6515842`:

| Measurement | Baseline |
| --- | ---: |
| Complete workflow | 81m 05s |
| Core job | 75m 44s |
| `taxonomy-app` module | 57m 04s |
| Coverage aggregation job | 3m 47s |

The 31 October implementation addresses five measured costs:

1. Move the exact 10 audited runtime classes and 45 domain-integration classes
   into separate Maven-owned groups. Existing recovery, browser and scenario
   owners remain; unassigned tests remain core. Runtime selection retains
   PostgreSQL, pinned ONNX provisioning and `runOnnxTests=true`.
2. Replay verified coverage inputs at `process-test-classes`, with no javac or
   ordinary test execution, while running a fresh `ReactorCoveragePolicyIT`.
   The full module inventory, original probes and source/digest checks remain.
3. Remove unused embedded HTTP servers from the encoding and dispatch-claim
   service fixtures while retaining the complete Spring application, production
   security, real persistence and outbound HTTP. Context cache size remains 1;
   browser JVM isolation remains unchanged.
4. Classify ready PRs conservatively for packaged-documentation verification and
   avoid expensive main-workflow and ancillary PR jobs while a PR is draft. The required gate
   rejects draft heads; `ready_for_review` resumes verification.
5. Separate dependency, output and image-layer caches; reduce large archive
   writes; and package the early UI application without test compilation or
   duplicate aggregate SBOM work. Its own fresh runtime SBOM stays embedded.

These changes have focused local regression evidence, but a new complete
exact-head CI run has **not yet established an overall improvement**. The two
changed Spring suites measured 63.023 s before and 56.948 s after (6.075 s,
9.6% less) in one paired local run. That observation applies only to those two
suites. Hosted-runner contention and cache state can change results; no cold/warm
Docker timing improvement has been measured locally.

See [verification scope and evidence](MAVEN_VERIFICATION.md) and
[cache authority and policy](MAVEN_INCREMENTAL_BUILDS.md). The earlier 28m 43s
result below is historical and is not the current runtime or a performance promise.

## Historical validated parallel result

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

This historical comparison proved that topology and its wall-clock reduction
at that source revision.
Median and p95 values still require multiple comparable green runs; hosted-runner
load and cache state can vary.

## Historical sequential baseline

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

For full verification, the `CI / CD` workflow separates work that has no
correctness dependency:

1. **Commit-bound UI application** packages the executable application early and
   publishes a manifest containing the exact source commit, source tree, filename
   and SHA-256 digest.
2. **Core and application-test groups** partition the existing Maven-owned tests
   through the explicit catalogue. Separate runtime and domain-integration groups
   join recovery, browser and scenario groups. A subsequent coverage job consumes
   their verified outputs and runs the full-reactor coverage policy freshly.
3. **OpenTelemetry performance budget** runs the existing Maven-owned script and
   thresholds in a parallel job. The existing path-scope rule is unchanged for
   pull requests; `main` and tag runs still execute the measurement.
4. **UI contract verification** runs the non-browser Node contracts through a
   standalone Maven POM while the application and core jobs are running. Browser
   binaries are restored by browser consumers, not this browser-free job.
5. **UI shards** start as soon as the application artifact and UI contracts are
   ready; they do not wait for the core reactor. Every shard verifies the JAR
   SHA-256, source commit and source tree before starting the application.
6. **Maven verification** is the final required job. It succeeds only after every
   lane succeeds and after the UI evidence gate proves complete, exact-once,
   commit-bound and JAR-digest-bound coverage.

The final job retains the established check name so branch rules, release gates
and the delivery workflow continue to consume one stable result.

## Authoritative UI shard plan

`.github/ui-shards.json` owns CI browser shard assignment;
`.mvn/verification-suites.json` separately owns application Java test groups.
The browser plan covers all 19 current scenarios exactly once. `.github/scripts/ui-shard-plan.test.mjs` rejects:

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
quality report. Missing, unexpectedly skipped, cancelled, corrupted or mismatched evidence
therefore makes full-scope `Maven verification` fail. Documentation scope has its
own explicit expected-lane outcomes and original evidence; it cannot satisfy a
full-scope result.

## Artifacts and retention

Intermediate JAR, shard and core-report artifacts use short retention because
they exist only to connect jobs in one workflow. The final `quality-reports` and
`ui-verification` artifacts retain the existing 90-day policy and remain bound to
the workflow commit.

## Local reproduction

The traditional complete local command remains valid and sequential:

```bash
./mvnw -B verify -Pci -DrunOnnxTests=true
```

The new lanes can also be reproduced independently:

```bash
# Package the commit-bound UI application
bash .github/scripts/package-ui-application.sh
bash .github/scripts/stage-ui-application.sh

# Core group without browser execution
./mvnw -B verify -Pci,ci-app-test-shards-core -DrunOnnxTests=true -Dtaxonomy.ui.skip=true

# One measured application group (runtime or domain-integration)
./mvnw -B -pl taxonomy-tooling,taxonomy-app -am verify \
  -Pci,ci-app-test-shard-runtime -DrunOnnxTests=true -Dtaxonomy.ui.skip=true

# Coverage join: requires the original verified core and every shard artifact
./mvnw -B process-test-classes -Pci,ci-app-test-shards-coverage \
  -DrunOnnxTests=true -Dtaxonomy.ui.skip=true

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
hosted-runner minutes; the latest measured critical path is now the 75m 44s core
job and its
57m 04s application module. Compare a fresh complete run of the current changes
against run `41216530274`, keeping runner/cache conditions and verification scope
visible. JUnit suite seconds and baseline-subset timing drift are diagnostic
measurements, not elapsed workflow time.

The early application module is always packaged freshly; its upstream modules
retain output-cache reuse. A local warm-cache probe found the app entry but the
pinned extension rejected its root-level SBOM attachment and rebuilt it. The
profile now avoids that futile app restore and cache write while retaining the
complete plugin/feature distribution and a fresh runtime SBOM.
