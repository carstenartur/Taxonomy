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
4. No `.py` file, versioned or unversioned `python`/`pip`, `pytest`, `unittest`
   or `actions/setup-python` invocation may remain in the final release candidate.
5. A productive adapter is removed only after its positive, negative, failure,
   retry and output contracts are preserved at the real boundary.
6. New Python is prohibited while the migration is in progress.
7. Every protected integration must remain green; adapter deletion and the
   relocation of tests that execute it are one atomic transition.

## Policies already owned by Maven/JUnit or dependency-free Java tooling

| Concern | Integration |
|---|---|
| Workflow test authority | #674 |
| Documentation and image links | #675 |
| Reactor-wide JaCoCo ratchet | #680 |
| Hibernate Search/ORM/Lucene alignment | #681 |
| Action and production-image pinning | #683 |
| Packaged CycloneDX dependency hygiene | #685 |
| Frontend API transport boundaries | #687 |
| Release request ancestry and revision | #716 / #757 |
| Exact release-gate behavior | #737 |
| Version-state implementation | #756 |
| Release parameters and request anchoring | #757 |
| Declared-reactor release-plan validation | #767 |
| Productive release/version workflow routing | #769 |
| Release metadata transformation and routing | #771 / #774 |
| CycloneDX SBOM/VEX companion generation and legacy-helper removal | #804 / #673 bounded cleanup slice |
| CodeQL SARIF high-severity enforcement | #673 bounded Java slice |

## Completed Java release-core migration

The `taxonomy-tooling` reactor module is a dependency-free executable Java JAR.
It owns the release boundary before and after immutable checkout changes and is
preserved in `$RUNNER_TEMP` by workflows that change checkout state.

The release-core migration replaces and removes:

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
- clean immutable release/tag validation, staged resume and protected-main handoff;
- stable GitHub output ordering and user-facing failure diagnostics;
- deterministic JSON output with Unicode, arbitrary-precision integer and
  exponent-preserving decimal semantics;
- transactional CFF, citation, Zenodo, CodeMeta and Helm metadata updates with
  validation-before-write, rollback, idempotency and release-date handling.

Release, protected-main and manual development-version workflows call the same
Java JAR rather than reconstructing these rules in YAML or shell. Historical
`taxonomy-build` fixtures that executed the removed scripts have been deleted;
their behavior remains covered beside the Java implementation in
`taxonomy-tooling`.

## CodeQL SARIF gate

The Java and JavaScript CodeQL jobs build the same dependency-free
`taxonomy-tooling` JAR and invoke its `check-codeql-sarif` command. The command
preserves the reviewed threshold contract: a result blocks when its referenced
rule has `security-severity >= 7.0` or when the result itself explicitly carries
SARIF level `error`.

Retained CodeQL artifacts prove that GitHub's reports keep query rules under
`runs[].tool.extensions[].rules` while `tool.driver.rules` is empty. The former
Python adapter inspected only the driver and therefore resolved those real rule
severities as zero. The Java gate resolves rule metadata from both the driver and
every extension and requires every result to reference exactly one known rule.
Duplicate rule IDs, unsupported SARIF versions, missing/non-regular inputs,
unknown rule references, malformed messages, invalid levels and non-numeric,
non-finite or out-of-range severities fail closed.

Missing result levels remain `warning`; the gate deliberately does not reinterpret
a rule's `defaultConfiguration.level` as an explicit result override. This keeps
the historical error-level policy stable while correcting high-severity lookup.
A deterministic JSON evidence file records all supplied reports, result count,
threshold, blocking findings and PASS/FAIL state. Any controlled failure removes
stale evidence rather than leaving an earlier PASS file behind.

The workflow contains orchestration only: it discovers SARIF paths in stable,
NUL-safe order and invokes the immutable Java JAR. Missing-input policy and all
positive, blocking and malformed-input behavior belong to Java/JUnit. A
repository contract prevents the deleted Python adapter, a shell duplicate or a
Python fallback from returning.

This slice reduces the tracked Python inventory from twelve to **eleven** files.
That count is encoded by the removal-only source ratchet and may only decrease in
subsequent protected integrations.

## Legacy VEX helper removal

The Maven build and productive release script already use the reviewed
`taxonomy-tooling generate-sbom-companion` command for `target/taxonomy-vex.json`.
The old `generate-vex.py` program therefore had no remaining productive call;
only the release workflow still copied it to `$RUNNER_TEMP` and exposed an unused
`VEX_HELPER` variable.

The bounded cleanup removes the file, the copy/chmod operations and the unused
environment variable together. The existing productive-routing JUnit contract is
extended to inspect the release workflow as well as Maven and `release.sh`, so a
legacy helper, Python fallback or competing VEX authority cannot return. The
source inventory then decreases from eleven to **ten** Python files without
changing SBOM/VEX output semantics.

## Removal-only source ratchet

The remaining Python inventory is an upper bound. Later slices may delete an
allowed path but cannot introduce another `.py` file. Productive XML, workflow
YAML, shell, Java main source, JavaScript build scripts, properties, Dockerfiles,
Makefiles and package metadata are diffed against the immutable green
release-core-removal baseline. New executable versioned or unversioned
`python`/`pip`, `pytest`, `unittest` or `actions/setup-python` references fail the
Maven/JUnit build. Token boundaries prevent ordinary shell terms such as
`pipefail` or application identifiers containing `python` from becoming false
positives.

The exact file-inventory check runs in every checkout, including source archives
and intentionally shallow specialized jobs. The ancestry and productive-diff
checks require the immutable baseline object and therefore run in complete-history
checkouts. Canonical CI uses `fetch-depth: 0` and is the merge authority for those
history-sensitive assertions. A missing baseline in any non-shallow Git checkout
remains a hard failure; shallow database or security jobs do not pretend to own a
history they were deliberately not given.

## Remaining migration inventory

The following programs are still present only until their Java/Maven-native or
bounded shell replacements have equivalent JUnit contracts:

### Artifact generation

- `generate-quality-site.py`

### Release, delivery and external-format verification

- `check-release-delivery-contract.py`
- `check-release-image-gate.py`
- `check-delivery-hardening.py`
- `verify-deployment.py`
- `verify-quality-publication.py`
- `check-observability-performance-scope.py`

### Duplicate Python test programs to remove with their productive boundary

- `test-generate-quality-site.py`
- `test-verify-deployment.py`
- `test-verify-quality-publication.py`

No remaining item is an accepted permanent exception.

## Migration order

1. Release parameter, plan and version-state core in `taxonomy-tooling` — complete.
2. Release metadata transformation, productive routing and helper removal — complete.
3. CycloneDX SBOM companion generation, productive routing and obsolete helper removal — complete.
4. CodeQL SARIF policy gate in dependency-free Java with JUnit contracts — complete.
5. Remaining release/delivery and observability policy checks under Java/JUnit authority.
6. Quality-site generation and publication verification in Java/Maven-native code.
7. Deployment verification at its real HTTP/Helm boundary.
8. Repository-wide absolute source contract rejecting every Python path and
   invocation.

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
- merging a deletion before tests that execute the deleted adapter are relocated;
- keeping Java and Python implementations of the same policy as fallback.

## Completion criteria

Issue #673 can close only when:

- GitHub code search for `extension:py repo:carstenartur/Taxonomy` returns zero;
- repository-wide search finds no executable Python reference;
- `./mvnw -B verify -Pci` remains the canonical verification command;
- every migrated generator and adapter has positive and negative JUnit coverage;
- the final non-publishing 1.4.0 release dry run succeeds without Python;
- an absolute source contract prevents Python from returning; and
- CI/CD, PostgreSQL, Oracle, SQL Server, CodeQL, Security Scan and relevant
  consumer contracts are green on one unchanged exact head.
