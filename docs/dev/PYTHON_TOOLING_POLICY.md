# Python tooling migration policy

Taxonomy uses Maven as the canonical verification entry point and JUnit/Failsafe as the authority for executable tests and repository pass/fail policies. Dependency-free Java tooling owns structured release transformations where a real program is required. Shell and GitHub Actions orchestrate external tools but must not duplicate policy logic.

The long-term goal remains a repository with no tracked Python files and no executable Python setup or invocation. On 24 August 2026, complete removal was deliberately moved from the Taxonomy 1.4.0 release scope to issue #673 on the 1.4.1 development line. The bounded adapters retained in 1.4.0 are not product runtime dependencies and remain protected by Maven/JUnit-owned positive and negative contracts.

## Taxonomy 1.4.0 boundary

Taxonomy 1.4.0 may retain only the Python files already listed by the removal-only source ratchet. This is a temporary migration boundary, not an invitation to add new Python tooling and not a permanent exception list.

The release remains acceptable only while all of the following hold:

1. Maven remains the canonical verification command.
2. JUnit/Failsafe or dependency-free Java owns deterministic pass/fail policy.
3. No new `.py` file, `python`, `python3`, `pytest`, `pip`, `unittest`, or `actions/setup-python` reference is introduced.
4. Every retained adapter continues to pass its existing process, output, failure, retry, provenance, and security contracts.
5. Release notes disclose the retained bounded adapters and the post-1.4.0 migration plan truthfully.
6. Any retained adapter shown to cause a concrete correctness or security defect becomes a release issue in its own right.

Complete removal is therefore not a 1.4.0 publication gate. It remains required before #673 can close.

## Permanent architecture rules

- Deterministic repository policy belongs in JUnit, Maven Enforcer, another Maven-native plugin, or the frontend toolchain that owns the source format.
- Structured artifact generation belongs in Java/Maven-native tooling when a real program is required.
- Shell and GitHub Actions may invoke Git, Helm, containers, scanners, and publication clients, but must not become a second implementation of repository policy.
- A productive adapter is removed only after its positive, negative, malformed-input, retry, and output contracts are preserved at the real boundary.
- Adapter deletion and relocation of tests that execute it form one atomic transition.
- Every migration slice must pass the exact-head CI, database, CodeQL, Security, and relevant consumer matrix required by its scope.

## Policies already owned by Maven/JUnit or dependency-free Java

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
| Exact release-gate behaviour | #737 |
| Version-state implementation | #756 |
| Release parameters and request anchoring | #757 |
| Declared-reactor release-plan validation | #767 |
| Productive release/version workflow routing | #769 |
| Release metadata transformation and routing | #771 / #774 |
| CycloneDX SBOM/VEX companion generation | #804 / #846 |
| CodeQL SARIF high-severity enforcement | #846 |

## Completed Java release-core migration

The dependency-free `taxonomy-tooling` executable JAR owns the release boundary before and after immutable checkout changes. It replaced the former Python release-parameter, release-plan, version-state, release-metadata, CodeQL SARIF, and SBOM/VEX companion implementations.

Its Java and JUnit contracts cover:

- workflow-dispatch derivation and explicit major, minor, patch, or exact next-version choices;
- exact-first-parent release-request anchoring, one-file request commits, and sequential request revisions;
- staged-release ancestry and resume behaviour;
- declared Maven reactor traversal, inherited properties, duplicate coordinates, and internal versus external snapshots;
- worktree cleanliness and transactional release metadata updates;
- release, development, and advanced version-state agreement;
- deterministic JSON output and actionable failure diagnostics;
- CodeQL SARIF rules stored in drivers or extensions, unknown-rule and malformed-input rejection, high-severity thresholds, and stale-evidence cleanup;
- SBOM/VEX companion generation without an obsolete Python fallback.

Workflows call the same immutable JAR rather than recreating those rules in YAML or shell.

## Removal-only source ratchet

The current Python inventory is an upper bound. Later slices may delete an allowed path but cannot introduce another Python source file. Productive XML, workflow YAML, shell, Java main source, JavaScript build scripts, properties, Dockerfiles, Makefiles, and package metadata are diffed against the immutable release-core-removal baseline. New executable Python or setup references fail the Maven/JUnit build.

The exact file inventory runs in every checkout. Complete-history checkouts also enforce ancestry and productive-diff rules. Shallow specialised jobs do not pretend to own history they were deliberately not given; canonical CI remains the authority for those assertions.

## Remaining post-1.4.0 inventory

The following files remain temporarily and may only be removed in contract-preserving slices:

### Artifact generation

- `.github/scripts/generate-quality-site.py`

### Release, delivery, and external-boundary verification

- `.github/scripts/check-release-delivery-contract.py`
- `.github/scripts/check-release-image-gate.py`
- `.github/scripts/check-delivery-hardening.py`
- `.github/scripts/check-observability-performance-scope.py`
- `.github/scripts/verify-deployment.py`
- `.github/scripts/verify-quality-publication.py`

### Duplicate Python test programs

- `.github/scripts/test-generate-quality-site.py`
- `.github/scripts/test-verify-deployment.py`
- `.github/scripts/test-verify-quality-publication.py`

No item is accepted as a permanent exception.

## Post-release migration order

1. Move release-delivery and immutable-image policy to Java/JUnit authority.
2. Move delivery-hardening and observability-scope policy to Java/JUnit authority.
3. Replace quality-site generation and publication verification with Java/Maven-native tooling and deterministic output tests.
4. Replace deployment verification at its real HTTP/Helm boundary.
5. Delete the final Python file and executable/setup reference.
6. Replace the shrinking allow-list with an absolute zero-Python source contract.

Draft PRs #851 and #852 were closed unmerged when 1.4.0 publication was prioritised. Their ideas may be reconstructed on the post-release `main`; stale stacked branches are not release evidence.

## Prohibited patterns

- any new `.py` file or Python setup/install step;
- translating Python unit tests into shell tests;
- duplicating a Java/JUnit rule in workflow YAML;
- silent JSON/YAML allow-lists without owner, rationale, and expiry semantics;
- deleting a productive adapter before its exact behaviour is covered;
- keeping Java and Python implementations of the same policy as fallbacks.

## Completion criteria for #673

Issue #673 can close only when:

- repository search returns zero tracked Python files;
- repository-wide search finds no executable Python or setup reference;
- `./mvnw -B verify -Pci` remains the canonical verification command;
- every migrated generator and adapter has positive and negative JUnit coverage;
- an absolute source contract prevents Python from returning; and
- CI/CD, PostgreSQL, Oracle, SQL Server, CodeQL, Security Scan, and relevant consumer contracts are green on one unchanged exact head.
