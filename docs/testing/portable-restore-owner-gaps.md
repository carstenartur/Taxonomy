# Portable restore owner coverage and native recovery boundary

Inspection on 2026-10-29 found no complete production portable restore matrix.
`RestoreSupportRegistry.closed()` emits owner/matrix blockers and always rejects
activation. It must remain closed. The current implementation can rehearse trusted
same-version native [HSQL](native-installation-recovery.md) and
[PostgreSQL 16](native-postgresql-recovery.md) recovery without claiming portable
owner completion.

The source of truth is
`taxonomy-app/src/main/resources/backup/coverage-inventory.tsv`. At this inspection
it contains 80 `PORTABLE_PRIMARY` categories across seven owners, plus Git,
rebuildable, transient and external categories. Export coverage does not prove
import readiness. In particular, a captured row or a parser for a payload is not
an operational restore owner.

| Owner | Portable-primary categories | Implemented staged pieces | Missing complete owner proof |
| --- | ---: | --- | --- |
| application | 11 | `RestoreIdentityImporter` and `ApplicationSourceRestoreImporter` | Compose all application preferences/configuration/source owners, validate relocated sources, prove inactive authority and nonrestored secrets in the complete target |
| workspace | 10 | `WorkspaceRestoreImporter`, `ApplicationGitHistoryRestoreImporter`, `GitRestoreImporter` and typed repository allocations | Compose routing, journal/reflog/history and projections into a durable target generation; verify all retained contexts and restart/retry at application level |
| analysis | 9 | Module `ClusterAnalysisBackupRestorer` and `AnalysisContinuationBackupRestorer` helpers | Complete owner registration, identity/reference remapping and interrupted-only runtime behavior across all analysis records without dispatch |
| portfolio | 25 | Existing capture/portable Git services | Full portfolio, requirement/version/snapshot, solution/product, mapping and reformulation import; validate every FK/business identity and do not revive jobs/leases |
| knowledge | 11 | Capture and reference selectors | Taxonomy, relations, evidence/proposals/hypotheses/coverage and recovery journal import with exact catalogue/provenance binding and rebuilt readiness |
| architecture | 1 | Capture | Architecture DSL owner import bound to target repositories and retained model identities |
| interop | 13 | Capture | Connections, external mappings, operation/checkpoint/publication/attempt/item records with inactive external authority and no automatic publication |

The Git-primary entries separately include workspace objects/refs/reflogs,
application preferences, document templates and portfolio history. An available
Git-history importer does not establish the application owner behavior of each
repository. Rebuildable projections/search must be reconstructed and validated;
old readiness flags are never sufficient. Transient dispatch bookkeeping,
credentials/sessions, leases and external dependencies have explicit inventory
rules and cannot simply be copied into an active portable installation.

`RestorePlanner` has typed principal/repository mapping checks, source-family
witnesses, archive/resource checks and read-only diagnostics. Its known-resource
coverage remains narrower than a complete application archive. `TargetDatabaseIdentity`
only proves local HSQL memory/file destinations; PostgreSQL physical staging is
not enabled there. `RestoreSchemaMigration` supplies a staged HSQL schema, not a
PostgreSQL owner migration contract. The target-generation/schema machinery does
not implement durable complete-owner orchestration, restartable all-owner import,
secret/grant quarantine, validated activation and application acceptance.

Enabling portable readiness therefore requires all these owners and dependencies
together for one explicitly supported version/database/profile, followed by
negative mapping/reference tests, crash/retry/idempotency tests and a real
application rehearsal. Empty fixtures, enum additions, schema creation, successful
capture or native database backup are not substitutes. Arbitrary cross-version
or cross-vendor migration is outside this native recovery work.

Native recovery avoids lossy reconstruction by retaining the installation's SQL
rows and filesystem content. It preserves business and Git identities and stored
application authority. PostgreSQL restores object ownership into the deliberately
selected target database owner and does not restore cluster roles/SQL ACLs.
Those are native operational semantics, not portable identity inactivation.
