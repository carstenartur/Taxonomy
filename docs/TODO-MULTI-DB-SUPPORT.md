# TODO: Full Multi-Database Support

## Status: Planned

The application currently defaults to HSQLDB and has initial integration tests for PostgreSQL, Microsoft SQL Server, and Oracle. Full multi-database support is planned but not yet complete.

## Scope

Full multi-database support means end-to-end coverage across:

### Application Layer
- [ ] Database-specific Hibernate dialect auto-detection
- [ ] Connection pool configuration per database type
- [ ] DDL generation validation for all supported databases
- [ ] Database-specific SQL compatibility (reserved words, identifier length limits, LOB handling, `@Nationalized` support)

### Testing
- [ ] All external database integration tests passing in CI (PostgreSQL, MSSQL, Oracle)
- [ ] Fix Oracle integration tests (currently returning HTTP 500/503 — likely DDL or dialect issues)
- [ ] Fix MSSQL integration tests (currently failing with prelogin connection errors in CI)
- [ ] PostgreSQL integration tests verified
- [ ] Add database-specific CI workflow (matrix strategy for each DB)

### GUI / Frontend
- [ ] Database connection status indicator in the UI
- [ ] Admin panel showing active database type and version
- [ ] Database-specific configuration guidance in the UI

### Documentation
- [ ] Configuration guide for each supported database
- [ ] Docker Compose examples for each database
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
