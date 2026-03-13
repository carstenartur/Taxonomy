# MSSQL Setup Guide

This guide explains how to run the Taxonomy Architecture Analyzer with
**Microsoft SQL Server** as the database backend.

---

## Prerequisites

- SQL Server 2019 or later (including Azure SQL Database)
- Docker (for the Quick Start below) **or** an existing SQL Server instance
- Java 17+ (for building from source)

---

## Quick Start with Docker Compose

The fastest way to try MSSQL:

```bash
docker compose -f docker-compose-mssql.yml up
# Open http://localhost:8080
```

This starts a SQL Server 2022 Developer Edition container alongside the
Taxonomy application. Data is persisted in a Docker volume (`mssql-data`).

To tear down and remove data:

```bash
docker compose -f docker-compose-mssql.yml down -v
```

---

## Environment Variables

Activate MSSQL via the `mssql` Spring profile:

| Variable | Required | Default | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Yes** | `hsqldb` | Set to `mssql` to activate the MSSQL profile |
| `TAXONOMY_DATASOURCE_URL` | No | `jdbc:sqlserver://localhost:1433;databaseName=taxonomy;encrypt=false;trustServerCertificate=true` | JDBC URL — override host/port/database as needed |
| `SPRING_DATASOURCE_USERNAME` | No | `sa` | SQL Server login |
| `SPRING_DATASOURCE_PASSWORD` | **Yes** | *(empty)* | SQL Server password (must meet complexity requirements) |
| `TAXONOMY_DDL_AUTO` | No | `create` | Schema strategy: `create` (rebuild each start), `update` (incremental), `validate` (read-only) |

### Example: Connecting to an Existing SQL Server

```bash
export SPRING_PROFILES_ACTIVE=mssql
export TAXONOMY_DATASOURCE_URL="jdbc:sqlserver://myserver.example.com:1433;databaseName=taxonomy;encrypt=true;trustServerCertificate=false"
export SPRING_DATASOURCE_USERNAME=taxonomy_user
export SPRING_DATASOURCE_PASSWORD=SecurePassword123!
export TAXONOMY_DDL_AUTO=update

java -jar taxonomy-app/target/taxonomy-app-*.jar
```

---

## Schema Management

| `TAXONOMY_DDL_AUTO` | Behavior | Recommended For |
|---|---|---|
| `create` | Drops and recreates all tables on every start | Development, testing, Docker Compose demos |
| `update` | Adds new columns/tables without dropping existing data | Staging, early production |
| `validate` | Verifies the schema matches the entity model; fails on mismatch | Production with managed migrations |

> **Tip:** For production, consider using Flyway or Liquibase for schema
> migrations instead of Hibernate DDL auto-generation.

---

## Character Encoding — Why `nvarchar`?

All string fields use Hibernate's `@Nationalized` annotation, which maps to
`nvarchar` (Unicode) columns on SQL Server. This ensures correct storage of
multilingual taxonomy descriptions (English and German).

Large text fields (`descriptionEn`, `descriptionDe`, `reference`) use
`@Lob` + `@Nationalized`, which produces `nvarchar(max)` on SQL Server.

---

## HikariCP Connection Pool

The `mssql` profile configures HikariCP (the default Spring Boot pool):

| Property | Value | Description |
|---|---|---|
| `maximum-pool-size` | 10 | Max simultaneous connections |
| `minimum-idle` | 2 | Minimum idle connections |
| `connection-timeout` | 30 000 ms | Max wait for a connection from the pool |
| `idle-timeout` | 600 000 ms | Max idle time before a connection is retired |
| `initialization-fail-timeout` | 60 000 ms | How long to retry initial connection on startup |

Override any of these via environment variables, e.g.:
```bash
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20
```

---

## SQL Server Password Requirements

SQL Server enforces password complexity. The password must:
- Be at least 8 characters long
- Contain characters from at least three of: uppercase, lowercase, digits, symbols

The Docker Compose example uses `A_Str0ng_Required_Password`.

---

## Troubleshooting

### Prelogin / TLS Connection Errors

**Symptom:** `The driver could not establish a secure connection to SQL Server by using Secure Sockets Layer (SSL) encryption.`

**Cause:** The MSSQL JDBC driver (v12+) defaults to `encrypt=true`, but the SQL Server
container uses a self-signed certificate.

**Fix:** Add to the JDBC URL:
```
encrypt=false;trustServerCertificate=true
```
The `mssql` profile already includes this in its default URL.

### Login Timeout

**Symptom:** `Login failed` or connection timeout on startup.

**Cause:** SQL Server may not be fully ready when the application tries to connect.

**Fix:** The `mssql` profile sets `initialization-fail-timeout=60000` (60 seconds),
which tells HikariCP to retry connecting for up to 60 seconds.

### `ntext` vs `nvarchar(max)`

**Symptom:** Deprecated `ntext` columns appear in the schema.

**Cause:** Older Hibernate versions may generate `ntext` for `@Lob` + `@Nationalized`.

**Fix:** This application uses Hibernate 7.x with the `SQLServerDialect`, which
correctly generates `nvarchar(max)`.

---

## Running MSSQL Integration Tests

```bash
# Build the application JAR first
mvn package -DskipTests

# Run MSSQL tests (requires Docker)
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Mssql*IT"
```

These tests start a SQL Server container via Testcontainers and verify the full
application stack against it.
