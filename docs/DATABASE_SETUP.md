# Database Setup Guide

This guide explains how to run the Taxonomy Architecture Analyzer with different database backends. By default, the application uses an embedded **HSQLDB** database that requires no setup. For production deployments, you can switch to PostgreSQL, Microsoft SQL Server, Oracle, or other JDBC-compatible databases.

---

## Table of Contents

- [Overview](#overview)
- [HSQLDB (Default)](#hsqldb-default)
- [PostgreSQL](#postgresql)
- [Microsoft SQL Server (MSSQL)](#microsoft-sql-server-mssql)
- [Oracle Database](#oracle-database)
- [Adding a New Database](#adding-a-new-database)
- [Common Configuration](#common-configuration)
- [Migration Path: HSQLDB → Production Database](#migration-path-hsqldb--production-database)
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
- Uses `SimpleDriverDataSource` instead of HikariCP to avoid connection pool overhead
- All data is loaded from the bundled Excel workbook at startup
- Data is **not persisted** between restarts (in-memory mode)

This is ideal for development, testing, and demo deployments.

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
| `TAXONOMY_DDL_AUTO` | No | `create` | Schema strategy (see [Common Configuration](#common-configuration)) |

### Example: Connecting to an Existing Server

```bash
export SPRING_PROFILES_ACTIVE=postgres
export TAXONOMY_DATASOURCE_URL="jdbc:postgresql://myserver.example.com:5432/taxonomy"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD=SecurePassword123!
export TAXONOMY_DDL_AUTO=update

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
mvn package -DskipTests
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Postgres*IT"
```

---

## Microsoft SQL Server (MSSQL)

### Prerequisites

- SQL Server 2019 or later (including Azure SQL Database)
- Docker (for Quick Start) **or** an existing SQL Server instance

### Quick Start with Docker Compose

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
| `TAXONOMY_DDL_AUTO` | No | `create` | Schema strategy (see [Common Configuration](#common-configuration)) |

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
| **TLS error** | `Could not establish secure connection` | Add `encrypt=false;trustServerCertificate=true` to JDBC URL |
| **Login timeout** | `Login failed` or timeout | The profile sets 60s retry; increase if needed |
| **Deprecated ntext** | `ntext` columns in schema | Verify Hibernate 7.x + `SQLServerDialect` are in use |

### Running Integration Tests

```bash
mvn package -DskipTests
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Mssql*IT"
```

---

## Oracle Database

### Prerequisites

- Oracle Database 23c Free, or Oracle Database 19c+ (including Oracle Cloud Autonomous Database)
- Docker (for Quick Start) **or** an existing Oracle instance

### Quick Start with Docker Compose

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
| `TAXONOMY_DDL_AUTO` | No | `create` | Schema strategy (see [Common Configuration](#common-configuration)) |

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
mvn package -DskipTests
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Oracle*IT"
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

The application's entity model is database-agnostic thanks to Hibernate. Any JDBC-compatible database with a Hibernate dialect should work with minimal configuration.

---

## Common Configuration

### Schema Strategy (`TAXONOMY_DDL_AUTO`)

| Value | Behavior | Recommended For |
|---|---|---|
| `create` | Drops and recreates all tables on every start | Development, testing, Docker Compose demos |
| `update` | Adds new columns/tables without dropping existing data | Staging, early production |
| `validate` | Verifies schema matches entity model; fails on mismatch | Production with managed migrations |

> **Tip:** For production, consider using Flyway or Liquibase for schema migrations instead of Hibernate DDL auto-generation.

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

> **Note:** The default HSQLDB profile does **not** use HikariCP — it uses `SimpleDriverDataSource` to avoid connection pool overhead in single-JVM mode.

---

## Migration Path: HSQLDB → Production Database

To migrate from the default HSQLDB to a production database:

1. **Choose a database** — Select PostgreSQL, MSSQL, or Oracle based on your infrastructure.
2. **Set the profile** — `SPRING_PROFILES_ACTIVE={postgres|mssql|oracle}`
3. **Configure connection** — Set `TAXONOMY_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
4. **Initial schema** — Use `TAXONOMY_DDL_AUTO=create` for the first run to create all tables.
5. **Switch to update** — After the initial setup, switch to `TAXONOMY_DDL_AUTO=update` to preserve data across restarts.
6. **Verify** — Check the health endpoint (`GET /actuator/health`) and run a test analysis.

The taxonomy data is always loaded from the bundled Excel workbook at startup, so no data migration from HSQLDB is needed for the taxonomy itself. Architecture DSL data (Git repository) is stored in the database and will need to be re-created or migrated manually.

---

## Related Documentation

- [Configuration Reference](CONFIGURATION_REFERENCE.md) — All environment variables
- [Deployment Guide](DEPLOYMENT_GUIDE.md) — Docker and cloud deployment
- [Architecture](ARCHITECTURE.md) — Database architecture and entity model
