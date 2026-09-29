# Operations Guide

This document provides operational procedures for running the Taxonomy Architecture Analyzer in production environments.

---

## Table of Contents

1. [System Requirements](#system-requirements)
2. [Health Checks](#health-checks)
3. [Monitoring](#monitoring)
4. [Logging](#logging)
5. [Backup and Recovery](#backup-and-recovery)
6. [Database Maintenance](#database-maintenance)
7. [Lucene Index Management](#lucene-index-management)
8. [JGit Repository Maintenance](#jgit-repository-maintenance)
9. [Scaling Considerations](#scaling-considerations)
10. [Troubleshooting](#troubleshooting)

---

## System Requirements

### Minimum (Development / Small Teams)

| Resource | Requirement |
|---|---|
| **CPU** | 2 cores |
| **RAM** | 512 MB (without embedding), 1 GB (with embedding) |
| **Disk** | 500 MB (application + data) |
| **Java** | 21+ (JRE) |

### Recommended (Production / 10+ Users)

| Resource | Requirement |
|---|---|
| **CPU** | 4 cores |
| **RAM** | 2–4 GB |
| **Disk** | 5 GB (SSD recommended for Lucene index) |
| **Database** | PostgreSQL 16+ (external) |

---

## Health Checks

### Endpoints

| Endpoint | Auth Required | Purpose |
|---|---|---|
| `GET /api/status/startup` | No | Returns 200 once the application is accepting connections. Use as Docker/Kubernetes health check. |
| `GET /actuator/health` | No (summary), Yes (details) | Spring Boot health indicator — aggregates database, disk, Lucene status |
| `GET /actuator/health/liveness` | No | Kubernetes liveness probe |
| `GET /actuator/health/readiness` | No | Kubernetes readiness probe |
| `GET /api/ai-status` | Yes | LLM provider availability (connected / degraded / unavailable) |
| `GET /api/embedding/status` | Yes | Local embedding model status |

### Docker Health Check

```dockerfile
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q --spider http://localhost:8080/api/status/startup || exit 1
```

### Kubernetes Probes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 30
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
```

---

## Monitoring

### Prometheus Metrics

The application exposes Prometheus-compatible metrics at:

```
GET /actuator/prometheus
```

Key metrics to monitor:

| Metric | Description | Alert Threshold |
|---|---|---|
| `http_server_requests_seconds_count` | Request count by endpoint and status | Error rate > 5% |
| `http_server_requests_seconds_sum` | Total request duration | P99 > 5s |
| `jvm_memory_used_bytes` | JVM heap usage | > 80% of max |
| `jvm_threads_live_threads` | Active thread count | > 200 |
| `hibernate_sessions_open_total` | Database session count | Sustained high count |
| `process_cpu_usage` | CPU utilization | > 80% sustained |
| `disk_free_bytes` | Available disk space | < 500 MB |

### Grafana Dashboard

Import the Prometheus data source and create panels for:

1. **Request Rate** — `rate(http_server_requests_seconds_count[5m])`
2. **Error Rate** — `rate(http_server_requests_seconds_count{status=~"5.."}[5m])`
3. **Response Time** — `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))`
4. **JVM Heap** — `jvm_memory_used_bytes{area="heap"}`
5. **Active Sessions** — `hibernate_sessions_open_total`

---

## Logging

### Log Format

The application uses SLF4J/Logback with the Spring Boot default format:

```
2026-03-15 10:30:00.000  INFO 1234 --- [main] c.t.service.TaxonomyService : Taxonomy loaded: 2500 nodes
```

### Security Audit Events

When `TAXONOMY_AUDIT_LOGGING=true` (default in production profile):

```
LOGIN_SUCCESS user=admin ip=192.168.1.100
LOGIN_FAILED user=unknown ip=10.0.0.1
USER_CREATED user=analyst roles=[USER] by=admin
```

### Log Rotation

Configure log rotation in the container or host:

**Docker (recommended):**

```yaml
# docker-compose.yml
services:
  taxonomy:
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "5"
```

**Logback (application-level):**

Create `logback-spring.xml` in `src/main/resources/` if file-based logging is required:

```xml
<configuration>
  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>/app/logs/taxonomy.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>/app/logs/taxonomy.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>30</maxHistory>
      <totalSizeCap>500MB</totalSizeCap>
    </rollingPolicy>
    <encoder>
      <pattern>%d{ISO8601} %level [%thread] %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="FILE" />
  </root>
</configuration>
```

### Log Levels

Adjust logging levels via environment variables:

```bash
LOGGING_LEVEL_COM_TAXONOMY=DEBUG       # Application debug logging
LOGGING_LEVEL_ORG_HIBERNATE=WARN       # Reduce Hibernate noise
LOGGING_LEVEL_ORG_ECLIPSE_JGIT=INFO    # JGit operations
```

---

## Backup and Recovery

### What to Back Up

| Component | Location | Method |
|---|---|---|
| **Authoritative database, including JGit packs, refs and reflog** | PostgreSQL for external production; `/app/data/taxonomydb*` for file-backed HSQLDB | Database-native consistent backup, or stop all writers before an HSQLDB volume snapshot |
| **Lucene indexes** | `TAXONOMY_SEARCH_DIRECTORY_ROOT` when filesystem-backed | Consistent snapshot alongside the database; test restored search |
| **Configuration and secrets** | Deployment-specific secret store, external configuration | Protected backup with separately managed access |
| **Source/provenance files** | Deployment-specific, if used | Include with the same recovery point |

For PostgreSQL, use a database-native backup against the primary database. Stop
all application writers for a coordinated restore; restore into an isolated,
empty target database with the appropriate database-native tooling, then verify
schema history and application data. Do not treat a filesystem volume snapshot
as a backup of an external PostgreSQL database. The database also contains the
canonical JGit objects and history; there is no separate `/app/data/git` store.

### Production Compose / file-backed HSQLDB

Use the [container image backup and restore procedure](CONTAINER_IMAGE.md#5-persistence-and-backup).
It resolves the actual Compose service mount rather than creating a bare,
project-independent `taxonomy-data` volume. Stop every process that can write
the HSQLDB files before archiving and test extraction into a new empty volume
on a separate instance. Back up the deployment secrets separately.

### Recovery procedure

1. Stop all writers and restore a complete database backup to an isolated instance.
2. Restore a matching filesystem index snapshot when that deployment stores Lucene on disk; do not mix backup times.
3. Start the isolated instance and inspect its readiness and application logs.
4. Verify login/roles, workspaces, taxonomy, DSL branches and commit traversal, and representative search and export results.
5. Promote only a tested recovery point under the deployment's change procedure.

A missing index is **not** guaranteed to rebuild completely at startup. The
commit-index startup task reindexes its scope only when the relational commit
projection is empty. Taxonomy node/relation embedding reindexing requires the
local ONNX embedding path to be enabled. Consult the release-specific index
recovery procedure before deleting or replacing any index files.

---

## Database Maintenance

### HSQLDB

HSQLDB uses in-memory mode by default for development, losing data on restart.
The production Compose baseline instead uses a file-backed HSQLDB database;
back it up with all writers stopped using the procedure above.

### PostgreSQL

```sql
-- Check database size
SELECT pg_size_pretty(pg_database_size('taxonomy'));

-- Vacuum and analyze (run weekly or after bulk operations)
VACUUM ANALYZE;

-- Check for long-running queries
SELECT pid, now() - pg_stat_activity.query_start AS duration, query
FROM pg_stat_activity
WHERE state = 'active' AND now() - pg_stat_activity.query_start > interval '5 minutes';
```

### Connection Pool Monitoring

Monitor via Actuator with a distinct configured machine token (do not paste a login password into shell history):

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/hikaricp.connections.active
```

---

## Lucene Index Management

### Index Location

- **Development (in-memory):** `local-heap` — no disk I/O, lost on restart
- **Production (persistent):** `local-filesystem` at `TAXONOMY_SEARCH_DIRECTORY_ROOT` (default: `/app/data/lucene-index`)

### Index recovery

Do not delete `/app/data/lucene-index/*` expecting an automatic full rebuild.
Restore a matching snapshot and verify representative search. The commit
search scope is rebuilt on startup only when its relational projection is
empty; local node/relation vector indexing runs only with embeddings enabled
and `LLM_PROVIDER=LOCAL_ONNX`. Other indexed entities need a verified,
release-specific rebuild path before discarding their on-disk indexes.

### Index Size

Typical index size for ~2,500 taxonomy nodes: **5–20 MB** (depending on relation count and analysis data).

---

## JGit Repository Maintenance

### Database-backed history

Architecture DSL objects, refs and reflog are stored through JGit Core in the
configured relational database, including `git_packs` and `git_reflog`.
Protect them through database backup and verify refs, commit traversal and
pack contents after a restore. Filesystem `git gc` and `git bundle` against
`/app/data/git` do not maintain or back up this store.

### Remote Replication

If `TAXONOMY_DSL_REMOTE_URL` is configured, DSL commits are pushed to a remote Git repository. Monitor push failures in the application logs.

---

## Scaling Considerations

### Single Instance

The application is designed for single-instance deployment. Multi-user support is handled via workspace isolation within the same JVM.

### Performance Tuning

| Parameter | Environment Variable | Default | Recommendation |
|---|---|---|---|
| JVM Heap | `JAVA_OPTS` | 220 MB | 1–2 GB for production |
| LLM Rate Limit | `TAXONOMY_LLM_RPM` | 5 | Match provider quota |
| Incoming LLM quota | `TAXONOMY_RATE_LIMIT_PER_MINUTE` | 10 | Size per authenticated identity and per application instance |
| JDBC Batch Size | `spring.jpa.properties.hibernate.jdbc.batch_size` | 50 | Suitable for most workloads |

The incoming LLM quota is held in memory per application instance. Local usernames and immutable Keycloak `iss`/`sub` identities receive separate budgets. When enabling multiple replicas, use a distributed ingress quota for a cluster-wide ceiling or account for the aggregate allowance increasing with replica count.

### Reverse Proxy

Always deploy behind a reverse proxy (nginx, Caddy, HAProxy) for:

- TLS termination
- Request buffering
- Connection limiting
- Static asset caching

Example nginx configuration:

```nginx
server {
    listen 443 ssl;
    server_name taxonomy.example.gov;

    ssl_certificate     /etc/ssl/certs/taxonomy.crt;
    ssl_certificate_key /etc/ssl/private/taxonomy.key;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

---

## Troubleshooting

### Application Won't Start

| Symptom | Likely Cause | Fix |
|---|---|---|
| `OutOfMemoryError` | Insufficient heap | Increase `-Xmx` in `JAVA_OPTS` |
| `Connection refused` (database) | Database not running | Check database service and `TAXONOMY_DATASOURCE_URL` |
| Port already in use | Another process on 8080 | Change `PORT` environment variable |
| `ClassNotFoundException` | Wrong Java version | Ensure Java 21+ |

### Slow Analysis

| Symptom | Likely Cause | Fix |
|---|---|---|
| Analysis takes > 30s | LLM timeout | Increase `TAXONOMY_LLM_TIMEOUT_SECONDS` |
| Rate limit errors | Too many requests | Reduce concurrent users or increase `TAXONOMY_LLM_RPM` |
| Embedding model slow | First-time download | Pre-download model via `TAXONOMY_EMBEDDING_MODEL_DIR` |

### Search Not Working

1. Check embedding model status: `GET /api/embedding/status`
2. Verify the index backup and the release-specific recovery path; a restart does not guarantee full reindexing
3. Check logs for `HSEARCH` errors

---

## Related Documentation

- [Deployment Guide](DEPLOYMENT_GUIDE.md) — initial deployment instructions
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — government deployment checklist
- [Configuration Reference](CONFIGURATION_REFERENCE.md) — all environment variables
- [Security](SECURITY.md) — security architecture and hardening
