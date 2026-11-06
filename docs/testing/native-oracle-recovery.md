# Oracle migration, restart and native recovery acceptance

The existing Maven `database-oracle` profile owns these tests through its
`*Oracle*IT` selector. No additional test runner or workflow selector is needed:

```sh
./mvnw -B -ntp -pl taxonomy-app -am verify -Pdatabase-oracle \
  -Dit.test=JgitStorageOracleMigrationIT,NativeInstallationOracleRecoveryIT,OracleHypothesisSessionColumnMigratorOracleIT
```

Java 25 and Docker with enough memory for Oracle Free and the application JVM are
required. The normal database matrix executes the whole Oracle profile, including
the existing provisioning, authenticated API and real-browser owners. A focused
command supplements that matrix and the canonical CI gate; it does not replace
them. No Oracle execution is asserted by this document.

## Exact migration boundary

`jgit-storage-hibernate-core` 0.12.0 supplies one Oracle fresh-schema migration,
`db/migration/jgit-storage-hibernate/core/oracle/V0_12_0__create_core_schema.sql`.
It requires native SQL `BOOLEAN` and uses the library's Oracle dialect. It is not
a forward migration for an earlier Hibernate-created Taxonomy installation. The
artifact contains no Oracle legacy-adoption stream or Oracle adoption constant.
The library owns its Core DDL; copying or adapting another vendor's migration
inside Taxonomy would create a second schema authority.

`JgitStorageOracleMigrationIT` runs that released SQL on actual Oracle, validates
the mapped Core entities, writes two original commits and a long Unicode branch,
checks the reflog, replays Flyway without changes, opens a second persistence
factory and reads the exact original objects before appending a new commit. Its
separate historical fixture verifies that Taxonomy refuses an unreviewed legacy
schema without changing pack bytes, reflog values or schema-history tables.

The existing `OracleHypothesisSessionColumnMigratorOracleIT` remains the owner for
historical hypothesis data, scope authority and each committed-DDL interruption.
Its deterministic JDBC counterpart provides fast local regression coverage.
Neither contract is duplicated by the native recovery fixture.

Oracle's application profile therefore retains its current Flyway setting.
Completing managed old-installation Core migration requires released upstream
Oracle adoption/forward assets, validated physical shapes and index contracts,
nontransactional interruption recovery, and real Oracle acceptance before
Taxonomy can select that stream. The fresh Core test alone cannot establish that
missing path. Oracle 19c is not certified by a native-BOOLEAN Oracle Free test.

## Application and native restore rehearsal

`NativeInstallationOracleRecoveryIT` reuses `NativeInstallationRecoveryDriver`,
the same complete application fixture as HSQL and PostgreSQL native recovery.
It creates disposable, separately passworded local users with only `CREATE
SESSION`, `CREATE TABLE`, `CREATE SEQUENCE` and finite tablespace quotas. Only the
new source fixture uses Hibernate `create`; every subsequent source and restored
application JVM uses `validate`. Before the first source process, the test runs
the released Oracle Core 0.12.0 SQL. The existing Core schema filter excludes
library-owned tables from application Hibernate creation, so the normal mapped
application and the released storage schema are exercised together.

Normal services save the real catalogue, portfolio/requirement history, a
test-authored saved analysis, source provenance and retained source bytes,
reviewed solution/product decisions, original SQL Git commits/branches, editor
checkpoints/journal/draft, preferences and an uploaded document template. A second
source JVM checks the same data and history without editing it.
The real working-copy owner enables collaboration through the authorized sharing
service. Every later process authenticates the retained actor through Spring's
credential verifier, checks the same stable principal and resolves the persisted
shared working copy. The opt-in must survive source restart and native restore.

With application writers stopped, the test captures the complete configured
filesystem root and invokes real Oracle `DBMS_DATAPUMP` schema export/import.
The import remaps into the separately provisioned target user and excludes user
creation. Every source table row, including BLOB/NCLOB content, is hashed and
compared with the imported target before application startup. Target database
password and restricted grants must remain unchanged. Source aliases, occupied
targets and replay into a populated target are rejected by the fixture before
import. These guards are test infrastructure, not a shipped Oracle recovery CLI.

Two target application JVMs verify saved data and original commits, undo, restart
and redo the retained draft, reconstruct the editor and search the catalogue.
Each process checks persisted admin authentication, rejects a wrong password and
reads the actual connected schema through the existing admin diagnostics endpoint.
No credentials or JDBC URL may appear in its diagnostics response. Source table
and file digests must remain unchanged after target edits and restarts.

The existing database workflow uploads `target/native-oracle-recovery/` process
and Data Pump logs. `result.json` is written only after every assertion passes;
it records the dump and filesystem manifest digests, version, scope and limits.
The private dump itself is disposable and remains inside the test container.

This rehearses trusted same-version Oracle Free schema recovery. It does not
introduce portable restore activation, a cross-vendor converter, an operator
backup command, atomic rollback of Oracle Data Pump, abrupt failure acceptance,
arbitrary pending-job replay, or Kubernetes volume/controller certification.
Native SQL restoration preserves application accounts, password hashes and
grants; it does not quarantine authority. External deployment secrets and network
settings still require a separately reviewed recovery environment.

Oracle's primary references describe the
[Data Pump API](https://docs.oracle.com/en/database/oracle/oracle-database/26/sutil/using-oracle-data-pump-api.html)
and [DBMS_DATAPUMP](https://docs.oracle.com/en/database/oracle/oracle-database/26/arpls/DBMS_DATAPUMP.html).
