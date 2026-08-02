# Release Verification and Publication

Taxonomy separates release verification from publication.

- Maven owns the locally reproducible version, reactor, dependency and test checks.
- `.github/scripts/release.sh` owns the atomic Git state transition.
- `.github/workflows/deploy-release.yml` owns GitHub Release, Helm, container and
  deployment publication gates.

There is deliberately no second SCM authority through `maven-release-plugin`.

## Fast local release-plan check

Run this before starting a release:

```bash
./mvnw -B -Prelease-check validate \
  -DreleaseVersion=1.3.0 \
  -DnextDevelopmentVersion=1.3.1-SNAPSHOT
```

The command is non-mutating. It verifies:

1. the current root and reactor versions match `${releaseVersion}-SNAPSHOT`;
2. the release version uses `X.Y.Z` and the next version uses
   `X.Y.Z-SNAPSHOT`;
3. the next version is numerically newer, including freely selected major or
   minor transitions;
4. all Taxonomy modules use one consistent reactor version;
5. no external dependency, plugin or build extension uses a SNAPSHOT version;
6. the checkout is clean;
7. no `release.properties`, `pom.xml.releaseBackup` or
   `maven-release-plugin` configuration introduces a competing release path.

For example, a major transition is valid:

```bash
./mvnw -B -Prelease-check validate \
  -DreleaseVersion=1.3.0 \
  -DnextDevelopmentVersion=2.0.0-SNAPSHOT
```

Repeating `1.3.0-SNAPSHOT` as the next version is invalid because the current
snapshot is the source of release `1.3.0`; development must continue at a newer
version.

## Complete local release verification

The complete release candidate check combines the release contract with the
same canonical suite used by pull requests:

```bash
./mvnw -B -Prelease-check,ci clean verify \
  -DreleaseVersion=1.3.0 \
  -DnextDevelopmentVersion=1.3.1-SNAPSHOT
```

This command requires the same Docker, browser and model-download prerequisites
as `./mvnw -B -Pci verify`. It still creates no tag, branch, GitHub Release,
container image or deployment.

## States used by the publication state machine

The profile defaults to `development`:

| State | Expected checked-out project version | Purpose |
|---|---|---|
| `development` | `${releaseVersion}-SNAPSHOT` | normal local preflight and new release |
| `release` | `${releaseVersion}` | immutable release commit verification |
| `advanced` | `${nextDevelopmentVersion}` | safe resume after `main` already advanced |

`release.sh` selects these states itself. Direct use is mainly useful when
reproducing a failed release stage:

```bash
./mvnw -B -Prelease-check validate \
  -DreleaseVersion=1.3.0 \
  -DnextDevelopmentVersion=1.3.1-SNAPSHOT \
  -DreleaseCheckCurrentState=advanced
```

The clean-check can be disabled only for focused development of the validator:

```bash
-DreleaseCheckRequireClean=false
```

It is never disabled by the real publication path.

## Publication responsibilities

After the Maven-owned check succeeds, the existing release state machine still
performs the project-specific operations that a generic Maven release plugin
cannot safely replace:

- synchronize Maven, citation, Zenodo, Codemeta and Helm versions;
- create and verify the immutable release commit and annotated tag;
- create a maintenance branch without overwriting an existing one;
- keep the GitHub Release as a draft until downstream artifacts are complete;
- generate and attach JAR, SBOM, VEX and Helm artifacts;
- build the container image from the immutable tag;
- advance `main` once, by fast-forward, to the selected next snapshot;
- verify the exact resulting `main` commit with canonical CI;
- publish and deploy only after every preceding gate succeeds;
- resume a staged release without recreating its tag or version commits.

This division keeps the Maven checks reproducible on a developer checkout while
preserving the stronger atomic publication guarantees already required by
Taxonomy.
