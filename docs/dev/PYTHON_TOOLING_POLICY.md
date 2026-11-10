# Java tooling and the zero-Python policy

Maven is the canonical verification entry point. JUnit/Failsafe owns executable
acceptance and repository pass/fail policies. Dependency-free Java owns structured
release and delivery transformations; shell and GitHub Actions orchestrate the
existing external tools.

## Absolute source and runtime boundary

The remaining eight Python adapters and duplicate test programs from #629 have
been replaced together with their callers. There is no permanent Python allowlist,
historical-diff exception or Python fallback.

`PythonSourceRatchetRepositoryTest` checks the current source tree, including
untracked source files, and rejects Python source and productive interpreter,
installer or setup invocations. Generated files under `target/`, installed Node
dependencies and Git metadata are excluded from this source inventory. Java test
fixtures may quote prohibited commands to prove that the recognizer rejects them;
this does not authorize executing them in productive tooling.

The canonical full verification command remains:

```bash
./mvnw -B verify -Pci -DrunOnnxTests=true
```

A focused tooling run is useful during development:

```bash
./mvnw -B -pl taxonomy-tooling -am test
```

It does not replace application, browser, PostgreSQL, SQL Server, Oracle,
CodeQL or Security Scan acceptance on the unchanged source revision.

## Productive command ownership

| Concern | Java/Maven owner |
|---|---|
| Release parameters, ancestry, reactor versions and metadata | Existing `TaxonomyTooling` release commands |
| Immutable image workflow requirements | `ReleaseImageGate` and JUnit repository/mutation tests |
| Release and delivery repository policies | `DeliveryGuards`, `CoreDeliveryContractsTest`, `DeliveryGuardRejectionTest` |
| Raw JUnit execution outcomes | Shared strict `JunitOutcomeVerifier` and secured `XmlSupport` parser |
| Quality summary, badges and HTML | `generate-quality-site` |
| Local/remote publication and original report verification | `verify-quality-publication` |
| Readiness, live commit and Render rollout evidence | `verify-deployment`, `DeploymentVerifierTest` |
| Disabled/failed/triggered Render receipts and exact hook pin | `render-delivery-state`, `render-deploy-url`, `render-service-id` |
| Standard and ONNX immutable image receipts | `write-release-image-evidence`, `read-evidence-digest` |
| Trivy HIGH/CRITICAL enforcement | `check-trivy-sarif` |
| Storage consumer selectors from the existing catalogue | `storage-contract-selectors` |
| Source/artifact evidence inventory | `write-quality-manifest`, `check-evidence-manifest` |

`.github/scripts/taxonomy-tooling.sh` launches the exact checked-out Java 25
sources. Where the release workflow has already built and preserved its immutable
tooling JAR, it invokes that JAR directly. An explicitly supplied `TOOLING_JAR`
selects the same packaged entry point. There is no downloading of newer tooling
or substitution of the current main revision during evidence verification.

## Preserved acceptance boundaries

Generator and verifier tests retain positive and negative XML/JSON, missing input,
wrong source, inconsistent badges, raw outcome recounts, malformed input, repeated
attempt identities, original plugin/application shard digests, dedicated test
ownership, cache headers and bounded retries. Rerun/flaky outcomes and XML
DTD/entities are rejected. Coverage rounding and badge formatting preserve the
retired generator's binary floating-point, ties-to-even behavior.

Deployment acceptance exercises actual loopback HTTP responses and the checked-in
workflow shell blocks. It retains the readiness/commit/root-smoke sequence,
Render terminal-failure handling, bounded polling and explicit
`disabled`, `triggered`, `verifying`, `succeeded`, `failed` evidence. Credentials
stay in the existing environment mechanism; errors do not copy HTTP payloads,
authorization headers or hook URLs into diagnostics.

Repository guards preserve both sides of existing shell/workflow delegation.
Mutation tests reject weakened immutable scans, stale-main publication,
unbounded diagnostics, stale report publication, missing Render evidence and
weakened resource profiles. Structured policy remains in Java/JUnit.

## Source/artifact manifest

See [the manifest contract](EVIDENCE_MANIFEST.md) and
[version 1 schema](evidence-manifest-v1.schema.json). The existing CI finalizer
writes the quality profile only after generating and verifying the original
quality evidence. Its manifest binds raw JUnit, aggregate coverage, UI summary
and the generated summary by file digest and immutable source identity.

The release profile requires the wider artifact and gate inventory. A verifier
checks an assembled archive; it never creates a release, tag, container image,
deployment or DOI. The recorded release-to-main mapping is verified with the
existing `ReleaseHistoryVerifier` when artifact source and final verification
source differ. A manifest cannot turn a fixture into public delivery evidence.

## Permanent rules

- Do not introduce Python source, setup or execution into productive tooling.
- Do not replace Java/JUnit policy with shell tests or workflow expressions.
- Preserve output, malformed-input, failure, retry, exact-source and security
  behavior when changing a productive adapter.
- Keep generated raw reports and migration/parity diagnostics under `target/`
  or existing CI artifacts.
- Require the existing exact-head CI and relevant consumer matrix before merging;
  local focused verification alone is not release certification.
