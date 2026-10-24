# Database Setup Guide

This guide explains how to run the Taxonomy Architecture Analyzer with different database backends. By default, the application uses an embedded **HSQLDB** database that requires no setup. For persistent external-database production, use PostgreSQL with the released JGit Core and Taxonomy Flyway migrations. SQL Server and Oracle profiles are compatibility/evaluation paths; their complete migration, upgrade and restore contracts have not been qualified for production. A JDBC driver and Hibernate dialect alone do not establish support.

---

## Table of Contents

- [Overview](#overview)
- [Optional database setup assistant](#optional-database-setup-assistant)
- [PostgreSQL Pod in Rancher or Kubernetes](#postgresql-pod-in-rancher-or-kubernetes)
- [HSQLDB (Default)](#hsqldb-default)
- [PostgreSQL](#postgresql)
- [Microsoft SQL Server (MSSQL)](#microsoft-sql-server-mssql)
- [Oracle Database](#oracle-database)
- [Adding a New Database](#adding-a-new-database)
- [Common Configuration](#common-configuration)
- [Moving from HSQLDB to PostgreSQL](#moving-from-hsqldb-to-postgresql)
- [Related Documentation](#related-documentation)

---

## Overview

The application provides database backends through **Spring Profiles**. Each database profile configures the JDBC driver, dialect, connection pool, and any database-specific column type mappings. Their deployment and migration support differs:

| Profile | Database | Activation | Docker Compose File | Support boundary |
|---|---|---|---|---|
| `hsqldb` *(default)* | HSQLDB (in-process) | No configuration needed | — | In-memory development/tests; file-backed controlled baseline |
| `postgres` | PostgreSQL 14+ | `SPRING_PROFILES_ACTIVE=postgres` | `docker-compose-postgres.yml` | Persistent external database with released migrations |
| `mssql` | SQL Server 2019+ | `SPRING_PROFILES_ACTIVE=mssql` | `docker-compose-mssql.yml` | Compatibility/evaluation; no qualified complete migration path |
| `oracle` | Oracle 19c+ / 23c Free | `SPRING_PROFILES_ACTIVE=oracle` | `docker-compose-oracle.yml` | Compatibility/evaluation; no qualified complete migration path |

All database profiles share the same entity model. Hibernate's `@Nationalized` and `@Lob` annotations ensure correct Unicode and large-text handling across all databases.

---

## Optional database setup assistant

On the first eligible administrator visit while Taxonomy uses embedded HSQLDB,
the welcome dialog offers database setup. You can close it, press Escape, or
continue with the current database. Dismissal keeps the active configuration and
application data intact and is stored on the server through `PreferencesService`
as `setup.database.dismissed`. It applies to the installation and survives a new
browser session. With in-memory HSQLDB it lasts only as long as that database;
resetting or recreating the data also removes the saved choice. Administrators
can reopen the assistant under **Settings > Database setup**.

1. Select **PostgreSQL**, **SQL Server**, or **Oracle** and enter the reachable
   database host, port, database name (Oracle: service name), username, and password.
   The database server and account must already exist.
2. Keep verified **TLS** enabled. For PostgreSQL, supply the absolute path to a
   trusted CA certificate already mounted in the **Taxonomy server/Pod**, for
   example `/etc/taxonomy-db-ca/ca.crt`, when your CA configuration requires it.
   This is a server path, not a file upload or a path on your browser's computer.
   SQL Server and Oracle use trust already configured in the application's JVM;
   the assistant does not install certificates or create a truststore.
3. Run the connection test. It opens a separate, time-bounded JDBC connection with
   a read-only hint and performs no DDL or application-data writes. Success proves
   reachability and database login only; schema compatibility, write/migration
   permissions, upgrade, and restore remain untested. SQL Server and Oracle remain
   evaluation paths even when this test succeeds.
4. Copy the configuration or download `taxonomy-database.properties` and
   `taxonomy-database-values.yaml`. Password properties are omitted entirely;
   comments explain how to supply the password through the existing deployment
   Secret or protected Spring configuration. The test password is not saved as a
   preference. The selected database profile replaces the old database profile,
   while other active/default profiles remain. `production` is preserved when
   present and is not added automatically; the Helm output also includes
   `kubernetes`. Review the profile list for the intended deployment and login mode.

If configuration export reports a **schema-setting conflict**, review existing
`spring.jpa.properties.hibernate.hbm2ddl.auto`,
`spring.jpa.properties.jakarta.persistence.schema-generation.database.action`,
and the legacy `spring.jpa.properties.javax.persistence.schema-generation.database.action`
setting. Also check `spring.jpa.hibernate.ddl-auto` in `SPRING_APPLICATION_JSON`,
JVM system properties, or command-line arguments: those sources can outrank the
generated environment settings. Remove the conflicting overrides or set them to
`validate` in the deployment, then retry once the corrected configuration is
effective. The ordinary embedded-HSQLDB default in an application-properties file
does not prevent export. Connection testing remains available during a conflict.
This check examines the current server configuration; still review settings added
later to the target deployment before restarting it.

The operator applies the configuration and deliberately redeploys/restarts
Taxonomy. The assistant does not switch the running datasource, provision Pods,
initialize a schema, or restart the application. It does **not transfer existing
HSQLDB data**. Keep the source database and a verified backup if those records
must be retained; see [Moving from HSQLDB to PostgreSQL](#moving-from-hsqldb-to-postgresql).

TLS reference: [pgJDBC](https://jdbc.postgresql.org/documentation/ssl/),
[Microsoft JDBC](https://learn.microsoft.com/en-us/sql/connect/jdbc/connecting-with-ssl-encryption),
and [Oracle JDBC](https://docs.oracle.com/en/database/oracle/oracle-database/23/jjdbc/client-side-security.html).

## PostgreSQL Pod in Rancher or Kubernetes

A straightforward starting arrangement is a separate PostgreSQL **StatefulSet**
with its own **PersistentVolumeClaim (PVC)** and a **ClusterIP Service** in the same
namespace as Taxonomy. The Taxonomy chart and browser assistant do not create the
database. The existing [database Pod guide](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/DATABASE_PODS.md)
and [PostgreSQL values example](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/values-postgres-service.yaml)
show how Taxonomy connects to a separately managed database.

1. **Create the workload and storage.** In Rancher, open the cluster's workload
   view, create a StatefulSet in the intended namespace, and attach a volume claim
   using a suitable StorageClass. Pin a PostgreSQL image version approved for your
   release and environment. For the official `postgres` image, version 17 uses
   `/var/lib/postgresql/data`; version 18 mounts the volume at `/var/lib/postgresql`
   and defaults to `PGDATA=/var/lib/postgresql/18/docker`. Match the PVC mount to the
   chosen image and configuration. See [Rancher workloads](https://ranchermanager.docs.rancher.com/how-to-guides/new-user-guides/kubernetes-resources-setup/workloads-and-pods/deploy-workloads),
   [Kubernetes StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/),
   and the [official image instructions](https://hub.docker.com/_/postgres).
2. **Create the database and application account.** Use a database named
   `taxonomy` and a dedicated restricted account such as `taxonomy_app`. Keep
   administrator/bootstrap credentials separate. The official image's
   `POSTGRES_USER` creates a **superuser**; setting it to the application username
   does not create a restricted application account. Provision the application
   role separately, with ownership/DDL rights for the Taxonomy database and schema
   needed by startup migrations, without cluster-wide superuser privileges.
   `POSTGRES_DB=taxonomy` and initialization scripts apply to an empty data
   directory; changing them does not reconfigure an existing PVC. See the
   [image's initialization contract](https://hub.docker.com/_/postgres).
3. **Expose an internal Service and configure TLS.** A Service called `postgres`
   on TCP 5432 can be addressed as `postgres` from the same namespace. For another
   namespace, use a name such as `postgres.database.svc.cluster.local`, adjusted
   to the cluster's DNS domain. Use the writable primary endpoint. `localhost`
   would point at the Taxonomy Pod; no NodePort, public LoadBalancer, or HTTP Ingress
   is needed. Enable TLS on PostgreSQL, provide a server certificate matching the
   chosen Service DNS name, and mount the public CA certificate into Taxonomy.
   The wizard's TLS option does not configure the database server. See
   [Service DNS](https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/)
   and [pgJDBC certificate verification](https://jdbc.postgresql.org/documentation/ssl/).
4. **Supply Taxonomy's configuration.** In the Taxonomy namespace, populate the
   Secret referenced by `existingSecret` with `SPRING_DATASOURCE_URL`,
   `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`. For example, the
   URL can be `jdbc:postgresql://postgres:5432/taxonomy?sslmode=verify-full&sslrootcert=/etc/taxonomy-db-ca/ca.crt`.
   Select `database.type=postgres`; leave `database.url` and `database.username`
   blank to use those Secret keys, or deliberately override them in values.
   Retain the configured login mode and its required Secret keys, such as
   `ADMIN_PASSWORD` for local login or `KEYCLOAK_CLIENT_SECRET` for Keycloak.
   Selecting the database must not remove authentication or deployment profiles.
5. **Allow the connection in both directions.** Any applicable NetworkPolicies
   must allow Taxonomy egress to the database and database ingress from Taxonomy
   on TCP 5432. Allow Taxonomy to reach the cluster DNS service as well. Match the
   actual namespace and Pod labels, and retain other required rules and mounts
   when combining values files. See [Kubernetes NetworkPolicies](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
   and the [chart examples](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/DATABASE_PODS.md#netzwerk-und-dauerhafte-daten).
6. **Test, deploy, and verify persistence.** Run the assistant's connection test
   from the running Taxonomy server, then explicitly apply the reviewed deployment
   configuration with `TAXONOMY_DDL_AUTO=validate`. On the next application start,
   the released JGit Core and Taxonomy Flyway streams initialize a fresh PostgreSQL
   schema; the connection test does not do this. Verify actual startup, login, and
   application behavior. Preserve the database PVC independently of Taxonomy's
   `/app/data` PVC and establish tested backups and restore. One database Pod alone
   does not provide high availability.

---

## HSQLDB (Default)

The application ships with an embedded HSQLDB database. No installation or external database server is required.

**Key characteristics:**
- Runs **in-process** (same JVM, no network hop)
- Uses a bounded HikariCP pool (`minimum-idle=1`, `maximum-pool-size=4`)
- Defaults to an in-memory URL for zero-configuration development and tests
- Supports a file URL for persistent deployments; the pool keeps a connection open so `shutdown=true` cannot close HSQLDB during Spring startup
- Reuses an existing persisted catalogue on restart; `TAXONOMY_INIT_RELOAD_EXISTING=true` performs an intentional destructive reload

The in-memory default is for development and tests. The small controlled production Compose baseline uses file-backed HSQLDB and filesystem Lucene; multi-user external production uses PostgreSQL.

For an upgrade from 1.3, stop all application writers and verify a complete
[backup and restore](CONTAINER_IMAGE.md#5-persistence-and-backup) before starting the
new version. With the HSQLDB profile and schema migration enabled, startup prepares
the existing repository and portfolio rows before Hibernate adds the remaining
schema. Repository/workspace/branch identities are derived from recorded
provenance; existing completed identities are preserved. Ambiguous repositories,
colliding portfolio keys within the same tenant or inconsistent parent references stop the
upgrade. Restore the stopped-writer backup before resolving the reported data
conflict and retrying. Do not use `create` or disable schema migration to get past
an upgrade failure.

Historical central portfolios remain available by selecting the central view
(API clients send an explicitly empty `X-Taxonomy-Workspace-Id`). Their GET/HEAD
read routes keep the exact repository and branch identity. New analyses and
changes still require an isolated workspace; selecting a workspace does not move
the old central records into it.

---

## PostgreSQL

### Prerequisites

- PostgreSQL 14 or later (including Amazon RDS, Azure Database for PostgreSQL, Google Cloud SQL)
- Docker (for Quick Start) **or** an existing PostgreSQL instance

### Quick Start with Docker Compose

```bash
docker compose -f docker-compose-postgres.yml up
# Open http://localhost:8080
```

This starts a PostgreSQL 16 Alpine container alongside the Taxonomy application. Data is persisted in a Docker volume (`postgres-data`).
This is a **disposable evaluation example**: its Compose file sets
`TAXONOMY_DDL_AUTO=create`, so restarting the application recreates
Hibernate-managed tables and can discard data despite the persistent volume.
Use `production,postgres` with `TAXONOMY_DDL_AUTO=validate`, backed-up
PostgreSQL and the released migrations for retained production data.

```bash
# Tear down and remove data
docker compose -f docker-compose-postgres.yml down -v
```

### Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Yes** | `hsqldb` | Set to `postgres` |
| `TAXONOMY_DATASOURCE_URL` | No | `jdbc:postgresql://localhost:5432/taxonomy` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | No | `taxonomy` | PostgreSQL login |
| `SPRING_DATASOURCE_PASSWORD` | No | `taxonomy` | PostgreSQL password |
| `TAXONOMY_DDL_AUTO` | No | base `create`; production `update`; Kubernetes `validate` | Use `validate` with released PostgreSQL migrations; never use `create` on persistent data. |

### Example: Connecting to an Existing Server

```bash
export SPRING_PROFILES_ACTIVE=production,postgres
export TAXONOMY_DATASOURCE_URL="jdbc:postgresql://myserver.example.com:5432/taxonomy"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD="$DATABASE_PASSWORD"
export TAXONOMY_ADMIN_PASSWORD="$TAXONOMY_ADMIN_PASSWORD"
export TAXONOMY_DDL_AUTO=validate

java -jar taxonomy-app/target/taxonomy-app-*.jar
```

### Character Encoding

PostgreSQL uses UTF-8 by default, so all string fields are stored correctly without special annotations. Hibernate's `@Nationalized` annotation has no effect on PostgreSQL.

### Troubleshooting

| Problem | Symptom | Fix |
|---|---|---|
| **Connection refused** | `Connection to localhost:5432 refused` | Verify PostgreSQL is running: `pg_isready -h localhost -p 5432` |
| **Auth failed** | `FATAL: password authentication failed` | Check `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` |
| **DB missing** | `FATAL: database "taxonomy" does not exist` | Create it: `CREATE DATABASE taxonomy OWNER taxonomy;` |

### Running Integration Tests

```bash
./mvnw -B verify -Pdatabase-postgres
```

---

## Microsoft SQL Server (MSSQL)

### Prerequisites

- SQL Server 2019 or later (including Azure SQL Database)
- Docker (for Quick Start) **or** an existing SQL Server instance

### Quick Start with Docker Compose (compatibility test only)

```bash
docker compose -f docker-compose-mssql.yml up
# Open http://localhost:8080
```

This starts a SQL Server 2022 Developer Edition container alongside the Taxonomy application. Data is persisted in a Docker volume (`mssql-data`).

```bash
# Tear down and remove data
docker compose -f docker-compose-mssql.yml down -v
```

### Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Yes** | `hsqldb` | Set to `mssql` |
| `TAXONOMY_DATASOURCE_URL` | No | `jdbc:sqlserver://localhost:1433;databaseName=taxonomy;encrypt=false;trustServerCertificate=true` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | No | `sa` | SQL Server login |
| `SPRING_DATASOURCE_PASSWORD` | **Yes** | *(empty)* | SQL Server password (must meet complexity requirements) |
| `TAXONOMY_DDL_AUTO` | No | base `create`; production `update`; Kubernetes `validate` | Compatibility testing only; this profile does not have the released PostgreSQL Flyway migration path. Never use `create` on retained data. |

### Example: Connecting to an Existing Server

```bash
export SPRING_PROFILES_ACTIVE=mssql
export TAXONOMY_DATASOURCE_URL="jdbc:sqlserver://myserver.example.com:1433;databaseName=taxonomy;encrypt=true;trustServerCertificate=false"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD=SecurePassword123!
export TAXONOMY_DDL_AUTO=update

java -jar taxonomy-app/target/taxonomy-app-*.jar
```

### Character Encoding — Why `nvarchar`?

All string fields use Hibernate's `@Nationalized` annotation, which maps to `nvarchar` (Unicode) columns on SQL Server. Large text fields use `@Lob` + `@Nationalized`, producing `nvarchar(max)`.

### SQL Server Password Requirements

The password must be at least 8 characters and contain characters from at least three of: uppercase, lowercase, digits, symbols. The Docker Compose example uses `A_Str0ng_Required_Password`.

### Troubleshooting

| Problem | Symptom | Fix |
|---|---|---|
| **TLS error** | `Could not establish secure connection` | Configure a trusted CA/server certificate and matching hostname; do not disable certificate validation for production. |
| **Login timeout** | `Login failed` or timeout | The profile sets 60s retry; increase if needed |
| **Deprecated ntext** | `ntext` columns in schema | Verify Hibernate 7.x + `SQLServerDialect` are in use |

### Running Integration Tests

```bash
./mvnw -B verify -Pdatabase-mssql
```

---

## Oracle Database

### Prerequisites

- Oracle Database 23c Free, or Oracle Database 19c+ (including Oracle Cloud Autonomous Database)
- Docker (for Quick Start) **or** an existing Oracle instance

### Quick Start with Docker Compose (compatibility test only)

```bash
docker compose -f docker-compose-oracle.yml up
# Open http://localhost:8080
```

This starts an Oracle Database 23c Free container alongside the Taxonomy application. Data is persisted in a Docker volume (`oracle-data`).

> **Note:** The Oracle container may take 1–2 minutes to start on the first run while it initialises the database. The `gvenzl/oracle-free:23-slim-faststart` image is optimised for fast startup.

```bash
# Tear down and remove data
docker compose -f docker-compose-oracle.yml down -v
```

### Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Yes** | `hsqldb` | Set to `oracle` |
| `TAXONOMY_DATASOURCE_URL` | No | `jdbc:oracle:thin:@localhost:1521/taxonomy` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | No | `taxonomy` | Oracle login |
| `SPRING_DATASOURCE_PASSWORD` | No | `taxonomy` | Oracle password |
| `TAXONOMY_DDL_AUTO` | No | base `create`; production `update`; Kubernetes `validate` | Compatibility testing only; this profile does not have the released PostgreSQL Flyway migration path. Never use `create` on retained data. |

### Example: Connecting to an Existing Server

```bash
export SPRING_PROFILES_ACTIVE=oracle
export TAXONOMY_DATASOURCE_URL="jdbc:oracle:thin:@myserver.example.com:1521/ORCLPDB1"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD=SecurePassword123!
export TAXONOMY_DDL_AUTO=update

java -jar taxonomy-app/target/taxonomy-app-*.jar
```

### Character Encoding

Oracle 23c uses **AL32UTF8** by default. Hibernate's `@Nationalized` maps to `NVARCHAR2` / `NCLOB`, ensuring correct Unicode storage regardless of the database character set.

### Oracle Reserved Words

Oracle reserves certain SQL keywords (e.g. `LEVEL`, `COMMENT`, `USER`). The application maps conflicting field names to safe column names via `@Column(name = "...")` — for example, `TaxonomyNode.level` → column `node_level`.

If you add new entity fields, check [Oracle Reserved Words](https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/Oracle-SQL-Reserved-Words.html).

### Troubleshooting

| Problem | Symptom | Fix |
|---|---|---|
| **Connection refused** | `IO Error: The Network Adapter could not establish the connection` | Verify Oracle is running and listener is active |
| **Auth failed** | `ORA-01017: invalid username/password` | Check credentials match Oracle configuration |
| **Service not found** | `ORA-12514: listener does not currently know of service` | Ensure JDBC URL uses correct service name (not SID) |
| **Slow startup** | Container takes >2 minutes | The `faststart` image is used; increase healthcheck retries if needed |

### Service Name vs SID

The default URL uses a **service name**: `jdbc:oracle:thin:@localhost:1521/taxonomy`

For Oracle instances using a **SID**, use: `jdbc:oracle:thin:@localhost:1521:ORCL`

### Running Integration Tests

```bash
./mvnw -B verify -Pdatabase-oracle
```

---

## Adding a New Database

To add support for a new database backend:

1. **Create a Spring Profile** — Add an `application-{dbname}.properties` file under `src/main/resources/` with the JDBC driver, dialect, and connection pool settings.
2. **Add a Docker Compose file** — Create `docker-compose-{dbname}.yml` with the database container and application service.
3. **Test entity mappings** — Run the full test suite against the new database using Testcontainers to verify that `@Nationalized`, `@Lob`, and custom column mappings work correctly.
4. **Check reserved words** — Verify that all `@Column(name = "...")` mappings avoid reserved words in the target database.
5. **Add an integration test** — Create a `*{Dbname}*IT.java` test class with the appropriate Testcontainers setup.
6. **Update this documentation** — Add a new section to this file with Quick Start, environment variables, troubleshooting, and test instructions.

The entity model alone does not qualify a new backend: storage-library schema migrations, application migrations, persistence, upgrade and restore must also pass on that database.

---

## Common Configuration

### Schema Strategy (`TAXONOMY_DDL_AUTO`)

| Value | Behavior | Recommended For |
|---|---|---|
| `create` | Drops and recreates Hibernate-managed tables on every start | Disposable development/tests only; never retained data |
| `update` | Hibernate attempts additive changes; it is not a versioned migration | File-backed HSQLDB Compose baseline; not the PostgreSQL managed-migration contract |
| `validate` | Verifies schema matches entity model; fails on mismatch | Production with managed migrations |

> **PostgreSQL:** JGit Core and Taxonomy application Flyway migrations are already released and run at startup. Use `TAXONOMY_DDL_AUTO=validate` after a verified backup. The generic `production` profile still defaults to `update` for the file-backed HSQLDB baseline; override it explicitly for PostgreSQL. SQL Server and Oracle disable this Flyway path pending qualification.

### HikariCP Connection Pool

All external database profiles (PostgreSQL, MSSQL, Oracle) configure HikariCP with the same defaults:

| Property | Value | Description |
|---|---|---|
| `maximum-pool-size` | 10 | Max simultaneous connections |
| `minimum-idle` | 2 | Minimum idle connections |
| `connection-timeout` | 30000 ms | Max wait for a connection |
| `idle-timeout` | 600000 ms | Max idle time before retirement |
| `initialization-fail-timeout` | 60000 ms | Retry window for initial connection |

Override via environment variables:
```bash
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20
```

> **Note:** The HSQLDB profile uses a small bounded HikariCP pool (`minimum-idle=1`, `maximum-pool-size=4`). The idle connection is required for a stable file-database lifecycle when `shutdown=true` is used.

---

## Moving from HSQLDB to PostgreSQL

Selecting `postgres` points the application at a **different database**; it
never copies users, workspaces, relations, hypotheses, Git packs/refs or other
records from HSQLDB. The project does not ship an automatic cross-database
transfer procedure. Keep a tested backup of the complete source database and
its associated index/configuration before considering a transfer. Qualify any
conversion on an isolated copy, verify row and Git-history integrity, login,
workspaces and representative search, and plan a controlled cutover. Do not
set `TAXONOMY_DDL_AUTO=create` on either retained database.

For a **fresh empty** PostgreSQL installation, provide database and local-login
credentials and `SPRING_PROFILES_ACTIVE=production,postgres`, set
`TAXONOMY_DDL_AUTO=validate`, and allow the released JGit Core and Taxonomy
application Flyway streams to create the schema. Back up before subsequent
upgrades; the safe Helm strategy is `Recreate`, and rolling compatibility is
release-specific. See [JGit storage](JGIT_STORAGE_HIBERNATE.md) and the
[Helm upgrade contract](https://github.com/carstenartur/Taxonomy/blob/main/deploy/helm/taxonomy/README.md#upgrade-safety-and-database-migrations).

SQL Server and Oracle Compose files remain evaluation fixtures, not a
production migration destination or a substitute for a verified data transfer.

---

## Related Documentation

- [Configuration Reference](CONFIGURATION_REFERENCE.md) — All environment variables
- [Deployment Guide](DEPLOYMENT_GUIDE.md) — Docker and cloud deployment
- [Architecture](ARCHITECTURE.md) — Database architecture and entity model

## Test architecture and compatibility evidence

`./mvnw verify` is the bounded deterministic lifecycle and does not start external
databases. Core HSQLDB container tests and the PostgreSQL/MSSQL/Oracle matrix are
ordinary Failsafe/Testcontainers tests enabled with `-DskipITs=false`.

The `Database Compatibility` workflow runs PostgreSQL diagnostics and Selenium
on relevant pull requests, all six database scenarios weekly, and a selected
family on manual dispatch. Every command is documented in
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).

### Hypothesis branch authority when upgrading to 1.5.1

V43 (PostgreSQL) and the portable schema contract require an explicit branch for
all relation hypotheses. Existing nonblank branches remain authoritative on
restart. A legacy row without a branch is backfilled only through its recorded
analysis snapshot or project, with matching repository, workspace and canonical
portfolio scope. An overlapping analysis-session string, current workspace
branch, repository default, or review status is never sufficient evidence.
Evidence rows inherit the validated parent's exact repository/workspace/branch
and retain a composite foreign key to that parent. A workspace can read the
central baseline only on the selected branch; mutations remain workspace-exact.

If provenance is absent, dangling or contradictory, startup stops without
removing hypotheses or evidence. The error contains bounded reason counts and at
most 20 numeric row IDs; those IDs refer to hypotheses unless the reason names
evidence. To remediate:

1. Keep all application writers stopped and preserve a verified full backup.
2. Inspect the reported IDs offline. Recover the recorded snapshot/project
   links and canonical repository/workspace/branch identity from trustworthy
   historical records or a backup. Repair only links and identities that can be
   proven; never assign all rows to `draft` or the current workspace branch.
3. Correct conflicting populated evidence authority to its proven parent only
   after verifying the historical link. Rerun startup; the migration reports the
   next bounded set if further conflicts remain.
4. If the original authority cannot be recovered, export/archive/removal of the
   affected hypotheses **and their dependent evidence** requires an explicit
   operator decision and a retained backup. The migration never performs that
   deletion automatically. Do not bypass the migration or edit published V7.

The retained HSQLDB upgrade prepares this contract before Hibernate. The shipped
Oracle and SQL Server compatibility profiles disable Flyway, so their portable
initializer also runs before Hibernate; the normal runner repeats the idempotent
checks. Interrupted DDL can be resumed, including Oracle's independently committed
DDL phases. These profiles still require their existing native compatibility
lanes; local HSQLDB results do not qualify Oracle or SQL Server.

SQL Server stores the new branch authority as Unicode with binary `BIN2`
comparison so `Main`, `main` and non-ASCII Git refs remain distinct. Its exact
business key was already wider in declared capacity than the nonclustered index
limit before this change. SQL Server allows variable-width declarations above
that limit, but an actual combined key above **1,700 bytes** cannot be indexed.
The migration retains exact keys and fails rather than replacing authority with
a lossy or hashed identity. Verify the native lane and real key lengths before
using that compatibility profile. See Microsoft's [maximum capacity limits](https://learn.microsoft.com/en-us/sql/sql-server/maximum-capacity-specifications-for-sql-server?view=sql-server-ver17)
and [collation and Unicode support](https://learn.microsoft.com/en-us/sql/relational-databases/collations/collation-and-unicode-support/).
