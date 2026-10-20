# Dismissible database setup assistant

## Agreed scope

Offer an optional database setup assistant on the first administrator visit while
using embedded HSQLDB. Closing it, Escape, and continuing with the current database
leave the active configuration and business data untouched. Remember dismissal on
the server and allow reopening from Settings. No data transfer, schema mutation,
automatic restart, or live DataSource replacement is part of this change.

Reuse the existing setup checks and JDBC probe. Offer PostgreSQL, SQL Server, and
Oracle connection preparation with their existing production/evaluation limits.
Link bundled, localized help for a database Pod and the existing deployment guides.
Generate configuration without passwords; the deployment remains authoritative.

## Tasks

1. Add an ADMIN-only `/api/admin/database-setup` controller and focused service in
   `com.taxonomy.setup`. GET returns `database`, `inMemory`, `promptRequired`, and
   `deploymentManaged`. POST `/dismiss` records `setup.database.dismissed` through
   the existing PreferencesService. POST `/preview` and `/check` accept structured
   connection settings: `type`, `host`, `port`, `database`, `username`, `password`,
   `tls`, `caCertificate`. Return password-free configuration previews and existing
   SetupChecks findings. Reuse SetupProbe for a separate read-only connection;
   enforce timeouts, known drivers/options, input limits, and secret redaction.
   No active datasource changes or DDL. Preserve unrelated profiles in output.
2. Add a small Thymeleaf fragment and JavaScript module for the optional prompt,
   connection form, test results, and copy/download of properties and Helm guidance.
   Use TaxonomyApiClient, native dialogs, and existing i18n/accessibility conventions.
   Add a Settings entry and coordinate with the existing welcome overlay so only
   one opening dialog appears. Closing must always restore normal interaction.
3. Extend German/English DATABASE_SETUP help with the assistant and a practical
   PostgreSQL Pod section. Keep database maturity and manual deployment/restart
   explicit. Add focused JUnit service/controller tests and a browser regression
   using existing test infrastructure; do not add workflows or a test framework.
4. Run focused Maven tests, inspect the integrated changes, obtain independent
   review, and publish one branch based directly on main as a reviewable PR.

## Acceptance

- Only ADMIN sees the initial offer or can invoke setup endpoints.
- Dismissal survives a new browser session on the same persistent installation.
- Existing analysis input and database settings survive dismissal and testing.
- Invalid/failed probes expose neither passwords nor raw driver exceptions.
- PostgreSQL settings use validate and preserve authentication/deployment profiles.
- SQL Server/Oracle do not acquire a false production or migration readiness claim.
- Help and API calls work behind the existing Rancher `/taxonomy` prefix.
- Generated files contain no submitted or existing password.
