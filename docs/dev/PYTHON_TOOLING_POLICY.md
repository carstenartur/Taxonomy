# Python tooling policy

Taxonomy uses Maven as the canonical verification entry point and JUnit/Failsafe
as the authority for executable tests and repository pass/fail policies. Python
is not a default build language and may not be introduced merely because a
one-file checker is convenient.

## Permanent rules

1. A deterministic repository policy belongs in JUnit, Maven Enforcer, another
   Maven-native plugin, or the frontend toolchain that owns the source format.
2. Canonical verification must not execute Python `unittest` programs.
3. A workflow must not contain a second implementation of a Maven/JUnit policy.
4. Retained Python must have an explicit non-test role, deterministic input and
   output contracts, and contract coverage owned by JUnit or Maven-native tests.
5. A retained adapter is not a permanent exemption from simplification. It must
   remain only while its workflow boundary makes a Java/Maven replacement less
   reliable or materially more complex.

## Policies migrated to JUnit/Failsafe

| Concern | Integration |
|---|---|
| Workflow test authority | #674 |
| Documentation and image links | #675 |
| Reactor-wide JaCoCo ratchet | #680 |
| Hibernate Search/ORM/Lucene alignment | #681 |
| Action and production-image pinning | #683 |
| Packaged CycloneDX dependency hygiene | #685 |
| Frontend API transport boundaries | #687 |

The entries after #680 remain subject to exact-head verification and ordered
integration. Their Python implementations are removed in the corresponding
slice rather than retained as fallback code.

## Retained artifact generators and format transformers

These programs produce artifacts; they do not decide whether application code
is correct:

- `generate-vex.py` — transforms the packaged CycloneDX SBOM into the published
  VEX companion artifact.
- `generate-quality-site.py` — transforms already verified JUnit, coverage and
  evidence inputs into the static quality publication.
- `update-release-metadata.py` — applies one release/development version state to
  citation, archive and package metadata during the release transaction.

Their output schemas and deterministic transformations must be covered from
JUnit/Maven-native contract tests. Their existing Python `unittest` suites are
migration work, not accepted permanent exceptions.

## Retained release and external-tool adapters

The following scripts currently bridge Git/GitHub workflow state, detached tags,
release staging, deployment targets or external report formats:

- `resolve-release-parameters.py`
- `check-version-state.py`
- `check-release-plan.py`
- `check-release-delivery-contract.py`
- `check-release-image-gate.py`
- `check-delivery-hardening.py`
- `verify-deployment.py`
- `verify-quality-publication.py`
- `check-codeql-sarif.py`
- `check-observability-performance-scope.py`

This list is a temporary classification, not an allow-list for new Python.
Core validation logic should move to JUnit/Maven-native contracts where the same
rule can be executed reliably from the reactor. Small adapters may remain to
translate workflow environment, GitHub output files, detached Git state, SARIF,
or deployment responses into those contracts.

## Shell and JavaScript tools

Shell remains appropriate for invoking external command-line tools, rendering
Helm, installing pinned binaries and orchestrating the release transaction.
JavaScript remains appropriate for browser/accessibility verification and the
frontend build. Those tools are governed by the same rule: workflows invoke a
catalogued Maven profile or a single documented adapter, not a duplicated test
implementation.

## Prohibited patterns

- new `test-*.py` or Python `unittest` execution in canonical CI;
- direct workflow invocation of a migrated checker;
- silent JSON/YAML allow-lists without owner, rationale and expiry semantics;
- converting an artifact generator into a JUnit test merely to eliminate a file
  extension;
- keeping both Java and Python implementations of the same policy as fallback.

## Completion criteria

Issue #673 can close only when:

- canonical `./mvnw -B verify -Pci` invokes no Python `unittest` program;
- every retained Python file appears in one of the bounded categories above;
- every retained generator/adapter has JUnit or Maven-native contract coverage;
- workflows contain no direct selector or duplicate implementation for migrated
  policies; and
- CI, database compatibility, CodeQL, security and relevant consumer contracts
  remain green after the final cleanup.
