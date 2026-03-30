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

| Component | Location | Frequency | Method |
|---|---|---|---|
| **Database** | PostgreSQL / MSSQL / Oracle | Daily | `pg_dump`, SQL Server backup, RMAN |
| **Lucene index** | `/app/data/lucene-index` | Daily | File-system snapshot |
| **JGit repository** | `/app/data/git` | Daily | File-system snapshot or `git bundle` |
| **Configuration** | Environment variables | On change | Version-controlled `.env` file |
| **Uploaded data** | `/app/data/uploads` (if applicable) | Daily | File-system snapshot |

### Database Backup (PostgreSQL)

```bash
# Automated daily backup
pg_dump -h localhost -U taxonomy -d taxonomy -F c -f /backup/taxonomy-$(date +%Y%m%d).dump

# Restore
pg_restore -h localhost -U taxonomy -d taxonomy /backup/taxonomy-20260315.dump
```

### Full Application Backup (Docker Volume)

```bash
# Stop the container
docker stop taxonomy-analyzer

# Backup the data volume
docker run --rm -v taxonomy-data:/data -v /backup:/backup \
  alpine tar czf /backup/taxonomy-data-$(date +%Y%m%d).tar.gz /data

# Restart
docker start taxonomy-analyzer
```

### Recovery Procedure

1. Stop the application
2. Restore the database from the latest backup
3. Restore the Lucene index directory (or let the application rebuild it on startup)
4. Restore the JGit repository directory
5. Start the application
6. Verify via `GET /api/status/startup` and `GET /actuator/health`
7. Verify taxonomy data via `GET /api/taxonomy`

> **Note:** The Lucene index is rebuilt automatically from the database on startup if it is missing. Database backup is the critical recovery path.

---

## Database Maintenance

### HSQLDB (Development Only)

HSQLDB uses in-memory mode by default. No maintenance required. Data is lost on restart.

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

Monitor via Actuator:

```bash
curl -u admin:password http://localhost:8080/actuator/metrics/hikaricp.connections.active
```

---

## Lucene Index Management

### Index Location

- **Development (in-memory):** `local-heap` — no disk I/O, lost on restart
- **Production (persistent):** `local-filesystem` at `TAXONOMY_SEARCH_DIRECTORY_ROOT` (default: `/app/data/lucene-index`)

### Rebuild Index

The index is rebuilt automatically on application startup from the database. To force a rebuild:

1. Stop the application
2. Delete the index directory: `rm -rf /app/data/lucene-index/*`
3. Start the application — index is rebuilt during initialization

### Index Size

Typical index size for ~2,500 taxonomy nodes: **5–20 MB** (depending on relation count and analysis data).

---

## JGit Repository Maintenance

### Repository Location

The JGit repository stores DSL versions, branches, and merge history at the path configured by the application (typically `/app/data/git`).

### Garbage Collection

JGit repositories accumulate loose objects over time. Run periodic garbage collection:

```bash
cd /app/data/git
git gc --aggressive --prune=now
```

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
| API Rate Limit | `TAXONOMY_RATE_LIMIT_PER_MINUTE` | 10 | Increase for high traffic |
| JDBC Batch Size | `spring.jpa.properties.hibernate.jdbc.batch_size` | 50 | Suitable for most workloads |

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
2. Check Lucene index: restart application to trigger re-index
3. Check logs for `HSEARCH` errors

---

## Related Documentation

- [Deployment Guide](DEPLOYMENT_GUIDE.md) — initial deployment instructions
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — government deployment checklist
- [Configuration Reference](CONFIGURATION_REFERENCE.md) — all environment variables
- [Security](SECURITY.md) — security architecture and hardening
