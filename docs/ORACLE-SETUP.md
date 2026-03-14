# Oracle Database Setup Guide

This guide explains how to run the Taxonomy Architecture Analyzer with
**Oracle Database** as the database backend.

---

## Prerequisites

- Oracle Database 23c Free, or Oracle Database 19c or later (including Oracle
  Cloud Autonomous Database)
- Docker (for the Quick Start below) **or** an existing Oracle instance
- Java 17+ (for building from source)

---

## Quick Start with Docker Compose

The fastest way to try Oracle:

```bash
docker compose -f docker-compose-oracle.yml up
# Open http://localhost:8080
```

This starts an Oracle Database 23c Free container alongside the Taxonomy
application. Data is persisted in a Docker volume (`oracle-data`).

> **Note:** The Oracle container may take 1–2 minutes to start on the first run
> while it initialises the database. The `gvenzl/oracle-free:23-slim-faststart`
> image is optimised for fast startup compared to the full Oracle images.

To tear down and remove data:

```bash
docker compose -f docker-compose-oracle.yml down -v
```

---

## Environment Variables

Activate Oracle via the `oracle` Spring profile:

| Variable | Required | Default | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Yes** | `hsqldb` | Set to `oracle` to activate the Oracle profile |
| `TAXONOMY_DATASOURCE_URL` | No | `jdbc:oracle:thin:@localhost:1521/taxonomy` | JDBC URL — override host/port/service as needed |
| `SPRING_DATASOURCE_USERNAME` | No | `taxonomy` | Oracle login |
| `SPRING_DATASOURCE_PASSWORD` | No | `taxonomy` | Oracle password |
| `TAXONOMY_DDL_AUTO` | No | `create` | Schema strategy: `create` (rebuild each start), `update` (incremental), `validate` (read-only) |

### Example: Connecting to an Existing Oracle Server

```bash
export SPRING_PROFILES_ACTIVE=oracle
export TAXONOMY_DATASOURCE_URL="jdbc:oracle:thin:@myserver.example.com:1521/ORCLPDB1"
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

Oracle Database 23c uses **AL32UTF8** as the default character set, which
supports the full Unicode range. All string fields (including multilingual
taxonomy descriptions in English and German) are stored correctly.

Hibernate's `@Nationalized` annotation maps to `NVARCHAR2` / `NCLOB` columns on
Oracle, ensuring correct Unicode storage regardless of the database character set.

Large text fields (`descriptionEn`, `descriptionDe`, `reference`) use
`@Lob` + `@Nationalized`, which produces `NCLOB` on Oracle.

---

## HikariCP Connection Pool

The `oracle` profile configures HikariCP (the default Spring Boot pool):

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

**Symptom:** `IO Error: The Network Adapter could not establish the connection`

**Cause:** Oracle is not running or not listening on the expected host/port.

**Fix:** Verify that Oracle is running and the listener is active:
```bash
# Using Docker
docker compose -f docker-compose-oracle.yml ps

# Using tnsping (if available)
tnsping localhost:1521/taxonomy
```
If using Docker Compose, ensure the `db` service is healthy before the app
starts (the provided `docker-compose-oracle.yml` handles this automatically).

### ORA-01017: Invalid Username/Password

**Symptom:** `ORA-01017: invalid username/password; logon denied`

**Cause:** The username or password does not match the Oracle configuration.

**Fix:** Verify that `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`
match the credentials configured in Oracle. The Docker Compose example uses
`taxonomy` / `taxonomy`.

### Service Name vs SID

**Symptom:** `ORA-12514: TNS:listener does not currently know of service requested`

**Cause:** The JDBC URL uses the wrong service name or SID.

**Fix:** Ensure the URL uses the correct service name. The `gvenzl/oracle-free`
Docker image creates a pluggable database with the service name specified by
`ORACLE_DATABASE`. For the default setup, this is `taxonomy`:
```
jdbc:oracle:thin:@localhost:1521/taxonomy
```

For Oracle instances using a SID instead of a service name, use:
```
jdbc:oracle:thin:@localhost:1521:ORCL
```

### Oracle Reserved Words

Oracle reserves certain SQL keywords (e.g., `LEVEL`, `COMMENT`, `USER`) that
cannot be used as unquoted column names. The application maps conflicting Java
field names to safe column names via `@Column(name = "...")` annotations — for
example, `TaxonomyNode.level` is mapped to the column `node_level`.

If you add new entity fields, check
[Oracle Reserved Words](https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/Oracle-SQL-Reserved-Words.html)
to ensure the column name is not reserved.

### Slow Container Startup

**Symptom:** The Oracle container takes a long time to start.

**Cause:** Oracle Database requires significant initialization time.

**Fix:** The `gvenzl/oracle-free:23-slim-faststart` image is optimised for fast
startup. The Docker Compose healthcheck has 20 retries (up to ~200 seconds) to
accommodate this. If startup still times out, increase the `retries` value in
`docker-compose-oracle.yml`.

---

## Running Oracle Integration Tests

```bash
# Build the application JAR first
mvn package -DskipTests

# Run Oracle tests (requires Docker)
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*Oracle*IT"
```

These tests start an Oracle container via Testcontainers and verify the full
application stack against it.
