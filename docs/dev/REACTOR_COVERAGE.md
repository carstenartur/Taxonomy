# Reactor-wide and release-critical test coverage

The authoritative coverage evidence for Taxonomy is produced by the final coverage module, `taxonomy-coverage`, using JaCoCo `report-aggregate`. Maven/JUnit evaluates every coverage policy from that one XML file after all shipped modules have completed.

## Included production modules

The report must contain all shipped modules as separate JaCoCo groups:

1. `taxonomy-domain`
2. `taxonomy-dsl`
3. `taxonomy-export`
4. `taxonomy-extension-api`
5. `taxonomy-app`

The gate normalizes Maven display names and artifact IDs, but it still fails when any required module is missing. This prevents a highly covered application module from hiding an uninstrumented or untested library module.

## Aggregate multi-counter ratchet

`.github/coverage-policy.json` is the single versioned policy for aggregate coverage. It requires and publishes all of these JaCoCo counters:

- instructions;
- lines;
- branches;
- methods;
- classes.

Every required counter must exist and have a measurable total both at aggregate level and for every module group. The build fails closed when a counter is missing, empty or below its configured aggregate minimum.

The branch threshold is an explicit non-regression ratchet based on verified reactor evidence. It must not be removed or silently replaced by instruction-only coverage. Thresholds should move upward as tests improve.

## Release-critical package and changed-source ratchets

`.github/critical-coverage-policy.json` protects packages where untested negative branches can affect security, provenance, imports, Git/versioning, repository/workspace routing or portfolio decisions.

For each configured package the policy records separate line and branch minimums. The values are floors measured from a successful authoritative aggregate report; future changes may retain or raise them but may not silently reduce them.

In a complete-history pull-request checkout, the same policy also identifies changed production Java files under configured critical source prefixes. Each selected source must:

- appear in the authoritative JaCoCo XML;
- meet the changed-source line minimum;
- meet the changed-source branch minimum when the source contains measurable branches.

JaCoCo omits the `BRANCH` counter for a source file with no branches. That case is reported as not applicable rather than as zero coverage. Package-level branch counters remain mandatory.

Canonical CI checks out complete history and is the authority for changed-source coverage. Shallow database or security jobs still enforce aggregate and package coverage where the complete reactor report is available, but they do not pretend to own a Git diff they cannot establish.

## Temporary exceptions

A temporary exception is valid only when the versioned policy contains all of:

```text
scope
rationale
owner
expiresOn
```

The scope must identify one configured package or one exact repository-relative source path. Expired exceptions fail policy loading. Active exceptions are printed in the evidence report as `APPLIED` or `NOT NEEDED`, so an exception cannot remain invisible after the underlying gap disappears.

The preferred response to a failing budget is additional meaningful positive and negative tests. Exceptions are a bounded transition mechanism, not a permanent allow-list.

## Single source of truth

The following inputs and output form one coverage contract:

```text
taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml
.github/coverage-policy.json
.github/critical-coverage-policy.json
target/coverage-gate.txt
```

They drive:

- the aggregate CI coverage gate;
- release-critical package budgets;
- changed critical source coverage;
- published and archived coverage evidence;
- release coverage claims.

Module-local reports may still exist for diagnosis, but they are not added together and are not authoritative.

## Local verification

The complete authoritative verification is:

```bash
./mvnw -B verify -Pci
```

The deterministic policy regression tests can be run after Maven has built the required modules with:

```bash
./mvnw -B -pl taxonomy-build -am test \
  -Dtest=ReactorCoveragePolicyTest,CriticalCoveragePolicyTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The post-reactor gate itself is a Failsafe test because it consumes the aggregate report created by earlier reactor modules.

## Adding a critical area

A new release-critical package or source prefix is incomplete until all of the following are addressed:

- the package/prefix is added to `.github/critical-coverage-policy.json`;
- the initial line and branch floors are derived from a successful authoritative build;
- meaningful denial, invalid-input, rollback, concurrency or dependency-failure tests exist for the risk being protected;
- any temporary exception has an owner, rationale and expiry;
- this document remains accurate.

## Adding or removing a module

A shipped module change is incomplete until all of the following are updated:

- root reactor `<modules>` list;
- direct dependencies in `taxonomy-coverage/pom.xml`;
- `expectedGroups` in `.github/coverage-policy.json`;
- critical coverage paths where applicable;
- this document.

CI deliberately fails when those views drift apart.
