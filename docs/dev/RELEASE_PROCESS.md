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
  -DreleaseVersion=1.4.0 \
  -DnextDevelopmentVersion=1.4.1-SNAPSHOT
```

The command is non-mutating. It verifies:

1. the current root and every declared reactor-module version match
   `${releaseVersion}-SNAPSHOT`;
2. the release version uses `X.Y.Z` and the next version uses
   `X.Y.Z-SNAPSHOT`;
3. the next version is numerically newer, including freely selected major or
   minor transitions;
4. the reactor is recursively derived from Maven `<modules>` declarations;
   missing modules and module paths outside the repository fail closed, while
   unrelated example POMs are not misclassified as release modules;
5. reactor coordinates are unique and version properties inherited through
   internal parent POMs are resolved with child-over-parent precedence;
6. no external parent, dependency, plugin or build extension uses a SNAPSHOT
   version, and no explicit version may retain an unresolved `${...}` property;
7. the checkout is clean, including linked Git worktrees;
8. no `release.properties`, `pom.xml.releaseBackup` or
   `maven-release-plugin` configuration introduces a competing release path.

For example, a major transition is valid:

```bash
./mvnw -B -Prelease-check validate \
  -DreleaseVersion=1.4.0 \
  -DnextDevelopmentVersion=2.0.0-SNAPSHOT
```

Repeating `1.4.0-SNAPSHOT` as the next version is invalid because the current
snapshot is the source of release `1.4.0`; development must continue at a newer
version.

## Complete local release verification

The local release candidate check combines the release contract with a
combined Maven verification lifecycle:

```bash
./mvnw -B -Prelease-check,ci clean verify -DrunOnnxTests=true \
  -DreleaseVersion=1.4.0 \
  -DnextDevelopmentVersion=1.4.1-SNAPSHOT
```

This command requires Docker, browser and a provisioned pinned embedding model
(`TAXONOMY_EMBEDDING_MODEL_DIR` and `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false`
for the offline CI-style run). The actual CI workflow runs a core `-Pci`
lane with UI execution skipped, then runs repository-owned UI shards and an
evidence gate as separate jobs. A single local Maven command does not by
itself reproduce that complete split CI evidence. It creates no tag, branch,
GitHub Release, container image or deployment.

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
  -DreleaseVersion=1.4.0 \
  -DnextDevelopmentVersion=1.4.1-SNAPSHOT \
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
- generate and attach the complete application distribution, JAR, SBOM, VEX and
  Helm artifacts;
- build the container image from the immutable tag;
- advance `main` once through a protected pull request using rebase merge;
- verify the exact resulting `main` commit with canonical CI;
- publish and deploy only after every preceding gate succeeds;
- resume a staged release without recreating its tag or version commits.

This division keeps the Maven checks reproducible on a developer checkout while
preserving the stronger atomic publication guarantees already required by
Taxonomy.

## Downloadable application distribution

The release workflow creates `taxonomy-<version>-distribution.tar.gz` and its
`.tar.gz.sha256` checksum. The archive contains a `taxonomy-<version>/` directory
with `app.jar`, all six standard startup feature JARs in `features/`, and the
external Mermaid plugin and its checksum in `plugins/`. It also includes launch
instructions, legal notices, the reviewed release notes and `SHA256SUMS` covering
every delivered file. Individual JAR assets remain available for inspection.

Use Java 21 and set `TAXONOMY_ADMIN_PASSWORD` to a unique secret in the environment
before the first startup. For a downloaded 1.5.0 distribution, run:

```bash
sha256sum --check taxonomy-1.5.0-distribution.tar.gz.sha256
tar -xzf taxonomy-1.5.0-distribution.tar.gz
cd taxonomy-1.5.0
sha256sum --check SHA256SUMS
java -jar app.jar
```

Replace `1.5.0` with the version downloaded. Start from the extracted directory so
the launcher can find `features/` and the plugin manager can find `plugins/`.
Configure provider and deployment settings using the tagged deployment guide
linked in the archive's README.

Collection fails if a standard feature or plugin is missing or a plugin checksum
is missing or incorrect. Publication requires both the archive and its checksum
as uploaded release assets. Version transitions use `versions:set` with
`-DprocessAllModules=true` so the independently versioned Mermaid reactor module
advances with the host.

## Linear history and immutable release provenance

`protected-release-main-advance.yml` waits for the canonical verification and
all required PR checks before using `gh pr merge --rebase --match-head-commit`.
It does not bypass branch protection or change repository rules.

GitHub rebase merge creates new commit IDs. The release tag and its already-built
artifacts continue to identify the original, verified release commit. They are
never moved to a rewritten commit. Before and after merging, the Java
`check-release-history` command requires exactly two single-parent commits above
the recorded source main: the release and the next development snapshot. Both
complete Git trees must match the verified staging commits, including file
contents, paths and modes. The workflow also binds the resulting main SHA to
GitHub's recorded PR merge result; concurrent main changes abort publication.
The final CI, database, CodeQL and security gates run on that exact resulting
main SHA as before.

Resume accepts a tag already in main's ancestry, or its byte-identical release
tree rebased directly onto the tag's original parent in main's first-parent
history. A squash that removes the intermediate release state, a different
base, or changed release contents fails. This mapping preserves the published
source identity without claiming that GitHub retained the original commit IDs.
