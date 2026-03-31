# TODO: Multi-Database Support

## Status: In Progress

HSQLDB (default), PostgreSQL, and MSSQL are supported with integration tests
and CI. Oracle has initial tests but is not fully verified.

## Open Items

### P1 — CI Stability
- [ ] All external database integration tests passing in CI (PostgreSQL, MSSQL, Oracle)

### P2 — DDL Validation
- [ ] DDL generation validation for all supported databases

### P3 — Documentation
- [ ] Migration guide from HSQLDB to production databases

## Test Execution

| Tag | Database |
|---|---|
| `db-postgres` | PostgreSQL |
| `db-mssql` | Microsoft SQL Server |
| `db-oracle` | Oracle Database |

```bash
mvn verify -DexcludedGroups=real-llm -Dit.test="*Postgres*IT"
mvn verify -DexcludedGroups=real-llm -Dit.test="*Mssql*IT"
mvn verify -DexcludedGroups=real-llm -Dit.test="*Oracle*IT"
```
