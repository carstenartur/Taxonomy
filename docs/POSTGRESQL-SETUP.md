# PostgreSQL Setup Guide

This guide explains how to run the Taxonomy Architecture Analyzer with
**PostgreSQL** as the database backend.

---

## Prerequisites

- PostgreSQL 14 or later (including cloud-managed instances such as Amazon RDS,
  Azure Database for PostgreSQL, or Google Cloud SQL)
- Docker (for the Quick Start below) **or** an existing PostgreSQL instance
- Java 17+ (for building from source)

---

## Quick Start with Docker Compose

The fastest way to try PostgreSQL:

```bash
docker compose -f docker-compose-postgres.yml up
# Open http://localhost:8080
```

This starts a PostgreSQL 16 Alpine container alongside the Taxonomy application.
Data is persisted in a Docker volume (`postgres-data`).

To tear down and remove data:

```bash
docker compose -f docker-compose-postgres.yml down -v
```

---

## Environment Variables

Activate PostgreSQL via the `postgres` Spring profile:

| Variable | Required | Default | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Yes** | `hsqldb` | Set to `postgres` to activate the PostgreSQL profile |
| `TAXONOMY_DATASOURCE_URL` | No | `jdbc:postgresql://localhost:5432/taxonomy` | JDBC URL — override host/port/database as needed |
| `SPRING_DATASOURCE_USERNAME` | No | `taxonomy` | PostgreSQL login |
| `SPRING_DATASOURCE_PASSWORD` | No | `taxonomy` | PostgreSQL password |
| `TAXONOMY_DDL_AUTO` | No | `create` | Schema strategy: `create` (rebuild each start), `update` (incremental), `validate` (read-only) |

### Example: Connecting to an Existing PostgreSQL Server

```bash
export SPRING_PROFILES_ACTIVE=postgres
export TAXONOMY_DATASOURCE_URL="jdbc:postgresql://myserver.example.com:5432/taxonomy"
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

## Character Encoding

PostgreSQL uses UTF-8 by default, so all string fields (including multilingual
taxonomy descriptions in English and German) are stored correctly without any
special column type annotations.

Hibernate's `@Nationalized` annotation has no effect on PostgreSQL — standard
`varchar` / `text` columns already support the full Unicode range.

---

## HikariCP Connection Pool

The `postgres` profile configures HikariCP (the default Spring Boot pool):

| Property | Value | Description |
|---|---|---|
| `maximum-pool-size` | 10 | Max simultaneous connections |
| `minimum-idle` | 2 | Minimum idle connections |
| `connection-timeout` | 30000 ms | Max wait for a connection from the pool |
| `idle-timeout` | 600000 ms | Max idle time before a connection is retired |
| `initialization-fail-timeout` | 60000 ms | How long to retry initial connection on startup |

Override any of these via environment variables, e.g.:
```bash
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20
```

---

## Troubleshooting

### Connection Refused

**Symptom:** `Connection to localhost:5432 refused.`

**Cause:** PostgreSQL is not running or not listening on the expected host/port.

**Fix:** Verify that PostgreSQL is running and accessible:
```bash
pg_isready -h localhost -p 5432
```
If using Docker Compose, ensure the `db` service is healthy before the app starts
(the provided `docker-compose-postgres.yml` handles this automatically).

### Authentication Failed

**Symptom:** `FATAL: password authentication failed for user "taxonomy"`

**Cause:** The username or password does not match the PostgreSQL configuration.

**Fix:** Verify that `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`
match the credentials configured in PostgreSQL. The Docker Compose example uses
`taxonomy` / `taxonomy`.

### Database Does Not Exist

**Symptom:** `FATAL: database "taxonomy" does not exist`

**Cause:** The target database has not been created.

**Fix:** Create the database manually:
```sql
CREATE DATABASE taxonomy OWNER taxonomy;
```
The Docker Compose example creates the database automatically via the
`POSTGRES_DB` environment variable.

---

## Running PostgreSQL Integration Tests

```bash
# Build the application JAR first
mvn package -DskipTests

# Run PostgreSQL tests (requires Docker)
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Postgres*IT"
```

These tests start a PostgreSQL container via Testcontainers and verify the full
application stack against it.
