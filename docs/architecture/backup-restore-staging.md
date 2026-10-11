# Portable restore staging contracts

This package supplies verified target wiring and read-only dataset/schema boundaries.
It does not activate an installation. Backup remains disabled by default until
the complete restore and acceptance packages have evidence.

`RestoreRepositoryAllocation` binds an explicitly approved target repository and
workspace key to a separately allocated physical storage name. Archive paths and
repository labels cannot allocate storage. `RestoreTargetGeneration.verified`
proves the active/staged DataSource destinations and the actual Hibernate factory
connection. `requireUnchanged` repeats those connection checks; durable generation,
owner content, schema and readiness proofs are separately necessary.

The initial physical-identity adapter supports local HSQLDB memory/file destinations.
Memory names are case-insensitive: actual connections with differently cased names
can access the same rows, so fingerprints normalize the name. Connection properties
do not create a different destination. File paths resolve existing real parents.
Different DataSource wrappers never suffice. The initial adapter requires a
different physical database, even when the default schema differs: a qualified
owner SQL statement or Hibernate default schema could otherwise address the active
schema. Remote HSQL and other database vendors fail closed pending tested physical
identity adapters. Public proofs contain vendor family and opaque destination hash,
catalog and schema, without JDBC URLs, accounts or credentials.

`GitRestoreImporter` is the host port for the workspace-owned importer. It receives
the complete verified archive, approved source-to-target allocations and the
verified generation. Its result distinguishes original provenance from actual
restored refs, working metadata and required target objects. Projected CURRENT and
SELECTED representations create new parentless target commits and document explicit
source-to-target mappings; only history representations preserve original Git IDs.
The host must verify complete repository coverage and owner readiness before use.

`RestoreArchiveRecords` reads only manifest-declared resources of their expected
owner. Compiled adapters supply the exact public DTO type; the archive cannot select
a class. Header schema/kind/profile, duplicate fields, unknown properties, scalar
coercion, missing creator fields, trailing tokens, record bytes, row limits and
checkpoints are enforced. Existing capture proofs without source-family metadata
remain readable, but cannot establish a database matrix combination implicitly.

`TargetSchemaEvidence` performs bounded read-only HSQL metadata and one-row probes.
It distinguishes an empty migrated schema from a partly populated or structurally
changed target and binds columns/defaults, indexes, foreign keys and CHECK constraints.
Views and triggers have no registered restore contract and are rejected. Every
table, column, index and constraint traversal checks cancellation; row probes and
constraint SQL have explicit limits/timeouts. Rollback cleanup preserves an active
failure and interruption. This evidence does not replace a versioned migration,
the durable journal or a complete business-data readiness proof.

`RestoreSchemaMigration.bootstrap` initializes a genuinely empty, separately
allocated local-HSQL database. It rejects any existing user table/view/sequence
before DDL, proves the physical identity of the connection used for all migrations,
and pins that connection through the checked-in business baseline and the existing
Principal, retained Catalogue Source and JGit Core versioned migrations. An unrelated
schema is never adopted. A failed partial migration needs a new empty generation;
it is not an empty target eligible for automatic adoption.

`openValidatedFactory` accepts the exact explicitly approved compiled entity model,
validates the versioned business migration and runs Hibernate `validate`. It never
uses `create` or `update`. JPA database action `none` must not be set alongside that
Hibernate mode: it overrides the requested validation, as the actual dropped-column
test demonstrated. Search indexing and entity callbacks are disabled for staging;
separate owner reconstruction and readiness are required before activation. Each
factory connection verifies its physical destination before being returned, so a
mutable DataSource cannot redirect later staging access to the active database.

The initial baseline covers the Task3A business model. New owner entities or field
revisions require reviewed versioned schema changes and actual validation before
their restore combination can be registered. A validated schema is not evidence
that its business datasets have been imported or that a database matrix combination
or production deployment can be activated.

## Typed source owner import

`ApplicationSourceRestoreImporter` consumes only the reviewed application schema1 source-artifact,
source-version, source-fragment and legacy-source-link records and their declared source files.
Preflight checks exact owner/file declarations, record kinds, duplicates, SQL field bounds,
version/artifact/fragment reference closure and fragment ancestry before it writes anything.
The in-memory inspection has a shared 100,000-record and 32MiB serialized-payload bound.

The actual write connection must match the witnessed isolated generation. New SQL identities
replace archive-local source IDs; version and fragment parent FKs use those explicit mappings.
V2 of the separate HSQL staging schema stores owner generation/archive/content-root bindings,
source-to-target IDs and row/file proofs in the same transaction as all imported source rows.
A reopened importer verifies those rows, inventories and files before returning the same maps;
an existing phase cannot be adopted by another archive or generation.

Target bytes use content-addressed names inside a fileKey-pinned SecureDirectoryStream.
Private temporary files are forced before atomic publication; ordinary cancellation removes
its own incomplete temporary file. The directory binding is rechecked and forced before SQL
commit. A process killed during an unpublished write can leave an unreferenced private partial
file in the inactive generation; it cannot be mistaken for verified content. Generation cleanup
must remove such staging debris before eventual activation. Target storage must support secure
directory access, file identities, atomic rename and directory/file force; unsupported storage
fails closed. This does not assert power-loss acceptance on every filesystem.

Unmapped legacy global requirement keys remain typed private JSON in the quarantine table,
with source references and digest evidence intact. They create no target-global or tenant link.
Owner bindings/mappings are restore runtime state; quarantined private primary data is not.
Full readiness still requires a reviewed quarantine export/preserve policy and every other
selected owner, the actual source/target database matrix and the durable activation workflow.
No public production restore route or feature enablement is introduced by this owner slice.

## Typed identity owner import

`RestoreIdentityImporter` requires a complete explicit source-principal to target-principal
and namespace allocation. Same display names, e-mail addresses and issuer subjects never
infer an allocation. Distinct source identities cannot merge into the same target principal
or scope. A quarantine allocation remains a separately named private target namespace.
All created target principals are disabled; no login binding, local account, role, membership,
capability grant or version grant is activated by the identity importer.

The reviewed principal, binding, account, role, grant and audit DTOs retain their source
values in private typed evidence. Source authority is explicitly inert, installation identity
is historical provenance, and password hashes are protected primary evidence only when the
verified archive declared encrypted secrets. The target installation identity is not replaced.
Evidence has a shared 100,000-record and 32MiB bound. Canonical UUID spelling is required at
the typed archive reader boundary; abbreviated, base64 and uppercase coercions are rejected.

V3 of the separate staging schema atomically journals generation/archive/approved-mapping
bindings, disabled principal allocations and evidence alongside the imported principal rows.
The fresh bootstrap runs business V1, the independently versioned Principal migration, then
remaining business migrations before Catalogue/JGit migrations. This preserves the real
principal FK rather than removing it to work around the version ordering.

The actual write connection must still match the witnessed isolated generation. Restart
verifies canonical source/target IDs, scopes, disabled status, exactly one owner row, exact
evidence/classification inventory and empty authority tables; changed mappings, generations
or target rows cannot adopt a completed owner. Private CLOB comparison uses a checkpointed
character stream and stops after the expected bounded record plus at most one extra character,
so a manipulated oversized target LOB cannot trigger unbounded string materialization.
Cancellation before commit rolls back the principal/mapping rows, preserving the primary
interruption even if rollback itself fails. A postcommit checkpoint permits recovery to reuse
the exact durable mapping without duplicate principals.

The private identity evidence is primary data that needs a reviewed portable quarantine
owner before full readiness. Workspace ownership must use these exact source-scope mappings;
archived runtime leases and grants must remain inactive in their respective owners. No full
P08 readiness, activation, cross-database acceptance or production restore route follows from
this identity slice alone. Backup remains disabled by default.
