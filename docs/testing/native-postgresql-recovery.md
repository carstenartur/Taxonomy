# Native PostgreSQL 16 installation recovery

The existing `taxonomy-tooling` executable provides an operator-controlled,
stopped-writer recovery path for a trusted installation running the **same
application version and PostgreSQL 16**. It captures a custom-format logical
PostgreSQL dump and every regular file below the configured application data
root. It restores into a separately created empty database and an absent data
root. It does not start an application or change a deployment's database URL.

This is native disaster recovery. Persisted application identities, password
hashes, grants, configuration and job states are preserved. It is not the
portable importer's inactive-identity model, an untrusted archive import, a
cross-database converter, or a cross-version upgrade. The portable support
registry remains closed; see [the owner gaps](portable-restore-owner-gaps.md).

## Preconditions and scope

- Stop all application replicas, analysis workers and other writers before
  capture. Keep the source stopped until the capture completes. The tooling
  requires `--writers-stopped true` and rejects observed additional database
  connections; neither check establishes that another controller cannot restart
  a writer. Freeze such controllers for the maintenance window.
- Use a private parent directory controlled exclusively by the operator.
  Snapshot and restored data-root paths must be absent, nonoverlapping and free
  of symbolic links. All persistent filesystem content must be included below
  the source root. The tool rejects symlinks and raw PostgreSQL cluster files;
  it does not copy a live PostgreSQL data directory.
- Use PostgreSQL 16 `pg_dump`, `pg_restore` and `psql` from a trusted local bin
  directory. The tool checks each client's reported major version before capture
  or target planning. Configure libpq service aliases and a private password file using
  `PGSERVICEFILE` and `PGPASSFILE`. Do not put passwords in CLI arguments. The
  native tool suppresses raw subprocess output in its own logs.
- The recovery operator needs backup/restore privileges and permission to read
  `pg_control_system()`. Physical isolation is witnessed from the server's system
  identifier and `current_database()`, not inferred from different hostnames.
  A different database in the same cluster is accepted; another alias of the
  source database is rejected. This bounded path accepts database names beginning
  with an ASCII letter/underscore followed by ASCII letters, digits, underscores
  or hyphens (at most 63 characters); other quoted database names are rejected. Built-in maintenance databases are never targets.
- Create a dedicated target database from `template0`, with the intended new
  database owner, before planning. Keep it exclusive to this recovery operation.
  Custom schemas, relations, functions, types, extensions other than `plpgsql`,
  large objects, user-schema collations, foreign servers, publications and
  subscriptions make the target nonempty and cause rejection. These enumerated
  catalog checks run during planning and again inside the restore transaction.
  They are a guard against occupied targets, not a complete comparison with
  `template0`: other catalog metadata, database settings and ACL changes are not
  comprehensively compared. The operator must provision a fresh, unmodified
  `template0` target; an older database that merely passes these checks is not a
  supported substitute. There is no `--clean`, merge, drop or overwrite operation.
- Retain the source application's exact image/build version and compatible
  configuration. The `--version` declaration binds the snapshot and must match
  during verification and restoration; it is not inferred from SQL rows. Reuse
  the exact source artifact/image digest. Identical `SNAPSHOT` labels can describe
  different builds; matching a label does not prove build compatibility.

A database dump includes persisted SQL-backed Git objects and business history.
It does not include cluster roles, passwords for PostgreSQL roles, tablespace
storage, Kubernetes Secrets, external object stores or separately mounted files.
Restore deliberately uses the target operator as object owner, omits source SQL
ACLs and places objects in the target's default tablespace. This does **not**
disable application-level accounts or grants stored in business tables. Custom
role-dependent policies/extensions require their own reviewed deployment plan;
this path does not certify them.

A trusted PostgreSQL dump can contain executable SQL. A matching manifest digest
proves byte integrity, not that an unknown publisher's dump is safe to execute.
Use only backups of a trusted installation, retain the printed SHA-256 separately,
and apply the deployment's access and encryption policy to the entire snapshot.

## Capture, inspect and restore

Build the same-version tooling with its normal Maven owner:

```sh
./mvnw -B -ntp -pl taxonomy-tooling -am package
```

Set `TOOL_JAR`, `APP_VERSION`, `PG_BIN`, `PGSERVICEFILE` and `PGPASSFILE` in the
operator environment. The examples assume service aliases `source_installation`
and `recovery_target` already resolve to the stopped source and the new empty
target respectively. Service files contain connection configuration; password
files need owner-only access. These commands contain no credentials:

```sh
java -jar "$TOOL_JAR" native-installation postgres-capture \
  --source /private/stopped-data --snapshot /private/postgres-snapshot \
  --version "$APP_VERSION" --pg-bin "$PG_BIN" \
  --service source_installation --writers-stopped true

java -jar "$TOOL_JAR" native-installation verify \
  --snapshot /private/postgres-snapshot --version "$APP_VERSION"

java -jar "$TOOL_JAR" native-installation postgres-plan \
  --source /private/stopped-data --snapshot /private/postgres-snapshot \
  --target /private/recovered-data --version "$APP_VERSION" \
  --pg-bin "$PG_BIN" --service recovery_target --writers-stopped true

java -jar "$TOOL_JAR" native-installation postgres-restore \
  --source /private/stopped-data --snapshot /private/postgres-snapshot \
  --target /private/recovered-data --version "$APP_VERSION" \
  --pg-bin "$PG_BIN" --service recovery_target --writers-stopped true
```

`postgres-capture` never writes the source data root. It stages a bounded copy,
creates `database.dump` and `database-source.json` within that private staging,
checks the custom archive with `pg_restore --list`, rechecks source identity and
connections, then publishes the existing native manifest/data directory format.
These two names are reserved at the source root. The manifest covers the dump,
source database identity and filesystem bytes together.

`verify` checks every byte and version without a database connection.
`postgres-plan` additionally parses the archive's table of contents and validates
the live target identity, emptiness and lack of other connections. It does not
write the database or publish a target directory. It uses a temporary private
working directory and removes it on return. This is a preflight, not an execution
of SQL and not application acceptance.

`postgres-restore` repeats the checks. The transaction itself also verifies its
actual server/database identity, major version, exclusive connection and empty
schema before the first imported statement; a changed service binding cannot
redirect a preflight-approved restore silently. `pg_restore --single-transaction` generates SQL into private staging.
Both the dump and generated SQL output are pre-created by the host with owner-only
read/write permissions on POSIX filesystems. A privileged container client can
truncate/write them without changing their host ownership; the host can then
read the dump and edit the SQL envelope without granting other users access.

Generating SQL with `--single-transaction` avoids separate large-object transactions,
whose commits would otherwise escape an
outer psql transaction. The tool verifies the generated PostgreSQL 16 header/footer
and replaces only that generated outer `BEGIN`/`COMMIT` with whitespace, preserving
all object payload bytes. Unknown envelopes are rejected before any database
write. `psql --single-transaction` then applies the SQL and a native recovery receipt
in the same transaction, with `ON_ERROR_STOP=1`. Only after database success does
the native helper publish the isolated filesystem target. The receipt is in
`taxonomy_native_recovery.receipt`; it binds the manifest hash and source/target
physical database identities. Source receipt schemas are excluded from new dumps.

The shared native limits are 100,000 entries, 64 GiB of content, depth 64 and a
16 MiB manifest. Each PostgreSQL subprocess is limited to ten minutes, 16 MiB of
captured client output and 64 GiB of generated dump/SQL. Connections default to a
10-second connect timeout. Session statement/lock defaults are supplied, but dump
SQL can reset those settings; the ten-minute subprocess limit remains authoritative.
An installation
exceeding these bounds needs a separately reviewed recovery method; bounds are
not silently increased. Interrupted clients are terminated and unpublished
staging is removed where the process can clean up.

## Interruption and retry

A failed SQL transaction leaves the empty target available for a fresh attempt.
If the database transaction commits but filesystem publication fails, keep the
target database inactive. Inspect its native receipt and compare its manifest
hash and physical target identity with the verified snapshot. The error reports
this phase explicitly. Do not rerun database restore into the committed database;
nonempty targets are always rejected.

After that explicit inspection, publish only the verified files:

```sh
java -jar "$TOOL_JAR" native-installation resume \
  --source /private/stopped-data --snapshot /private/postgres-snapshot \
  --target /private/recovered-data --version "$APP_VERSION" --writers-stopped true
```

The receipt attributes the original commit; it does not attest that database rows
remain unchanged afterward. If commit status or subsequent database activity is
uncertain, create another empty isolated database and a new filesystem target,
then repeat the complete restore from the verified snapshot. Never erase an
uncertain target to make a retry pass. A repeated completed `postgres-restore`
rejects the populated target; it does not claim an idempotent database result.
Only the file publication operation has automatic exact-content recognition.

The file `resume` operation accepts an absent target or an existing target whose
complete byte inventory already matches the snapshot. It never merges or
rewrites an existing target. A target changed by an application, extra files or
an observed HSQL lock causes rejection. This operation does not inspect or
repair PostgreSQL; it is authorized only after the operator has established the
committed receipt, confirmed exclusive custody of the target since commit, and
accepted the need for fresh application verification. A lost client connection can make commit status uncertain:
inspect the receipt before retrying. A forcibly killed process may leave private
staging; inspect and remove only the abandoned staging while writers stay stopped.

Before a later capture of the recovered application root, move its extracted
`database.dump` and `database-source.json` back into private backup storage after
acceptance. The capture tool rejects these reserved input names so a stale dump
cannot silently become part of a new recovery generation.

## Application rehearsal and Rancher

Start a separate application at the original application version, pointing to
only the target database and files. Keep external network calls, workers,
providers and integration schedulers disabled or isolated before its first boot.
Native database restoration retains pending jobs and authority; it cannot assert
that arbitrary production jobs are safe to replay. Supply independently reviewed
target credentials and network/domain settings, preserving required source mount
paths. Absolute source locators in SQL are not rewritten by native recovery.

Check actual saved projects, requirement versions, retained analyses, selected
solutions/products, source content and Git history through application services.
Exercise an edit/undo, restart, then redo/reopen and verify catalogue search. A
successful `pg_restore`, schema validation or readiness endpoint alone is not the
application proof. Keep both the snapshot and surviving source until the drill is
accepted. Restore does not switch traffic automatically.

For Rancher/Helm, record the release, exact image digest, database endpoint,
replica/worker counts, PVCs/mount paths and secret references before maintenance.
Suspend reconcilers or autoscalers that could restore writers, stop all app and
worker Pods, and use an operator Job with the appropriate PostgreSQL 16 clients
and private filesystem mounts to capture the same database/filesystem generation.
The chart manages application resources, not a database backup system. Restore
first in an isolated namespace or equivalent environment with a fresh database,
new PVCs and reviewed outbound-network restrictions. Provision secrets separately;
do not accidentally reference the source database's writable service. Rehearse
through the actual restored application before any explicit traffic switch.
A Helm rollback changes application resources and image, not a migrated database.
Kubernetes controller/PVC behavior needs real cluster evidence in addition to the
local database/application rehearsal below.

## Maven-owned acceptance

`NativeInstallationPostgresRecoveryIT` belongs to the existing
`database-postgres` owner (`*Postgres*IT`). By default it uses PostgreSQL 16 in
Testcontainers and invokes the real clients through Docker-exec wrappers. It
uses the existing full application driver for seed, undo and redo, so its
business-data assertions match the HSQL native rehearsal. It checks the source
alias and populated-target rejection, verifies original source files remain
unchanged, compares every public table's row digest immediately after SQL restore
and checks the source table digests again after target edits/restarts. A deliberately injected
late SQL error proves that imported tables, explicit PostgreSQL large-object
bytes and the receipt roll back, followed
by a successful retry. A changed committed target is then rejected even when the
requested filesystem target is absent, preserving the later change. Command logs
have unique step numbers. The test writes `target/native-postgres-recovery/result.json` only on full
success. Process logs and manifest evidence remain under that directory.

```sh
./mvnw -B -ntp -pl taxonomy-app -am verify -Pdatabase-postgres \
  -Dit.test=NativeInstallationPostgresRecoveryIT
```

For an explicitly provisioned isolated local PostgreSQL 16 server, the test also
accepts `-Dtaxonomy.recovery.postgres.config=/private/test-server.json`. That
private JSON has `source_jdbc_url`, `user`, `password_file` and `bin` fields; the
test creates and later deletes uniquely named databases in that test server.
`password_file` is a private local path, not an inline password. PostgreSQL shared
libraries must be available through the normal runtime search path. This mode
requires loopback sockets but no Docker and runs the same application assertions.

The fixture contains test-authored completed analyses and no configured remote
credentials or unfinished external work. Its passing result proves its recorded
scope, not every production owner's state or a human model-quality judgment.
The canonical gate remains `./mvnw -B verify -Pci -DrunOnnxTests=true` plus the
existing applicable database, filesystem and delivery owners.

PostgreSQL references: [pg_dump](https://www.postgresql.org/docs/16/app-pgdump.html),
[pg_restore](https://www.postgresql.org/docs/16/app-pgrestore.html),
[logical backup](https://www.postgresql.org/docs/16/backup-dump.html).
