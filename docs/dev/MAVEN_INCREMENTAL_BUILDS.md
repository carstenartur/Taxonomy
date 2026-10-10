# Incremental Maven builds

Taxonomy keeps the canonical Maven reactor command, but pull-request builds use
the Apache Maven Build Cache Extension to avoid repeating compilation and tests
whose complete inputs are unchanged.

## Why the reactor command stays complete

A simple `-pl ... -amd -am` selection is not sufficient for this repository.
`taxonomy-app` has a deliberately broad fan-in, while `taxonomy-coverage` and
`taxonomy-build` are downstream aggregation and policy modules. Selecting those
modules with `-am` pulls most of the reactor back into the build on a fresh CI
runner.

The build cache gives the reactor the full graph and lets Maven validate each
module's inputs. Unchanged modules restore their compiled/test outputs; changed
modules and modules whose dependency inputs changed execute normally. This keeps
the dependency semantics of a full reactor build without paying the full work on
every PR update.

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

Pull requests run the normal `verify -Pci` command with cache reads enabled.
GitHub Actions persists `~/.m2/build-cache` separately for the application
package and canonical verification lanes. The cache key carries an explicit
namespace version; bump it whenever the set of restored outputs changes so old,
incomplete cache entries cannot satisfy a newer contract.

Pushes to `main`, tags, and manual workflow runs clean the full reactor **before**
release-contract preparation, Helm rendering, and opening the Maven log. After
that preparation they run the uncached-read verification suite:

```bash
./mvnw -B clean -Dmaven.build.cache.enabled=false
# CI prepares release-contract and Helm evidence here, then starts the log.
./mvnw -B verify -Pci -DrunOnnxTests=true -Dtaxonomy.ui.skip=true \
  -Dmaven.build.cache.skipCache=true
```

Do not move `clean` into the `verify | tee target/maven-verification.log`
pipeline: it deletes the prepared Helm evidence and unlinks the already-open log.
PR builds do not perform this cleanup. `CoreVerificationCleanlinessTest` exercises
the actual workflow shell for PR, push, and manual events with successful and
failed Maven exits, including preservation of stdout/stderr and failure status.
Its test-only Maven substitute models deletion, output, and exit status; it is
not a Maven build-cache integration test.

`skipCache=true` disables cache reads but still allows the completed authoritative
build to populate fresh entries for later PR builds.

`taxonomy-coverage` and `taxonomy-build` explicitly set
`maven.build.cache.enabled=false`. The separate UI-verification POM does the same.
These projects therefore always recompute aggregate evidence and repository-wide
quality gates. The root `install-pinned-node` execution is configured to run even
on a cache hit, restoring the shared `target/test-runtime/frontend` installation
before JavaScript-consuming tests. Cached modules restore `target/classes`,
`target/test-classes`, Surefire/Failsafe reports and `jacoco.exec`
so those aggregate gates never depend on missing evidence.

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
