# Database Setup Guide

This guide explains how to run the Taxonomy Architecture Analyzer with different database backends. By default, the application uses an embedded **HSQLDB** database that requires no setup. For persistent external-database production, use PostgreSQL with the released JGit Core and Taxonomy Flyway migrations. SQL Server and Oracle profiles are compatibility/evaluation paths; their complete migration, upgrade and restore contracts have not been qualified for production. A JDBC driver and Hibernate dialect alone do not establish support.

---

## Table of Contents

- [Overview](#overview)
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

The application supports multiple database backends through **Spring Profiles**. Each database profile configures the JDBC driver, dialect, connection pool, and any database-specific column type mappings.

| Profile | Database | Activation | Docker Compose File |
|---|---|---|---|
| `hsqldb` *(default)* | HSQLDB (in-process) | No configuration needed | — |
| `postgres` | PostgreSQL 14+ | `SPRING_PROFILES_ACTIVE=postgres` | `docker-compose-postgres.yml` |
| `mssql` | SQL Server 2019+ | `SPRING_PROFILES_ACTIVE=mssql` | `docker-compose-mssql.yml` |
| `oracle` | Oracle 19c+ / 23c Free | `SPRING_PROFILES_ACTIVE=oracle` | `docker-compose-oracle.yml` |

All database profiles share the same entity model. Hibernate's `@Nationalized` and `@Lob` annotations ensure correct Unicode and large-text handling across all databases.

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

All production database profiles (PostgreSQL, MSSQL, Oracle) configure HikariCP with the same defaults:

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
[Helm upgrade contract](../../deploy/helm/taxonomy/README.md#upgrade-safety-and-database-migrations).

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
