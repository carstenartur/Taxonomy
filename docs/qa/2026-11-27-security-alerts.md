# Security alert remediation — 27 November 2026

## Baseline and scope

The signed-in GitHub Security UI was inspected at main commit
`724f4111be87feb93682a928496bc8fda37d4bbf`:

| Category | Open findings |
| --- | ---: |
| Dependabot vulnerabilities | 1 |
| Code scanning | 43, all reported by Trivy |
| Secret scanning | 0 |
| Malware | 0 |

The baseline [Security Scan run](https://github.com/carstenartur/Taxonomy/actions/runs/45966770020)
published artifact `15183876167`. Its original SARIF contains 43 findings and
identifies Trivy 0.70.0. The build gate passed although the log reports that the
SNAPSHOT Helm chart could not render without an explicit image tag or digest.
Successful dependency scanning did not establish Helm coverage.

## Dependency correction

[Dependabot alert #126](https://github.com/carstenartur/Taxonomy/security/dependabot/126)
is [CVE-2026-118259 / GHSA-ccrj-gw8r-854m](https://github.com/open-telemetry/opentelemetry-java/security/advisories/GHSA-ccrj-gw8r-854m),
quadratic CPU consumption while parsing crafted W3C `tracestate` headers.

The resolved dependency path is `taxonomy-app` → `selenium-chrome-driver` →
`selenium-remote-driver` → OpenTelemetry, entirely in test scope. Spring Boot's
inherited BOM managed the family at 1.62.0. Overriding its existing
`opentelemetry.version` property to the patched 1.67.0 release keeps the complete
family aligned rather than overriding only the API artifact.

The complete before/after Maven dependency trees each contain 440 coordinates.
Exactly eleven OpenTelemetry components change from 1.62.0 to 1.67.0, all retaining
test scope. No other coordinate, version or scope changes, additions, removals or
downgrades were observed. Maven validation and dependency resolution succeeded for
all 20 selected projects.

## Deployment corrections

- The small provider declares both resource namespaces, its application image's
  non-root identity 10001, and a tagged placeholder with `imagePullPolicy: Never`.
- Its renderer accepts only the two owned smoke namespaces and the exact locally
  built source-SHA image. It validates and buffers the entire result before
  creating any Secret, ConfigMap or workload. Missing/replaced template slots fail
  before cluster mutation.
- The Artemis smoke LimitRange now includes minimum container requests, compatible
  with every fixture's declared requests.
- Disposable Artemis and PostgreSQL smoke workloads use UID/GID/fsGroup 10001.
  Their exact pinned Linux/amd64 image configs and extracted entrypoints were
  inspected: Artemis creates its instance in the writable PVC, and PostgreSQL
  includes the `nss_wrapper` path needed for an arbitrary numeric UID. The existing
  live kind workflow must confirm initialization and reuse of the same PVCs.
- The two Apache Artemis image references explicitly name `docker.io`; their
  existing immutable digests are preserved. Trivy's documented custom registry
  data approves Docker Hub and GHCR, the registries actually used by this project.
  The registry rule remains enabled.
- The existing Maven supply-chain policy also checks exact registry hostnames in
  static deployment YAML and Helm values. It rejects suffix lookalikes such as
  `evilghcr.io`, subdomains and unapproved ports. Docker Hub's implicit references
  and the local smoke image remain valid. Safe YAML loading rejects malformed
  documents and duplicate keys; template files are inspected by the rendered scan.

## Accurate scan coverage

The workflow pins Trivy 0.76.2, supplies a source-SHA image tag and a Secret **name**
for rendering, and records all severities plus successful misconfiguration checks
in the original JSON report. It converts that same scan to SARIF for publication;
successful checks are not published as alerts.

The Java `check-trivy-coverage` command requires the actual chart resource target to
have type `helm`, completed checks, and matching detailed/summary counts. It also
validates the Trivy SARIF structure and compares every vulnerability, secret and
failed configuration finding against the original JSON by ID, severity and occurrence
count. Empty or truncated conversion output cannot conceal a source finding.

SARIF upload requires this coverage step to succeed. The separate
`check-trivy-sarif` HIGH/CRITICAL gate still fails on blocking findings, while the
complete report remains uploadable so those findings appear in GitHub. Incomplete
reports remain diagnostic artifacts and cannot accidentally close existing alerts.
No security rule or scanner is disabled.

The policy configuration and all deployment files now trigger the security PR
workflow. Runtime image scans remain separate from this repository coverage check.

## Trivy wrong-resource-kind findings

Thirty baseline findings are caused by Trivy applying a resource-specific rule to
other Kubernetes objects in the scan. KSV-0039 describes a `LimitRange` but is also
reported on ResourceQuotas, Deployments, Services and PVCs; KSV-0040 describes a
`ResourceQuota` but is also reported on LimitRanges and other resource kinds.
These resources must not acquire an invalid foreign `spec` merely to silence a
scanner. The actual smoke namespace policies are present and checked separately.
Production namespace policy remains an administrator prerequisite documented in
the [Artemis setup guide](../de/ARTEMIS_SETUP.md#21-ressourcenbudget-des-namespaces).

The source-level cause is independently confirmed by upstream Trivy commit
[`decb934`](https://github.com/aquasecurity/trivy/commit/decb934aa98f55ec351e8986c06bd2f84b254630),
which changes subtype filtering from a check-wide applicability decision to the
specific inputs passed into each rule. That fix is not included in released
Trivy 0.76.2. Updating the scanner alone therefore does not resolve these thirty
false positives.

The individually identified false-positive alerts are:

| Rule | Alert numbers |
| --- | --- |
| KSV-0039 | 61, 249, 277, 278, 279, 280, 303, 304, 307, 317, 318, 324, 325, 326, 331 |
| KSV-0040 | 62, 254, 281, 282, 283, 284, 309, 311, 312, 319, 320, 327, 328, 329, 332 |

Alert #244 is deliberately excluded: it points to the actual incomplete Artemis
smoke LimitRange and is addressed by the manifest change. The thirty wrong-kind
findings are suitable for individual false-positive resolution, with the concrete
resource-kind reason retained in GitHub; they are not blanket rule exclusions.

## Existing production broker identity

The two remaining production broker findings (#275 and #276) describe its actual
UID/GID 1001. Existing broker PVCs can contain configuration files owned by 1001
with mode 0600. Changing the process UID and fsGroup does not change that ownership,
and the startup script cannot subsequently chmod another owner's files without a
capability deliberately removed from this workload. The deployment guide documents
the [image and identity constraint](../de/ARTEMIS_SETUP.md#22-image-und-benutzerkennung).

These two LOW findings are compatibility exceptions, not fixed numeric identities
or scanner false positives. Existing controls remain: non-root execution, read-only
root filesystem, no privilege escalation, all capabilities dropped, RuntimeDefault
seccomp, no service-account token, restricted network access and an immutable image
digest. A future UID migration requires a planned, backed-up ownership migration of
the existing broker volume. Disposable smoke volumes do not have that legacy-data
constraint.

## Verification and limits

The focused scanner regressions first failed on the old implementation. Coverage,
conversion integrity and upload gating are checked alongside the existing release
evidence tests. The actual baseline JSON is rejected for missing Helm coverage;
the corrected Trivy configuration produces seven scanned targets, including 99
successful Helm checks and zero failing Helm checks.

Maven executed all 12 `ReleaseEvidenceTest` and 28 `EvidenceManifestTest` cases
successfully, with no failures, errors or skips. The tests include malformed SARIF,
missing raw findings, changed severities, all three scanner domains, and publication
of a complete HIGH report while retaining its failing severity gate.

The exact-host registry regressions were executed against unchanged original test
sources with JUnit 6.0.3: 21 new cases failed before implementation, and all 31 tests
passed afterward, including the real repository inspection. This isolated run
uses resolved dependencies and records original source hashes; it is not presented
as a complete Maven reactor pass.

The two original Helm contract classes likewise passed all 20 tests with the pinned,
checksum-verified Helm 3.21.0 executable. These were isolated JUnit executions of
the original sources; no source overlay or changed assertion was used.
The unchanged `BrowserSessionTest` also passed all 14 cases with the resolved
application test classpath, including OpenTelemetry 1.67.0. This verifies the
session helper's unit contract, not an actual browser journey. The wider Selenium
and reconciliation suites remain assigned to the full CI gates.

The first PR CI run caught a renderer portability defect: GNU awk reserves
`namespace`, while local mawk accepted that variable. The existing provider
regression failed in both the plugin-profile and document-report lanes before
runtime deployment. The renderer now uses the specific name `smoke_namespace`.
All 20 unchanged Helm contract tests pass with GNU awk 5.2.1 first in PATH, using
locally extracted official Debian packages rather than replacing system tools.

CodeQL's first PR push run completed successfully. Its Java SARIF export logged
three internal `java/csrf-unprotected-request-type` graph errors for login/edit/localEdit.
The same three messages are present in the successful main-base run 46062421210;
after removing timestamps they match byte for byte. This is an existing scanner
limitation, not a new finding or a claimed error-free export.

After all deployment corrections, the configuration scan on integration base
`487c2646721e41c42b8c0e57a06621fffab20b44` still covers seven targets and passes all
99 Helm checks. It contains exactly 32 LOW findings: fifteen KSV-0039, fifteen
KSV-0040 and the two production broker identity advisories. Eleven genuine baseline
configuration findings no longer occur. This count describes the local
configuration report, not yet the live GitHub alert state or a full dependency scan.
The exact workflow `check-trivy-coverage` and `check-trivy-sarif` commands both
accept this original JSON/SARIF pair: all source finding IDs, severities and
occurrence counts are preserved, and no HIGH/CRITICAL finding is present.

The [PR Security Scan on `b68e104`](https://github.com/carstenartur/Taxonomy/actions/runs/46065704854)
subsequently completed the full filesystem scan with vulnerability, secret and
misconfiguration scanning enabled. Its original JSON binds the PR test merge
`be7707165ff34ce0f19c28dabb9128604f9b1aa4` to that head and base `487c264`.
The report contains 25 Maven POM inventories and seven configuration targets,
99 passing Helm checks, no reported vulnerabilities or secrets, and exactly the
32 documented LOW results. Coverage, severity, SARIF upload and processing all
passed. The downloaded original artifact's SHA-256 matches GitHub's digest.
Trivy excludes development/test dependencies by default, so this result does
not replace the resolved Maven and Dependabot evidence for OpenTelemetry.

The [document-template E2E run on `b68e104`](https://github.com/carstenartur/Taxonomy/actions/runs/46065704897)
also completed successfully after the GNU awk correction. The full Maven and
Kubernetes integration gates remain separate required evidence. This successful
Maven run executes all 20 Helm contracts and all 31 supply-chain policy cases
within 322 build-module tests, with zero failures, errors or skips. Its actual
`DocumentTemplateReportDownloadIT` also passes.

### Native package cache prerequisite

The integration run exposed an existing shared package-cache defect introduced
by base PR #1612. Both the screenshot workflow and the WebKit UI shard failed
before their tests at `apt-get install --no-download`, after successful archive
checksum verification. The scripts were unchanged by the security corrections.

The [successful UI cache producer](https://github.com/carstenartur/Taxonomy/actions/runs/46064384240/job/140727627680)
on Azure and the [failing consumer](https://github.com/carstenartur/Taxonomy/actions/runs/46065704971/job/140731355190)
on AWS report the same Ubuntu image version `20261122.9.1`. The producer downloaded
and installed 185 new packages. The consumer restored those same 185 archives but
APT additionally required `libjson-glib-1.0-0` and `libjson-glib-1.0-common`, which
were absent from that cache. A download-only cache is a delta against the
producer's installed packages; the runner image label alone did not establish
that the consumer had the same already-satisfied dependencies.

The cache correction binds both UI and document package caches to a canonical
fingerprint of the installed package state and APT architecture/variant settings.
The compatibility check recomputes that state before accepting the exact cache
key. A changed baseline requires a distinct cache; failed state queries stop
preparation. Archive checksums, offline installation, time limits and all test
gates remain enforced. Regression checks exercise the actual workflow shell
blocks with equal image labels and different dependency baselines.
All 41 focused package/workflow cases pass after reproducing the old failures,
including case-insensitive APT options, ordered architecture preferences,
query failures and baseline drift. An independent review found no remaining
substantive issue. The broader local Maven UI-contract attempt produced no
JavaScript contract results after Surefire startup and was stopped with exit 130.
It is not counted as a pass; the full CI contract
and hosted package-installation gates must validate the published correction.

### Runtime and local verification boundaries

The provider regression exercises the real shell renderer and installation
sequence with external cluster/certificate commands replaced by boundary fixtures.
It checks both owned namespaces, ten invalid input combinations and two changed
template cases. It first reproduced the missing identity/namespace and unsafe
input acceptance, then passed after the correction. This is a real renderer test,
not a live Kubernetes deployment. The existing Kubernetes Constrained Smoke
workflow remains the acceptance gate for actual image startup and persistent
service recovery.

The local filesystem scan attempt encountered a Maven repository HTTP 429 while
analyzing dependency metadata and did not produce a complete report. It is not
counted as a successful full vulnerability/secret scan. The successful local Trivy
run was a configuration scan; the successful full filesystem CI scan is recorded
above. Two local
package attempts ended with SIGKILL: first during compilation, then with an explicit
384 MiB Maven heap after all production modules compiled, during Spring Boot
repackaging. No kernel cause is inferred and neither attempt is counted as a pass.
One additional focused reactor test attempt ended with SIGKILL before reaching
the changed build-contract tests; it is also not counted as executed test coverage.

Raw reports and logs remain in task build output or CI artifacts. The exact final
PR checks, merge and live alert dispositions must be verified against the final
published revision before reporting completion.
