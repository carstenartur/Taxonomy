# Guided database setup

## Intent and evidence

The administrator could reach PostgreSQL with TLS unchecked, but the wizard hid the successful login below several unrelated caveats. With TLS enabled, a green badge merely described the selected setting while the real connection failed. A fresh official PostgreSQL container initialized only with `POSTGRES_PASSWORD` also has neither the suggested `taxonomy_app` login nor the suggested `taxonomy` database. The user requests an ergonomic overhaul and an optional way to create the database.

## Approach

Extend the existing three-stage administrator dialog, shared transport and deployment exporter. A wording-only patch leaves the missing database/account workflow unresolved; replacing the wizard or changing the active datasource at runtime would introduce a second configuration mechanism. The chosen implementation keeps the existing deployment model and adds explicit PostgreSQL provisioning.

This is an implementation decision based on the user's request, not a claim that the user approved particular API names or visual details.

## User flow

1. Choose the database engine and either **Connect an existing database** or, for PostgreSQL, **Create a database and application account**. Explain that PostgreSQL must already be running and reachable through a Kubernetes Service.
2. Enter server, application database/account and transport settings. In create mode enter separate server-administrator credentials, default maintenance database/user `postgres`, and a new application password. A compact help text explains the official container's initial account. TLS stays explicit and verified by default; no automatic downgrade.
3. Existing mode: test connection, see one prominent success/failure result with actionable diagnosis, then prepare deployment configuration. Create mode: inspect the server and names without writing, show the exact database/account operations, then explicitly create/reuse them. Only the explicit creation button performs writes. The resulting application login is checked before reporting completion.
4. Export step: show that configuration is prepared, then give a short numbered Rancher/deployment handoff: put the application password in the deployment Secret, apply the displayed settings and restart Taxonomy. The wizard does not claim that test success changed the running datasource, migrated records or completed schema migrations.

## Result hierarchy and accessibility

- A single primary result precedes secondary details and says what happened and what to do next.
- Connection failures distinguish known DNS, refused connection, timeout, TLS/certificate, login, missing database and permission cases. Unknown failures remain explicitly unknown. Never show raw driver messages or credentials.
- A selected TLS setting is not a successful test. Plaintext is one clear transport warning, not duplicate warning rows. Informational scope/deployment notes live in an expandable detail section.
- Changing connection inputs clears stale results and exports. Reopening keeps non-secret choices for the current page session and clears passwords. Every manual test click initiates a fresh request.
- During provisioning disable edits and navigation; browser cancellation is not represented as rollback. Bound the full decoded response, including a stalled body after headers (75 seconds for creation, 25 seconds for inspection). If the creation response is lost or late, say the outcome is uncertain and offer inspection before another write; a late reply must not overwrite that outcome.
- Preserve native dialog focus handling, keyboard access, readable live status, responsive layout and complete English/German localization.

## PostgreSQL provisioning boundary

Use separate `PostgresProvisioningService` with `inspect(ProvisionSettings)` and `provision(ProvisionSettings)`. Input contains `target: ConnectionSettings`, `maintenanceDatabase`, `adminUsername`, `adminPassword`, `confirmed`, and nullable `expectedRole` / `expectedDatabase` actions. Inspection does not require expected actions. Confirmed provisioning requires both expected actions to be `CREATE` or `REUSE`, reflecting the reviewed inspection result. Target and administrator passwords are write-only and input `toString()` is redacted. Routes are `POST /api/admin/database-setup/postgres/inspect` and `/postgres/provision`, under existing ADMIN and CSRF rules, bounded strict JSON and no-store responses.

Return `ProvisionResult(outcome, role, database, finding)`. Outcomes: `READY`, `COMPLETE`, `CONFLICT`, `FAILED`, `PARTIAL`, `UNKNOWN`, `BUSY`. Resource states: `CREATE`, `REUSE`, `CREATED`, `REUSED`, `NOT_CREATED`, `UNKNOWN`. `finding` contains a fixed safe code/message, never credentials or SQL. Inspection can return READY with CREATE/REUSE; only successful post-provision application verification yields COMPLETE.

Validate PostgreSQL creation names as ASCII `[a-z_][a-z0-9_]{0,62}`, exclude system names and `pg_` prefixes. Quote identifiers and parameterize catalog reads. Require PostgreSQL 15 or later for this optional creation path. Inspect server writability and administrative capabilities before writes. The fresh Docker image's `postgres` superuser is supported. This implementation requires a database superuser to create a new application role: CREATE ROLE and CREATE DATABASE privileges alone do not establish SET ROLE capability for a future owner across the supported PostgreSQL versions. A non-superuser may reuse an existing verified restricted application role; if a database must be created, the administrator must have CREATEDB and successfully execute SET ROLE / RESET ROLE for that owner before any writes. Reusing both existing resources requires no creation privileges. Fail clearly before writing when these capabilities cannot be established.

Create a restricted LOGIN role with explicit NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOREPLICATION and NOBYPASSRLS, then a database owned by that role from template0. Use pgJDBC PasswordUtil to generate a SCRAM verifier on the client so the plaintext application password is never part of SQL. Do not reimplement cryptography. PostgreSQL's existing runtime dependency may become a compile dependency.

Existing resources are never altered or dropped. Before any writes, verify existing database ownership and existing role flags/memberships and supplied application login. Reuse is explicit in the inspection summary. A conflicting name/owner, privileged role or wrong application password blocks writes. Reinspect under a session advisory lock and compare the fresh CREATE/REUSE actions with `expectedRole` and `expectedDatabase` before any writes. If either differs, return CONFLICT with the fixed `database.provision.plan-changed` finding, make no changes and require a fresh inspection and confirmation. Retain a bounded global permit until the database operation really ends, use finite JDBC/statement timeouts, and handle external creation races conservatively. No automatic retry of writes. PostgreSQL CREATE DATABASE is not transactional: report partial or uncertain effects precisely; later inspection re-reads state and permits safe reuse where verified.

After preparation, verify a new connection with the application credentials and target TLS settings, expected user/database, ownership and required schema privileges. Schema migrations continue to run through the existing startup path.

## Validation

Use focused Java tests for secret-safe diagnosis, successful retries and resource limits; service/controller tests for inspection, explicit confirmation, authentication/CSRF, strict input, safe conflicts and partial outcomes; PostgreSQL integration coverage for actual SQL/SCRAM/permissions; browser tests for result order, repeated tests, input invalidation, preserved non-secret entries, review-before-create and uncertain mutation outcomes. Verify narrow behavior first and use the repository's existing CI gates for integration.

## Scope

This change does not deploy to the user's cluster, create Kubernetes Services, migrate existing application records, hot-swap datasources or add provisioning for SQL Server/Oracle. Those existing connection/export choices remain available.
