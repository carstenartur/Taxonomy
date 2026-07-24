# Taxonomy Architecture Analyzer — Container Image

The official container image is published to **GitHub Container Registry (GHCR)** after a successful default-branch or release-tag build:

```text
ghcr.io/carstenartur/taxonomy
```

> **GHCR is the only official registry.** Do not use images from other registries unless you built and published them yourself.

## 1. Image tags

| Tag | Example | When updated | Use case |
|---|---|---|---|
| `latest` | `ghcr.io/carstenartur/taxonomy:latest` | Successful push to `main` | Local evaluation |
| `main` | `ghcr.io/carstenartur/taxonomy:main` | Successful push to `main` | Default-branch tracking |
| `vX.Y.Z` | `ghcr.io/carstenartur/taxonomy:v1.2.6` | Successful release-tag build | Versioned release |
| `sha-<hash>` | `ghcr.io/carstenartur/taxonomy:sha-abc1234` | Successful published build | Immutable deployment reference |

Use a digest-pinned image or a verified `sha-` tag for production. Feature branches do not publish application images.

## 2. Quick start with the published image

For local evaluation only:

```bash
docker pull ghcr.io/carstenartur/taxonomy:latest
docker run --rm -p 8080:8080 ghcr.io/carstenartur/taxonomy:latest
```

Open <http://localhost:8080>. The development default is `admin` / `admin`; never expose that configuration to a network.

### Cloud LLM

```bash
docker run --rm -p 8080:8080 \
  -e GEMINI_API_KEY=your-gemini-api-key \
  ghcr.io/carstenartur/taxonomy:latest
```

### Persistent HSQLDB data

A volume alone is not sufficient: point HSQLDB at a file below the mounted directory and use the persistent schema mode.

```bash
docker run -d --name taxonomy-analyzer \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=production,hsqldb \
  -e TAXONOMY_ADMIN_PASSWORD=replace-with-a-strong-password \
  -e TAXONOMY_DATASOURCE_URL='jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached;hsqldb.write_delay_millis=0;shutdown=true' \
  -e TAXONOMY_DDL_AUTO=update \
  -v taxonomy-data:/app/data \
  ghcr.io/carstenartur/taxonomy:latest
```

Port 8080 is plain HTTP. Use a TLS-terminating reverse proxy for any non-local deployment.

## 3. Recommended Docker Compose setup

The repository includes [`docker-compose.prod.yml`](../../docker-compose.prod.yml), which runs Taxonomy behind Caddy with automatic HTTPS.

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy
cp .env.example .env
# Edit .env: domain, administrator password, AI settings and build credentials.
docker compose -f docker-compose.prod.yml up -d --build
```

The local source build consumes `jgit-storage-hibernate-core` from GitHub Packages. Set these values in `.env` or export them in the shell:

```bash
GITHUB_ACTOR=your-github-username
GITHUB_TOKEN=token-with-read-packages
```

The token needs `read:packages`. Compose passes both values to the Dockerfile as BuildKit secrets; they are available only while Maven resolves dependencies and are not stored as build arguments or image layers.

The stack contains:

| Service | Purpose |
|---|---|
| `taxonomy` | Application on internal port 8080, built from the checked-out source |
| `caddy` | HTTPS reverse proxy on ports 80 and 443 |

### Use the published image instead

A production deployment can avoid source-build credentials by replacing the `build` block with an immutable published image reference:

```yaml
services:
  taxonomy:
    image: ghcr.io/carstenartur/taxonomy@sha256:<verified-digest>
```

Remove the complete `build:` block, including its `secrets:` entries, when using an image.

### Database-specific Compose files

| File | Database / purpose |
|---|---|
| `docker-compose-postgres.yml` | PostgreSQL 16 |
| `docker-compose-mssql.yml` | Microsoft SQL Server 2022 |
| `docker-compose-oracle.yml` | Oracle Database 23 |
| `docker-compose-keycloak.yml` | PostgreSQL plus Keycloak/OIDC |
| `docker-compose.integration-test.yml` | Two Taxonomy instances plus Gitea |

All source-building Compose files use the same `GITHUB_ACTOR` and `GITHUB_TOKEN` BuildKit secrets.

## 4. Environment variables

Runtime variables:

| Variable | Default | Description |
|---|---|---|
| `TAXONOMY_ADMIN_PASSWORD` | `admin` outside production | Initial local administrator password |
| `LLM_PROVIDER` | auto-detected | `GEMINI`, `OPENAI`, `DEEPSEEK`, `QWEN`, `LLAMA`, `MISTRAL` or `LOCAL_ONNX` |
| `GEMINI_API_KEY`, `OPENAI_API_KEY`, … | empty | Provider credentials |
| `TAXONOMY_EMBEDDING_ENABLED` | `true` | Enable semantic/KNN search |
| `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD` | profile-dependent | Permit runtime model download |
| `TAXONOMY_DATASOURCE_URL` | in-memory HSQLDB | JDBC URL; set a file URL for persistent HSQLDB |
| `TAXONOMY_DDL_AUTO` | `create` | Hibernate management for Taxonomy-owned tables; persistent deployments normally use `update` |
| `TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION` | `false` | One-time opt-in for verified pre-library JGit storage adoption |
| `JAVA_OPTS` | Dockerfile defaults | JVM heap, GC and stack settings |

Build-only variables:

| Variable | Required for source build | Description |
|---|---:|---|
| `GITHUB_ACTOR` | yes | GitHub Packages username |
| `GITHUB_TOKEN` | yes | Token with `read:packages` |

See [Configuration Reference](CONFIGURATION_REFERENCE.md) for the full application property set and [Hibernate-backed JGit storage](JGIT_STORAGE_HIBERNATE.md) for the storage contract.

## 5. Persistence and backup

The production Compose stack stores mutable state below `/app/data`:

| Path | Contents |
|---|---|
| `/app/data/taxonomydb*` | File-backed HSQLDB catalogue, users and Git pack/ref/reflog data |
| `/app/data/lucene-index` | Hibernate Search/Lucene indexes |

Named volumes configured by `docker-compose.prod.yml` survive container recreation. Stop writers before taking a filesystem-level backup:

```bash
docker compose -f docker-compose.prod.yml stop taxonomy
docker run --rm \
  -v taxonomy-data:/data:ro \
  -v "$(pwd)":/backup \
  alpine \
  tar czf /backup/taxonomy-backup.tar.gz -C /data .
docker compose -f docker-compose.prod.yml start taxonomy
```

A backup is useful only after a restore test. For an upgrade that changes the JGit Core schema, retain the backup until refs, commit traversal and pack BLOB checksums have been verified.

## 6. Health checks

The image exposes Spring Boot Actuator readiness internally. The production Compose service checks:

```yaml
healthcheck:
  test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://localhost:8080/actuator/health/readiness"]
  interval: 30s
  timeout: 10s
  retries: 5
  start_period: 90s
```

Useful endpoints include:

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health/readiness` | Container readiness |
| `GET /api/status/startup` | Catalogue initialization status |
| `GET /api/ai-status` | LLM provider status |
| `GET /api/embedding/status` | Embedding subsystem status |

## 7. Upgrading persistent installations

### Normal upgrade

1. Stop writers and take a restorable backup.
2. Pull the verified image or rebuild with BuildKit secrets.
3. Recreate the application container without deleting the data volume.
4. Verify readiness, repository refs/history and search functionality.

HSQLDB and PostgreSQL profiles run the released `jgit-storage-hibernate-core` Flyway stream before Hibernate starts. Fresh installations and already-managed schemas migrate automatically.

### Existing pre-library Taxonomy JGit schema

An installation created by the former copied backend does **not** migrate silently. Startup fails closed until an operator has:

1. stopped every writer;
2. taken a restorable backup;
3. recorded representative repository/ref data and ordered `git_packs.data` BLOB checksums;
4. reviewed the [JGit storage adoption runbook](JGIT_STORAGE_HIBERNATE.md#adopting-an-existing-taxonomy-database).

Enable adoption for exactly one startup:

```bash
TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION=true \
  docker compose -f docker-compose.prod.yml up -d
```

After successful startup, verify refs, commit traversal, reflogs and BLOB checksums, then set the variable back to `false`. Duplicate pack identities, partial schemas, oversized pack-extension values and other unsafe shapes are rejected before the adoption DDL runs.

`jgit-storage-hibernate-core` 0.1.9 publishes Flyway migrations for HSQLDB and PostgreSQL. SQL Server and Oracle remain on Taxonomy's Hibernate schema-management path until matching upstream migrations and real-database tests are released.

## 8. Building locally

The Dockerfile requires BuildKit secrets because the released storage library is currently hosted on GitHub Packages:

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=token-with-read-packages

docker build \
  --secret id=github_actor,env=GITHUB_ACTOR \
  --secret id=github_token,env=GITHUB_TOKEN \
  -t taxonomy-analyzer .

docker run --rm -p 8080:8080 taxonomy-analyzer
```

Do not pass the token with `--build-arg`, copy a Maven `settings.xml` containing credentials into the build context, or bake credentials into a custom image. The multi-stage build uses Maven with Eclipse Temurin 21 and a Temurin 21 JRE runtime image.
