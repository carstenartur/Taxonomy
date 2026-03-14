# TODO: Full Multi-Database Support

## Status: In Progress

The application defaults to HSQLDB and supports **Spring profile-based** database
switching. MSSQL and PostgreSQL support is implemented with integration tests,
CI workflow, Docker Compose, and documentation. Oracle has initial integration
tests but is not yet fully verified.

## Scope

Full multi-database support means end-to-end coverage across:

### Application Layer
- [x] Database-specific Hibernate dialect auto-detection (via Spring profiles: `hsqldb`, `mssql`, `postgres`, `oracle`)
- [x] Connection pool configuration per database type (HikariCP for MSSQL/PostgreSQL/Oracle, SimpleDriverDataSource for HSQLDB)
- [ ] DDL generation validation for all supported databases
- [x] Database-specific SQL compatibility (`@Nationalized` → `nvarchar`, `@Lob` → `nvarchar(max)` / `varbinary(max)`)

### Testing
- [ ] All external database integration tests passing in CI (PostgreSQL, MSSQL, Oracle)
- [x] Fix Oracle integration tests (Spring profile `oracle` + application-oracle.properties)
- [x] Fix MSSQL integration tests (pinned container image, added `loginTimeout`, Spring profile)
- [x] PostgreSQL integration tests verified
- [x] Add database-specific CI workflow (MSSQL, PostgreSQL, and Oracle in `ci-cd.yml`)

### GUI / Frontend
- [ ] Database connection status indicator in the UI
- [ ] Admin panel showing active database type and version
- [ ] Database-specific configuration guidance in the UI

### Documentation
- [x] Configuration guide for MSSQL (`docs/MSSQL-SETUP.md`)
- [x] Configuration guide for PostgreSQL (`docs/POSTGRESQL-SETUP.md`)
- [x] Configuration guide for Oracle (`docs/ORACLE-SETUP.md`)
- [x] Docker Compose examples for each database (`docker-compose-mssql.yml`, `docker-compose-postgres.yml`, `docker-compose-oracle.yml`)
- [ ] Migration guide from HSQLDB to production databases
- [ ] Performance considerations per database

### Future Databases (Potential)
- [ ] MariaDB / MySQL
- [ ] H2 (as alternative embedded DB)
- [ ] CockroachDB / YugabyteDB (distributed SQL)

## JUnit Test Tags

Each database has its own JUnit tag for targeted test execution:

| Tag | Database |
|---|---|
| `db-postgres` | PostgreSQL |
| `db-mssql` | Microsoft SQL Server |
| `db-oracle` | Oracle Database |
| `real-llm` | Real LLM API calls (not database-related) |

## How to Run Individual Database Tests

```bash
# PostgreSQL only
mvn verify -DexcludedGroups=real-llm -Dit.test="*Postgres*IT"

# MSSQL only
mvn verify -DexcludedGroups=real-llm -Dit.test="*Mssql*IT"

# Oracle only
mvn verify -DexcludedGroups=real-llm -Dit.test="*Oracle*IT"
```
