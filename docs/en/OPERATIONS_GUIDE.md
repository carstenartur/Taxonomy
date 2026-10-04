# Operations Guide

Clustered analysis with external Artemis: [deployment, TLS/Secrets, worker scaling, HA and administration](#external-artemis-analysis-operations).

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

---

## External Artemis analysis operations

The default remains `local` transport with runtime role `all`. Clustered analysis
uses the same Taxonomy image with `coordinator` web pods and independently scaled
`worker` pods. The database owns operations, source authority, task effects,
cancellation and replay; the external broker owns delivery. No production
Taxonomy pod starts an embedded broker.

### Deploy a coordinator and worker sets

Use an external shared database and the same immutable image, destination prefix,
provider configuration and source authority across all pods. Do not use a private
HSQLDB database per pod. Worker-only mode loads exact root snapshots for its
configured roots instead of initializing the global catalogue/indexes. Only
coordinators serve the normal ingress. Adding workers does not automatically make
other web session or background-job state safe for multiple web replicas; the
existing `scaling.allowMultipleReplicas` acknowledgement still applies to web pods.

The chart's [Artemis overlay](../../deploy/helm/taxonomy/values-artemis.yaml) creates
one coordinator Deployment, a CP set with two replicas, and a set for the other
seven roots. This is an example, not a required topology. A set can own several
roots; zero replicas pauses that set. Keep at least one consumer for every root
that users may request, including relation target roots.

```bash
helm upgrade --install taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --values deploy/helm/taxonomy/values-artemis.yaml \
  --values /secure/config/taxonomy-site.yaml \
  --set existingSecret=taxonomy-secrets \
  --set image.digest=sha256:REPLACE_WITH_VERIFIED_IMAGE_DIGEST
```

The site file contains reviewed endpoints and policy selectors, not credentials.
The digest placeholder must be replaced with a real 64-character digest. Create
`taxonomy-artemis` out of band with `ARTEMIS_USER` and `ARTEMIS_PASSWORD`, using your
secret manager or protected files. Both keys are required Secret references; the
chart never creates credentials. Use a dedicated application broker role, not a
broker management account. Database/provider credentials use the existing
`existingSecret` / `secretEnv` mechanism.

Adjust one set in values and run the same Helm upgrade:

```yaml
analysis:
  workerSets:
    - name: cp
      replicas: 4
      shards: [CP]
      consumersPerShard: 1
    - name: general
      replicas: 1
      shards: [BP, BR, CI, CO, CR, IP, UA]
      consumersPerShard: 1
```

Helm lists replace rather than merge individual entries: keep the other sets in
your values file. Each worker consumes both subtaxonomy and relation queues for
its roots. `consumersPerShard` applies to each queue family; size HTTP capacity
with provider permits as well as pod resources. Give a hot set a `resources` block
to override common resources. Workers use ephemeral scratch volumes; durable
operations/results and immutable source snapshots belong in the shared database.
Do not share a writable Lucene volume between workers.

### TLS and network policy

Every TCP connector in a failover URL needs `sslEnabled=true`. Keep certificate
chain and hostname verification enabled; never use `trustAll=true` or
`verifyHost=false`. The chart rejects insecure visible URLs when `requireTls=true`.
Use `requireTls=false` only for an explicitly isolated disposable test network.
If your CA is not in the JVM trust store, put PKCS12/JKS stores in
`analysis.artemis.tlsSecret`; the chart mounts them read-only at
`/var/run/taxonomy-artemis`. Connector `trustStorePath`, `trustStoreType` and, for
mTLS, `keyStorePath`/`keyStoreType` refer to these mounted files.

When connector options contain a store password, store the **entire broker URL**
in an `ARTEMIS_URL` Secret key and configure:

```yaml
analysis:
  artemis:
    brokerUrl: ""
    brokerUrlSecretKey: ARTEMIS_URL
    existingSecret: taxonomy-artemis
    tlsSecret: taxonomy-artemis-tls
```

Secret contents cannot be inspected at Helm render time. Verify every connector's
TLS, hostname and trust settings when creating/rotating that Secret. Restart pods
after URL/credential rotation; a mounted store update alone does not recreate the
existing JMS connection factory. Never put passwords in `JAVA_OPTS`, ordinary
values, rendered evidence or shell history. See the upstream
[transport configuration](https://artemis.apache.org/components/artemis/documentation/latest/configuring-transports.html).

`analysis.artemis.egress` supplies destination **and** TCP-port rules to the web
and worker NetworkPolicies. Include all primary/backup connector destinations,
including any topology addresses advertised by the broker. DNS remains separately
allowed. With `allowSameNamespaceEgress=false`, add explicit database, provider,
OIDC, OTLP and other required peers through `networkPolicy.egress`. Kubernetes
NetworkPolicy has no FQDN resolver: use reviewed CIDRs or namespace/pod selectors,
or separately managed CNI FQDN policies. A policy render proves the rules exist;
only a CNI-enabled cluster test proves they are enforced.

Workers have no Service by default and reject inbound traffic. When the existing
ServiceMonitor is enabled, the chart adds internal ClusterIP metrics Services;
`analysis.workerMetricsIngressFrom` must identify the monitoring peers. The normal
web Service and ingress never select worker pods. No broker Service, admin port,
Hawtio route or Jolokia route is created by this chart.

### Broker configuration and delivery

[broker.xml](../../deploy/artemis/broker.xml) is a real Artemis configuration
example, parsed against the repository's broker dependency by
`ArtemisBrokerConfigurationTest`. Merge the address, ACL and delivery settings
into the externally operated broker. Configure protected TLS key material, JAAS
users/roles, durable disks and HA separately; the file is not a complete HA cluster
installation. Changing `destinationPrefix` requires updating the broker names and
ACL patterns too.

| Destination under `taxonomy.analysis` | Routing and retention |
|---|---|
| `subtaxonomy.BP` … `subtaxonomy.UA` | Eight durable anycast queues; one successful durable effect per deterministic task identity |
| `relation.BP` … `relation.UA` | Eight durable anycast queues routed by target root |
| `relation.general` | Compatibility queue for multi-root work; only full-root consumers can handle it |
| `completion` | Durable anycast coordinator queue; no broker expiry |
| `progress`, `control` | Multicast with per-process transient subscriptions; database replay/cancellation remains authoritative |
| `rejected` | Malformed, unsupported-schema or misrouted delivery evidence |
| `dlq`, `expiry` | Durable failure input queues; the coordinator settles known tasks as failed |
| `failed` | Content-free failure diagnostics for administrators after durable settlement |
| `provider-permits.<quota-group>` | Separately provisioned durable anycast tokens; no expiry or finite delivery-attempt limit |

The example bounds task redelivery to ten attempts with increasing delay and a
30-second cap. Subtaxonomy/relation deliveries expire after 24 hours if no JMS TTL
was set. These values are operational examples: align retention with application
deadlines, recovery windows and disk capacity. Expiry/DLQ arrival is a failure
signal, not successful task completion. The coordinator records known tasks as failed and forwards content-free diagnostics
to `failed`; the database retains the explicit terminal/incomplete state.
Investigate and repair the cause before selective recovery. Never rewrite tenant/source/task IDs to force delivery.
The broker pages to durable storage rather than dropping work when memory fills.
See [address settings](https://artemis.apache.org/components/artemis/documentation/latest/address-settings.html).

Task effects commit before delivery acknowledgement. A worker crash can cause
redelivery and replay of the recorded effect; it cannot guarantee exactly-once
external provider billing. Dispatch intent recovery runs at startup/reconnect or
explicit administrator repair (`POST /api/admin/analysis/dispatch/repair`), not
through a periodic pending-row scheduler. Cancellation commits in the database
before a live control event; a missed event cannot resurrect cancelled work.

### Provider concurrency permits

Enable `taxonomy.analysis.provider-permits.enabled` explicitly and map every HTTP
provider to a quota group. For example `provider-groups.openai=shared-openai` and
`provider-groups.custom-openai=shared-openai` share one upstream account's tokens.
Do not infer quota ownership from display names. Provision exactly N durable
messages in the group's new queue with the separate one-shot provisioner **before
starting consumers**. The application never seeds tokens on startup, and an
existing queue must never be topped up from its current available-message count:
some tokens may be held in open transactions.

For Helm, use an explicit JSON map to preserve provider names such as
`custom-openai`; plain environment-variable map keys can lose separators. Merge
this into any existing JSON object and keep chart-owned role/transport settings
in `analysis` values:

```yaml
config:
  SPRING_APPLICATION_JSON: >-
    {"taxonomy":{"analysis":{"provider-permits":{"enabled":true,
    "provider-groups":{"openai":"shared-openai","custom-openai":"shared-openai"}}}}}
```

Run the provisioner from the packaged application with the separate provisioning
account in `TAXONOMY_ANALYSIS_ARTEMIS_BROKER_URL`, `TAXONOMY_ANALYSIS_ARTEMIS_USER`
and `TAXONOMY_ANALYSIS_ARTEMIS_PASSWORD` environment variables. The TLS requirement
and optional `TAXONOMY_ANALYSIS_PROVIDER_PERMITS_DESTINATION_PREFIX` apply as usual.
For two permits in `shared-openai` (adjust the jar path outside the image):

```bash
java -Dloader.main=com.taxonomy.composition.analysis.artemis.ArtemisProviderPermitProvisioner \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher shared-openai 2
```

Keep permit queues outside task expiry/finite-redelivery policy. The example
sets no expiry, unlimited delivery attempts, no auto-delete and no purge on zero
consumers. The broker transaction timeout must exceed the maximum physical HTTP
exchange; review the example's 900 seconds against provider timeouts. On holder
loss the unacknowledged token becomes available again. Inspect an interrupted
initial provisioning operation with all consumers stopped; a queue created before
token commit may be empty and needs deliberate recreation. Concurrency tokens do
not provide cluster-wide RPM, TPM or RPD; existing local rate limits remain.

### Readiness, observability and administration

| Signal | Meaning |
|---|---|
| Web `/actuator/health/readiness` | `readinessState,taxonomy`: editor/catalogue usability stays available during broker outage |
| Worker `/actuator/health/readiness` | `readinessState,analysisBroker`: a connected broker, without waiting for the global catalogue |
| `/actuator/health/broker` | Broker-only health group; details remain subject to Actuator authorization |
| `/actuator/health/liveness` | Process liveness; a broker outage must not trigger restart loops |

Worker readiness does **not** prove provider credentials, quota tokens, DNS/TLS to
the model service, model availability, or a successful inference. Validate provider
configuration through the existing authorized provider checks without making an
unapproved paid request. A disconnected worker may still finish already received
work while the durable protocol preserves recovery.

The existing authenticated `/actuator/prometheus` ServiceMonitor covers web and
worker metrics Services. Keep the distinct `ADMIN_TOKEN` machine credential;
never reuse the interactive login password. Scrape external broker metrics using
its own exporter/metrics plugin and separately managed monitor. Alert on shard
queue depth with zero consumers, sustained delivering/redelivering messages,
expiry/DLQ/rejected growth, connection failures and paging disk pressure. For
permit queues compare available plus delivering tokens with provisioned capacity.
Application operation/task/phase/root and latency metrics describe durable work;
broker metrics describe delivery. Avoid operation/user IDs as metric labels.
See [broker metrics](https://artemis.apache.org/components/artemis/documentation/latest/metrics.html).

Hawtio/Jolokia are administrative surfaces. Bind the broker console to a private
interface, protect it with TLS and authenticated admin roles, and restrict
management NetworkPolicy/firewall access to an admin network or authenticated
port-forward. Configure JAAS and console/Jolokia authorization explicitly; deny
application users `manage`/admin roles. Keep Hawtio's proxy allowlist and Jolokia
origin policy restricted. Do not route 8161, 8778 or the broker management context
through Taxonomy's public ingress. Routine monitoring should use counters, not
message-body browsing or prompt/response capture. See
[broker security](https://artemis.apache.org/components/artemis/documentation/latest/security.html)
and [management console](https://artemis.apache.org/components/artemis/documentation/latest/management-console.html).

### HA, upgrades and recovery exercises

Operate a primary/backup Artemis pair with durable journal/bindings/paging/large
message storage, either using the supported shared-store or replicated HA policy.
Configure quorum/fencing and network partition behavior for your chosen policy.
Two independent brokers behind a load balancer are not a shared durable queue or
permit pool. A failover URL only supplies endpoints; it does not replicate their
state. Keep one logical authority for each permit queue during failover. Test
backup activation and failback with TLS and real client-advertised hostnames.
See [Artemis HA](https://artemis.apache.org/components/artemis/documentation/latest/ha.html).

`Recreate` applies to each Deployment independently. It does not stop all worker
sets before the coordinator migrates the shared database. For upgrades without
explicit cross-version schema compatibility, stop admission, cancel/drain or
record running work, scale all Taxonomy Deployments to zero, confirm termination,
back up the database and broker, then deploy the new image to every role. Resume
after migrations/readiness and inspect redelivery/dispatch repair evidence. Never
combine a restored older database with a newer broker journal without reconciling
operation/task authority. Rolling upgrades require the existing explicit
release-specific compatibility acknowledgement.

The Maven-owned `HelmArtemisContractTest` renders local, distributed and constrained
profiles, checks independent CP scaling, TLS/Secret handling, web/worker selector
isolation, metrics and restricted broker egress. `ArtemisBrokerConfigurationTest`
checks the actual XML against destination names and broker parsing. Run:

```bash
./mvnw -B -pl taxonomy-build -am test \
  -Dtest=HelmArtemisContractTest,HelmConstrainedSmokeContractTest,ArtemisBrokerConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
bash deploy/helm/taxonomy/verify.sh
```

These checks do not replace the existing #638 live constrained-cluster smoke or
multi-pod failure acceptance. The existing single-pod smoke quota is not a sizing
promise for this example's four application pods plus external dependencies. In
a disposable cluster with a NetworkPolicy-enforcing CNI, external database and HA
broker, retain evidence of independent CP scaling, CP worker loss while IP
progresses, broker restart/failover, coordinator loss, SSE reconnect to another web
pod, bounded expiry/DLQ, permit recovery, and denied non-broker/admin egress. Use a
deterministic provider and actual catalogue roots. Record command/image/chart
versions, replica/resources, source commit, timings and outcomes without secrets
or payload bodies; do not report render-only checks as a successful live exercise.
