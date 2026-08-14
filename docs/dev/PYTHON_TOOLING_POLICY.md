# No-Python build tooling policy

Taxonomy uses Maven as the canonical verification entry point and JUnit/Failsafe
as the authority for executable tests and repository pass/fail policies. The
repository owner decided on 14 August 2026 that the final Taxonomy source and
release toolchain must contain **no Python files and no Python invocation**.

This document records the migration boundary until the remaining files are
removed. It is not an allow-list for permanent exceptions.

## Permanent rules

1. A deterministic repository policy belongs in JUnit, Maven Enforcer, another
   Maven-native plugin, or the frontend toolchain that owns the source format.
2. Structured artifact generation belongs in Java/Maven-native tooling when a
   real program is required.
3. Shell and GitHub Actions may orchestrate external command-line tools, Git,
   Helm, containers and publication, but must not become a second test or policy
   implementation.
4. No `.py` file, `python`, `python3`, `pytest`, `pip` or `unittest` invocation
   may remain in the final release candidate.
5. A productive adapter is removed only after its positive, negative, failure,
   retry and output contracts are preserved at the real boundary.
6. New Python is prohibited while the migration is in progress.

## Policies already owned by Maven/JUnit

| Concern | Integration |
|---|---|
| Workflow test authority | #674 |
| Documentation and image links | #675 |
| Reactor-wide JaCoCo ratchet | #680 |
| Hibernate Search/ORM/Lucene alignment | #681 |
| Action and production-image pinning | #683 |
| Packaged CycloneDX dependency hygiene | #685 |
| Frontend API transport boundaries | #687 |
| Release request ancestry and revision | #716 |
| Exact release-gate behavior | #737 |

## Java release-core migration

The `taxonomy-tooling` reactor module is a dependency-free executable Java JAR.
It owns the release boundary before and after immutable checkout changes and is
preserved in `$RUNNER_TEMP` by the release workflows.

The first #673 migration group replaces and deletes:

- `resolve-release-parameters.py` and its Python test suite;
- `check-release-plan.py` and its Python test suite;
- `check-version-state.py`;
- `update-release-metadata.py`.

The Java implementation and JUnit contracts cover:

- workflow-dispatch derivation and freely selected major/minor/patch advances;
- reviewed push requests, exact-first-parent anchoring, one-file request commits
  and exact `request_revision` increments;
- staged-release tag ancestry and repaired history;
- declared Maven reactor traversal, inherited properties, duplicate coordinates,
  internal versus external snapshots and nested modules;
- ordinary and linked-worktree cleanliness;
- Maven, citation, CodeMeta, Zenodo and Helm version-state agreement;
- release, development and advanced lifecycle states;
- stable GitHub output ordering and user-facing failure diagnostics;
- deterministic nested JSON parsing and writing with Unicode preservation;
- coherent CFF, citation, Zenodo, CodeMeta and Helm metadata transitions;
- release-date insertion, snapshot date removal, ORCID restoration and
  validation-before-write behavior;
- source-level proof that both productive metadata transitions use the Java JAR
  and the removed Python helper cannot return.

Release, protected-main and manual development-version workflows call the same
Java JAR rather than reconstructing these rules in YAML or shell.

## Remaining migration inventory

The following programs are still present only until their Java/Maven-native or
bounded shell replacements have equivalent JUnit contracts:

### Artifact generation

- `generate-vex.py`
- `generate-quality-site.py`

### Release, delivery and external-format verification

- `check-release-delivery-contract.py`
- `check-release-image-gate.py`
- `check-delivery-hardening.py`
- `verify-deployment.py`
- `verify-quality-publication.py`
- `check-codeql-sarif.py`
- `check-observability-performance-scope.py`

### Duplicate Python test programs to remove with their productive boundary

- `test-generate-quality-site.py`
- `test-verify-deployment.py`
- `test-verify-quality-publication.py`

No remaining item is an accepted permanent exception.

## Migration order

1. Release parameter, plan, version-state and metadata core in
   `taxonomy-tooling`.
2. Release/delivery, CodeQL and observability policy checks under JUnit authority.
3. Quality-site and VEX generation in Java/Maven-native code.
4. Deployment and quality-publication verification at their real process
   boundaries.
5. Repository-wide source contract rejecting every Python path and invocation.

Each slice must remain bounded, preserve the real behavior before deleting the
old implementation, and pass the exact-head CI, database, CodeQL and security
matrix required by its scope.

## Shell and JavaScript boundaries

Shell remains appropriate for invoking external command-line tools, rendering
Helm, installing pinned binaries and orchestrating release state transitions.
JavaScript remains appropriate for browser/accessibility verification and the
frontend build. Neither is a fallback location for policy logic migrated out of
Python.

## Prohibited patterns

- any new `.py` file or Python setup/install step;
- translating Python unit tests into shell tests;
- duplicating a Java/JUnit rule in workflow YAML;
- silent JSON/YAML allow-lists without owner, rationale and expiry semantics;
- deleting a productive adapter before its exact behavior is covered;
- keeping Java and Python implementations of the same policy as fallback.

## Completion criteria

Issue #673 can close only when:

- GitHub code search for `extension:py repo:carstenartur/Taxonomy` returns zero;
- repository-wide search finds no executable Python reference;
- `./mvnw -B verify -Pci` remains the canonical verification command;
- every migrated generator and adapter has positive and negative JUnit coverage;
- the final non-publishing 1.4.0 release dry run succeeds without Python;
- a source contract prevents Python from returning; and
- CI/CD, PostgreSQL, Oracle, SQL Server, CodeQL, Security Scan and relevant
  consumer contracts are green on one unchanged exact head.
