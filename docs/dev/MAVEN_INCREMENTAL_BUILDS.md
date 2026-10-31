# Incremental Maven builds

Taxonomy keeps the canonical Maven reactor command and distinguishes dependency
downloads, reusable packaging outputs, model assets and Docker layers. In split
CI, the Apache Maven Build Cache Extension accelerates the early application
package. Authoritative test lanes execute freshly; restoring an archive never
substitutes for their original source-bound test evidence.

## Why the reactor command stays complete

A simple `-pl ... -amd -am` selection is not sufficient for this repository.
`taxonomy-app` has a deliberately broad fan-in, while `taxonomy-coverage` and
`taxonomy-build` are downstream aggregation and policy modules. Selecting those
modules with `-am` pulls most of the reactor back into the build on a fresh CI
runner.

For an invocation that enables the output cache, Maven still sees the complete
graph and validates each module's inputs. Changed modules and their affected
dependants execute normally. Ordinary local builds may restore compiled/test
outputs according to their configured scope; CI's core, isolated application
groups and CI plugin-verification companions disable output-cache reuse. The
coverage join separately consumes verified producer artifacts and runs its policy
freshly; it does not accept a Maven output-cache hit as test authority.

## Version identity

`.mvn/maven-build-cache-config.xml` enables
`projectVersioning.calculateProjectVersionChecksum`. Each module's project
version therefore participates in its cache key. A transition from a development
snapshot to a release, or to the next development snapshot, rebuilds that module
even when its sources are unchanged. Repeated builds of the same version still
reuse cached outputs.

This preserves the exact host/feature version agreement checked during startup,
as well as the versions embedded in `MANIFEST.MF`, `pom.properties` and filtered
resources. Maven's default version normalization can otherwise restore an older
artifact under new coordinates. Manifest rewriting alone does not protect all
embedded metadata; keep the version in the cache key instead. See the Apache
[project versioning parameters](https://maven.apache.org/extensions/maven-build-cache-extension/parameters.html).

The Maven-owned `MavenBuildCacheVersioningIT` uses the repository's actual pinned
extension and cache configuration in a small standalone project. It warms an
isolated cache at the old version, checks the snapshot/release/next-snapshot
transitions and verifies a cache hit at the unchanged final version. It removes
build outputs between invocations while retaining the cache, and checks both JAR
metadata and the restored physical classes directory.

## CI policy

The early application lane restores `~/.m2/build-cache` with a versioned daily
archive key. PRs, manual runs and tags restore only. Only a successful push to
`main` saves a missing daily entry; later pushes on that UTC day reuse that seed
and rebuild any newly changed module. Maven still validates module inputs after
an archive restore. Internal per-module history retains two builds instead of
four. Existing remote entries are left to normal cache-service eviction; this
policy reduces upload churn and is not a guarantee of a global cache quota.

The application summary records exact/prefix archive restore, matched key,
candidate module entries, rejected restores, skipped cached executions and cache size. An archive hit
alone does not prove that a module's changed inputs were reusable.

Full-scope main, tag and manual runs clean the reactor **before** release-contract
preparation, Helm rendering and opening the Maven log. PR runs do not perform
that cleanup. Do not move `clean` into the `verify | tee target/maven-verification.log`
pipeline: it deletes prepared evidence and unlinks the open log.
`CoreVerificationCleanlinessTest` covers event-specific cleanup, log preservation
and command failure propagation.

Core/shard companions, CI plugin-verification companions, `taxonomy-tooling`,
`taxonomy-coverage`, `taxonomy-build` and the standalone UI-verification POM disable
output-cache reuse. Their scope-sensitive tests or repository-wide evidence must
not be restored from a different invocation. The catalogue-driven cache contract
checks every app-shard profile in both the root and application POMs, including
runtime and domain-integration. The unsliced local lifecycle retains its configured
module-cache behavior; disabling cache reads with `skipCache=true` can still
populate fresh local entries, whereas `enabled=false` disables that behavior.

Pinned Node installation and root npm provisioning run even on an eligible root
cache hit because their runtime directories are not complete attached cache
outputs. Producer archives for coverage are a separate mechanism: verified
classes/resources, original probes and policy bytecode are checked by source/tree
and per-file digests before the fresh aggregate policy runs.

## Dependencies, browser/model assets and Docker layers

Maven dependency-download keys cover conventional POMs, the standalone
`.github/ui-verification-pom.xml`, `**/*-pom.xml`, `**/pom-*.xml`, wrapper properties
and extension descriptors. They cache downloaded dependencies, not successful
JUnit outcomes. Model assets retain their pinned provisioning policy. The
browser-free UI-contract job no longer restores Playwright binaries; browser
shards retain their binary and native-package caches and execute their scenarios.

`ProductionImageIT` still builds and loads actual standard/ONNX images before
checking runtime/revision and offline model behavior. Its repository-owned Docker
helper defaults to ordinary local `docker build`. CI can select Buildx with a
persistent local BuildKit layer export; only main pushes save missing daily
archives, and PRs restore only. A missing/invalid index builds cold. An imported
cache failure retries the real build without external cache arguments; a failed
real build remains a failure. The helper replaces only its own current export
and records configured imports plus observed `CACHED` steps for each target.

Delivery/release use distinct stable GHA cache scopes for standard and ONNX
images, retaining their existing publication, provenance, SBOM and scan checks.
Docker dependency prefetch runs from copied descriptors before source copying
and volatile build identity arguments. Dependencies live in a reusable image
layer; host Maven caches are not mounted into Docker. Reactor siblings remain
source-built. Optional prefetch failure falls through to actual Maven packaging,
which remains authoritative; no Maven test-output cache is enabled in Docker.

Actual cold/warm image timings and cache-service hit rates require hosted CI.
Local command/contract tests and descriptor prefetch do not establish those
performance results. See [the current baseline](CI_PERFORMANCE.md).

## Local use

The extension is enabled through `.mvn/extensions.xml`, so ordinary wrapper
commands use the local build cache automatically. The cache lives in
`~/.m2/build-cache`.

Force a fresh local execution without deleting the cache:

```bash
./mvnw verify -Dmaven.build.cache.skipCache=true
```

Disable the extension behavior for one invocation:

```bash
./mvnw verify -Dmaven.build.cache.enabled=false
```

The repository contract check is:

```bash
node .github/scripts/check-maven-build-cache.mjs
```

### Fresh application distribution on cached upstream builds

The `ci-ui-application-package` profile disables output-cache reuse for
`taxonomy-app` only. Upstream modules remain cacheable. The application always
materializes its current runtime SBOM, external plugin and feature directories,
and executable JAR. This avoids a confirmed ineffective app restore: the pinned
cache extension rejected the app-attached SBOM outside the app directory and
fell back to rebuilding it, after already downloading/restoring cached data.
Cache summaries distinguish found entries from rejected restores and actually
skipped plugin executions.
