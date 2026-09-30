# Portable backup capture boundary

This is the P03 implementation of issue #1146. The public export/restore workflows
and module-specific portable schemas are separate work packages. The feature is
disabled by default. Enabling the writer boundary alone does not expose a complete
backup product or declare a database roundtrip supported.

## Deployment contract

Every application instance sharing the installation must run the same supported
version with `taxonomy.backup.enabled=true`. Do not combine guarded and unguarded
instances or let external programs write the database during capture. An external
Git service, identity provider, file store or deployment directory is not covered
by the database gate; its adapter must declare an external prerequisite or supply
an independently authorized capture. No global atomicity is claimed for such a
service. The capture inventory must reject an unsupported primary-data source.

The initial implementation pauses the entire controlled installation even when
the requested export scope is smaller. A single ordered gate avoids deadlocks
between overlapping repository/workspace selections. Reads remain possible;
new writes receive a maintenance error and can be retried after capture. This
version does not promise uninterrupted writes or silent retries of external work.

| Writer family | Controlled boundary |
| --- | --- |
| Editor semantic operations, checkpoints and version/integration actions | Outer `BackupWriteBarrier` section across journal and Git transactions, plus JDBC commit fence |
| Git objects, refs and reflogs, including synchronization and templates | Shared application Hibernate factory over the guarded datasource |
| Portfolio, analysis completion/recovery, drafts and reformulation | Application ORM/native transactions over that same datasource |
| Knowledge, source content and projections | Application ORM/native transactions over that same datasource |
| Preferences and document-template content | Database-backed Git through the shared factory |
| Identities, capability grants, ACL changes and their audit | Guarded JDBC transactions, including independent `REQUIRES_NEW` transactions |
| WebDAV and interoperability persistence | Shared ORM/Git boundaries; external publication itself is an external dependency |
| Schema and bootstrap changes | Exclusive, non-expiring startup hold through application runners |

The enabled Spring configuration rejects additional/unrecognized datasource beans.
`BackupWriterCoverageIT` starts the full application and checks the common
datasource, ORM/Git factory composition, a real preferences Git mutation and a
native identity-table mutation. `BackupConsistencyIT` runs two independent
Hibernate/Git nodes, pauses a checkpoint after its ref update, and attempts an
analysis completion while the capture is draining it.

## Lease and fencing protocol

`backup_schema_history` owns the versioned coordination schema. These live-process
rows are transient in a portable backup and must not be reactivated by restore.
An ordinary writer registers a bounded durable lease against the current
generation. Nested sections on the same thread retain one lease until their last
holder closes. An already-running writer may complete while maintenance drains
it; new writers cannot enter.

Immediately before the actual JDBC commit, the writer locks the global gate row
**on that same connection** and checks its generation and lease. The lock remains
held through the commit. Checking in an unrelated transaction would leave a race
between the check and publication. Autocommit mutations therefore use short
explicit transactions. A timed-out writer rolls back even after another instance
has resumed writes. Old cleanup cannot release a newer maintenance owner's hold.

Lease expiry uses the database wall clock: PostgreSQL `clock_timestamp()`, SQL
Server `sysdatetimeoffset()`, Oracle `systimestamp`, MySQL/MariaDB UTC time and
HSQL statement time. PostgreSQL transaction-start timestamps cannot fence an old
transaction correctly. Offset-aware reads avoid dependence on an instance's JVM
timezone. See the [PostgreSQL datetime documentation](https://www.postgresql.org/docs/current/functions-datetime.html)
and [HSQL datetime functions](https://www.hsqldb.org/doc/guide/builtinfunctions-chapt.html).

Coordination has a separate two-connection Hikari pool using the application's
connection configuration. It must not compete for a pool whose last connections
are held by transactions waiting for their leases. Monitoring still receives
read-only application pool metadata; unwrapping the guarded datasource or its
JDBC handles cannot expose a writable underlying connection. Result-set updates
and writes through LOB locators are rejected. Runtime statements are restricted
to the application's transactional SQL; native procedures, SQL transaction
commands, multi-statement strings and runtime DDL are unsupported.

Defaults are 300 seconds per writer lease and 30 seconds to drain writers during
startup (`taxonomy.backup.writer-lease-seconds` and
`taxonomy.backup.startup-wait-seconds`). Long operations must finish within their
lease or recover from their durable application journal. Capture renews its own
maintenance lease while copying and checks it again before returning any result.

## Startup and recovery

On engines with implicit DDL commits, a timestamp lease cannot stop an already
executing migration from committing after expiry. Startup therefore takes a
non-expiring exclusive hold. Its own initialization code can use both the pure
writer port and guarded JDBC. The hold is released only on `ApplicationReadyEvent`,
after migration runners have finished. The minimal migration startup and the
complete application are both integration-tested.

A failed/interrupted startup intentionally leaves the installation blocked.
Operator recovery must stop/fence that process and validate migration state before
releasing the hold; merely changing an expiry timestamp is not a recovery protocol.
The restore/operator CLI in P08 must own this recovery step. Do not manually clear
a hold while the old process could still execute DDL.

## Durable captured state

`BackupSnapshotCoordinator.capture(AuthorizedBackupRequest)` rechecks authority,
acquires maintenance, inventories the exact selection, and requires all declared
component adapters and schema versions. Contributors stream into a private
staging directory. Each entry has a portable normalized path, bounded length and
SHA-256. The coordinator retains only bounded buffers and entry metadata. It
records repository/ref/semantic-revision evidence and does not create a source
backup commit.

The complete manifest and capture proof are written and flushed before an atomic
directory rename. The maintenance generation is checked again before returning
`CapturedBackup`; failure or cancellation removes both partial and newly renamed
output. The immutable metadata can be reopened after restart independently of
the source. Entry streams check length and digest, so later spool corruption
cannot become a successfully verified archive. Job publication and download
reauthorization are P04 responsibilities.

Initial per-capture defaults are 10 GiB total, 2 GiB per entry, 10,000 entries and
30 minutes. The manifest is independently bounded to 4 MiB. These are safety
limits, not measured throughput claims. Secret-bearing staging requires an explicitly
supplied standard streaming protection adapter; plaintext temporary secrets are
not supported. P04 supplies Tink protection for both spool entries and the
manifest, with the keyset kept outside the spool/archive. See the
[archive protection decision](backup-archive-protection.md).
