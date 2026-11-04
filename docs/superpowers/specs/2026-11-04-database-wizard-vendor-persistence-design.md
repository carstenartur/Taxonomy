# Database setup across supported connection providers

## Authorized goal

Extend the existing optional database wizard to SQL Server and Oracle, integrate Rancher/Pod persistence guidance, and expose the real connected database identity and engine start in the existing administration system-information view. Continue PR #1374; do not operate a live installation or merge the PR.

## Design decisions

- One shared preparation contract, worker limit, response deadlines, cancellation/unknown handling and UI. PostgreSQL, SQL Server and Oracle own only vendor-specific validation/catalog/SQL/verification.
- Inspection is read-only. Reviewed CREATE/REUSE actions are sent back on explicit confirmation and compared under a vendor session lock. Existing objects are never modified or removed. New resources can require multiple non-atomic statements; partial/unknown results retain truthful resource states and require inspection before retry.
- PostgreSQL retains its verified restricted login/owned database flow. SQL Server prepares a dedicated SQL login and database, assigning ownership only on the newly created database. Oracle prepares a local user/schema with a finite explicit tablespace quota in an existing writable PDB service; it creates no instance/PDB/tablespace.
- Administrator passwords are transient, separated from runtime application credentials, redacted in DTOs/errors, excluded from configuration exports, and cleared after success/close. Bound request parsing, ADMIN/CSRF/no-store protection remain.
- SQL Server and Oracle remain compatibility profiles. Resource creation and login success do not certify schema readiness or production migrations. The wizard prominently states that fresh compatibility targets need controlled schema initialization; permanent deployment exports retain validate. No automatic cross-vendor data transfer is introduced.
- Extend the existing help route in EN/DE. Explain database-backed preferences, DB storage, application files, deployment Secrets/ConfigMaps and their different lifetimes. Mounting a PVC does not persist memory HSQLDB. Existing volatile data must be preserved before restarting; no unsupported portable restore is advertised.
- Extend existing ADMIN-only on-demand system information. Use active-connection queries for effective user, database/container and schema. Engine start is separate from JVM start; PG yields an absolute timestamp, SQL Server/Oracle server-local time, HSQL unsupported. Optional missing privileges never suppress other metadata.
- Repair Oracle chart/Compose compatibility through existing deployment controls. Oracle service URLs must work without accepting credential-bearing JDBC URLs.

## Validation and non-goals

Preserve PostgreSQL uncertainty/reuse/secret tests; add vendor unit and real-container acceptance, generic controller/OpenAPI and EN/DE native wizard scenarios, chart render rejection/acceptance, active-database metadata tests. Run local JVM tests with existing supported runtime; real browsers/containers execute in CI. Do not bypass local browser/Docker restrictions. No automatic deployments, DB upgrades, credential rotation, live migration or new production-compatibility certification.

## Existing CI evidence correction

Previous head passed all individual verification jobs but the aggregate rejected an extra database-setup-guided directory under its exact shard inventory. Move supplemental wizard screenshots outside target/ui-verification; retain the strict shard verifier unchanged.
