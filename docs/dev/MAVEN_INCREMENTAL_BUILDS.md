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

## CI policy

Pull requests run the normal `verify -Pci` command with cache reads enabled.
GitHub Actions persists `~/.m2/build-cache` separately for the application
package and canonical verification lanes. The cache key carries an explicit
namespace version; bump it whenever the set of restored outputs changes so old,
incomplete cache entries cannot satisfy a newer contract.

Pushes to `main`, tags, and manual workflow runs execute:

```bash
./mvnw -B clean verify -Pci -DrunOnnxTests=true -Dtaxonomy.ui.skip=true \
  -Dmaven.build.cache.skipCache=true
```

`skipCache=true` disables cache reads but still allows the completed authoritative
build to populate fresh entries for later PR builds.

`taxonomy-coverage` and `taxonomy-build` explicitly set
`maven.build.cache.enabled=false`. The separate UI-verification POM does the same.
These projects therefore always recompute aggregate evidence and repository-wide
quality gates, and the cache extension cannot stage transient files such as
`taxonomy-build/target/frontend` between focused Maven invocations. Cached modules restore `target/classes`, `target/test-classes`, Surefire/Failsafe reports and `jacoco.exec`
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
