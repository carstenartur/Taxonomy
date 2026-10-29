# Native cold HSQL installation recovery

`NativeInstallationRecoveryTest` rehearses the documented stopped-writer HSQL
deployment using three complete application JVMs and a new, isolated data root.
It is an ordinary Maven-owned test in the existing recovery shard. It uses no
portable owner importer and does not change `RestoreSupportRegistry.closed()`.

## Reproduce the application rehearsal

```sh
./mvnw -B -ntp -pl taxonomy-app -am test \
  -Dtest=NativeInstallationSnapshotTest,NativeInstallationRecoveryTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Java 25, Maven dependencies and local loopback sockets are required. No Docker,
browser, model download or configured model provider is needed for this test.
This focused command is supplemental; the canonical gate remains
`./mvnw -B verify -Pci -DrunOnnxTests=true` with its documented prerequisites.

The seed JVM uses normal `TaxonomyApplication` startup, the real catalogue,
file-backed HSQL cached tables, filesystem Lucene, and application services to
save a project, two requirement versions, source provenance, a completed analysis,
and a reviewed solution/product decision. The analysis is explicitly authored
test evidence stored through `PortfolioAnalysisPersistenceService`; no inference
or language-quality claim follows from it. Real SQL-backed Git contains two editor
checkpoints, an additional branch, the subsequent uncheckpointed edit, preferences,
and an uploaded document template.

After graceful application shutdown and HSQL closure, the native helper captures
the entire configured data root, including retained source files and Lucene. The
test checks every restored byte before startup. A second JVM reads the saved
portfolio/source/snapshot/template data and original Git objects through actual
services and undoes the draft. A third JVM verifies the persisted undo and redoes
it, checks editor reconstruction and catalogue search, and checks no external
work is queued. Both restored JVMs run with mock model transport, empty remote
provider credentials and local analysis transport. The original stopped data
root must remain byte-identical after all target changes.

Machine-readable results and process logs are under
`taxonomy-app/target/native-installation-recovery/`. `result.json` is written only
after every phase succeeds; it names the build version, native database family,
manifest digest, byte/file counts, covered data and limitations. The recorded
scope is the populated fixture, not every possible business owner or production
dataset. Physical container-volume handling, abrupt power loss, Windows filesystems,
cross-version upgrade and cross-database restore remain separate acceptance.

The local 2026-10-29 run on Java 25 / Linux passed the seven helper/CLI cases and
the full application rehearsal without skips. Application `1.5.3-SNAPSHOT`
captured 58 files totaling 106,257,711 bytes; the source stayed unchanged after
both restored starts. This historical observation does not replace a fresh
candidate-head Maven run.

## Existing tooling: capture, verify and isolated restore

The dependency-free `taxonomy-tooling` executable now has `native-installation`
operations for this cold HSQL data-root contract. Build it through its normal Maven
module, then use the JAR for the **same application version** being rehearsed:

```sh
./mvnw -B -ntp -pl taxonomy-tooling -am package
java -jar taxonomy-tooling/target/taxonomy-tooling-<version>.jar native-installation capture \
  --source /private/stopped-data --snapshot /private/cold-snapshot \
  --version <version> --writers-stopped true
java -jar taxonomy-tooling/target/taxonomy-tooling-<version>.jar native-installation verify \
  --snapshot /private/cold-snapshot --version <version>
java -jar taxonomy-tooling/target/taxonomy-tooling-<version>.jar native-installation restore \
  --source /private/stopped-data --snapshot /private/cold-snapshot \
  --target /private/new-restored-data --version <version> --writers-stopped true
```

These are operator commands, not an online backup endpoint. Stop **every** writer
first and use a private parent directory owned exclusively by the operator. Both
snapshot and target destinations must be absent. All configured persistent roots
must be below the source data root; separately mounted source content, external
secrets and databases are not silently included. The helper cannot establish
that another host/container has stopped, and an absent HSQL lock file alone is
not proof of that. The explicit acknowledgement records the operator precondition.
The version argument must come from the stopped deployment's build/image metadata;
the file helper binds that declaration but does not infer a version from SQL bytes.

For restore, `--source` remains the original installation path used to prevent
overlap, even when that directory or its volume has been lost. Restore reads the
verified snapshot and does not require the original data to survive. Existing
ancestors must be directories without symlinks. If the original root still
exists, it must be a directory, and an observed `.lck` below it rejects the
operation before staging even with `--writers-stopped true`. The acknowledgement
remains required because the absence of a lock cannot prove that every writer
has stopped.

The snapshot is a directory containing `manifest.json` and `data/`, not a
`.taxbackup` archive. The manifest lists every regular file's length/SHA-256 and
all directories. Verification rejects missing, extra or changed bytes, traversal,
symbolic links, unsupported files, active HSQL lock files and application-version
mismatch. Path checks inspect supplied components before normalization, including
symlink components that would otherwise disappear in `alias/..` spellings.
Limits are 100,000 entries, 64 GiB total content, directory depth 64 and
a 16 MiB manifest. Copying uses bounded buffers. Private sibling staging precedes
publication; an ordinary interruption removes its incomplete staging. A forcibly
killed process can leave a `.native-recovery-*` directory, which is never the
published snapshot/target and must be inspected and removed while all writers
remain stopped. Keep the destination parent on one local filesystem.
On POSIX filesystems, published data roots are owner-only and copied files have
owner read/write permissions. Other platforms require an equivalent private ACL;
this local evidence does not certify those platforms.

Retain the printed manifest SHA-256 separately and compare it when transferring
snapshots. These digests detect accidental corruption; they do not authenticate
an attacker-controlled replacement manifest. Native snapshots contain the entire
installation's private data and persisted identity/configuration. Store them under
the deployment's backup access/encryption policy. Native restore preserves these
values; it does not quarantine authority as portable restore would. Rehearse with
independent network/domain settings and external calls disabled before starting
a recovered deployment. The automated fixture contains no remote credentials or
unfinished external jobs and does not establish safe replay of arbitrary jobs.

Absolute source locators stored in the database retain their original paths.
The automated fixture uses the relative locator `requirements.txt`. If a recovered
installation uses a different mount path, an unchanged absolute locator can fall
outside the configured content root and make its source unavailable. Preserve
the original mount path inside an isolated environment or use a separately
reviewed relocation plan for such installations. Native byte copying does not
remap database locators.

The helper never starts an application, changes the live data path or replaces an
existing target. Point only a separate same-version application at the verified
target, then follow the service-level checks above. Keep the snapshot and any
surviving original source until the recovery is accepted.
