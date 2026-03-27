# Taxonomy Architecture Analyzer — Container Image

The official container image is published to **GitHub Container Registry (GHCR)** by the
CI/CD pipeline on every push to the default branch.

```
ghcr.io/carstenartur/taxonomy
```

> **GHCR is the only official registry.** Do not use images from other registries unless
> you built and pushed them yourself.

---

## Table of Contents

1. [Image Tags](#1-image-tags)
2. [Quick Start (Single Container)](#2-quick-start-single-container)
3. [Recommended Setup (Docker Compose)](#3-recommended-setup-docker-compose)
4. [Environment Variables](#4-environment-variables)
5. [Persistence & Volumes](#5-persistence--volumes)
6. [Health Checks](#6-health-checks)
7. [Upgrading](#7-upgrading)
8. [Building Locally](#8-building-locally)

> See also: [Configuration Reference](CONFIGURATION_REFERENCE.md) for all environment
> variables, [Deployment Guide](DEPLOYMENT_GUIDE.md) for Render.com and VPS instructions.

---

## 1. Image Tags

The CI/CD pipeline (`ci-cd.yml`) publishes the following tags:

| Tag | Example | When Updated | Use Case |
|---|---|---|---|
| `latest` | `ghcr.io/carstenartur/taxonomy:latest` | Every push to `main` | Quick start, local testing |
| `main` | `ghcr.io/carstenartur/taxonomy:main` | Every push to `main` | Identical to `latest` (branch-name tag) |
| `sha-<hash>` | `ghcr.io/carstenartur/taxonomy:sha-abc1234` | Every push to any branch | Reproducible deployments — pin to a specific commit |

**Choosing a tag:**

- Use **`latest`** for local experimentation or CI pipelines that always want the newest build.
- Use **`sha-<hash>`** in production to pin a known-good version and upgrade explicitly.
- Feature branches also produce `sha-` tags, but no `latest` tag.

---

## 2. Quick Start (Single Container)

Pull and run in one command — no clone, no build:

```bash
docker pull ghcr.io/carstenartur/taxonomy:latest

docker run -p 8080:8080 ghcr.io/carstenartur/taxonomy:latest
```

Open <http://localhost:8080> and log in with `admin` / `admin`.

> ⚠️ **Local testing only.** The command above exposes unencrypted HTTP on port 8080.
> Never expose this port to the internet. For any non-local deployment, use the
> [Docker Compose setup](#3-recommended-setup-docker-compose) below.

### Common Variants

**With Google Gemini (cloud AI):**
```bash
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your-gemini-api-key \
  ghcr.io/carstenartur/taxonomy:latest
```

**Fully offline (local ONNX model, no API key):**
```bash
docker run -p 8080:8080 \
  -e LLM_PROVIDER=LOCAL_ONNX \
  ghcr.io/carstenartur/taxonomy:latest
```

**With persistent data:**
```bash
docker run -p 8080:8080 \
  -v taxonomy-data:/app/data \
  ghcr.io/carstenartur/taxonomy:latest
```

---

## 3. Recommended Setup (Docker Compose)

For any deployment beyond `localhost`, use **Docker Compose** with a reverse proxy.
The repository includes [`docker-compose.prod.yml`](../../docker-compose.prod.yml)
with [Caddy](https://caddyserver.com) for automatic HTTPS:

```bash
# 1. Clone and configure
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy
cp .env.example .env          # edit with your domain and API key

# 2. Start
docker compose -f docker-compose.prod.yml up -d
```

This starts two services:

| Service | Image | Purpose |
|---|---|---|
| `taxonomy` | Built from the local `Dockerfile` (or `ghcr.io/carstenartur/taxonomy:latest`) | Application on port 8080 (internal only) |
| `caddy` | `caddy:2-alpine` | Reverse proxy with automatic Let's Encrypt HTTPS on port 443 |

### Using the Published Image Instead of Building

Edit `docker-compose.prod.yml` and replace the `build: .` line with the GHCR image:

```yaml
services:
  taxonomy:
    image: ghcr.io/carstenartur/taxonomy:latest   # ← use published image
    # build: .                                     # ← remove or comment out
```

This skips the local Maven build and pulls the pre-built image directly.

### Database-Specific Compose Files

Additional Compose files are available for external databases:

| File | Database |
|---|---|
| `docker-compose-postgres.yml` | PostgreSQL 16 |
| `docker-compose-mssql.yml` | Microsoft SQL Server 2022 |
| `docker-compose-oracle.yml` | Oracle Database 23 |
| `docker-compose-keycloak.yml` | PostgreSQL + Keycloak (SSO) |

See [Database Setup](DATABASE_SETUP.md) for details.

---

## 4. Environment Variables

Key environment variables for container deployments. See the
[Configuration Reference](CONFIGURATION_REFERENCE.md) for the complete list.

| Variable | Required? | Default | Description |
|---|---|---|---|
| `GEMINI_API_KEY` | At least one LLM key or `LOCAL_ONNX` | *(empty)* | Google Gemini API key for AI analysis |
| `LLM_PROVIDER` | No | *(auto-detect)* | Force a provider: `GEMINI`, `OPENAI`, `DEEPSEEK`, `QWEN`, `LLAMA`, `MISTRAL`, `LOCAL_ONNX` |
| `TAXONOMY_ADMIN_PASSWORD` | Recommended | `admin` | Login password for the admin user |
| `TAXONOMY_EMBEDDING_ENABLED` | No | `true` | Set to `false` to disable semantic/KNN search (saves ~80–140 MB) |
| `TAXONOMY_THYMELEAF_CACHE` | No | `true` | Template cache — keep `true` in production |
| `TAXONOMY_SWAGGER_PUBLIC` | No | `false` | Set to `true` to expose Swagger UI without authentication |
| `TAXONOMY_AUDIT_LOGGING` | No | `false` | Enable detailed audit logging |
| `JAVA_OPTS` | No | *(see Dockerfile)* | Override JVM flags (heap, GC, stack size) |

**Example:**
```bash
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your-key \
  -e TAXONOMY_ADMIN_PASSWORD=strong-password \
  -e TAXONOMY_EMBEDDING_ENABLED=true \
  -v taxonomy-data:/app/data \
  ghcr.io/carstenartur/taxonomy:latest
```

---

## 5. Persistence & Volumes

The application stores all mutable data under `/app/data` inside the container:

| Path | Contents |
|---|---|
| `/app/data` | HSQLDB database files, Lucene search index, JGit repository |

Mount a **named volume** to persist data across container restarts and upgrades:

```bash
-v taxonomy-data:/app/data
```

In `docker-compose.prod.yml` this volume is already configured:

```yaml
volumes:
  - taxonomy-data:/app/data   # persist database + Lucene index
```

### Embedding Model Cache (LOCAL_ONNX)

When using `LLM_PROVIDER=LOCAL_ONNX`, the DJL library downloads the embedding model
(~33 MB) on first startup. To cache it across restarts:

```bash
-v djl-cache:/root/.djl.ai
```

### Backup

To back up application data, stop the container and copy the volume:

```bash
docker run --rm -v taxonomy-data:/data -v $(pwd):/backup alpine \
  tar czf /backup/taxonomy-backup.tar.gz -C /data .
```

---

## 6. Health Checks

| Endpoint | Description |
|---|---|
| `GET /api/status/startup` | Always HTTP 200; body: `{"initialized": true/false, "status": "..."}` |
| `GET /` | HTTP 200 when the application is fully running |
| `GET /api/ai-status` | LLM provider availability |
| `GET /api/embedding/status` | Embedding model status |

The `docker-compose.prod.yml` already includes a health check:

```yaml
healthcheck:
  test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://localhost:8080/api/status/startup"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 60s
```

---

## 7. Upgrading

### Using `latest` Tag

```bash
docker pull ghcr.io/carstenartur/taxonomy:latest
docker stop taxonomy-analyzer
docker rm taxonomy-analyzer
docker run -d --name taxonomy-analyzer \
  -p 8080:8080 \
  -v taxonomy-data:/app/data \
  ghcr.io/carstenartur/taxonomy:latest
```

### Using Docker Compose

```bash
cd Taxonomy
docker compose -f docker-compose.prod.yml pull    # pull latest images
docker compose -f docker-compose.prod.yml up -d    # recreate with new image
```

### Pinned Versions

To upgrade to a specific commit:

```bash
docker pull ghcr.io/carstenartur/taxonomy:sha-abc1234
# Update your docker-compose.prod.yml image tag, then:
docker compose -f docker-compose.prod.yml up -d
```

> **Data compatibility:** The embedded HSQLDB database is automatically migrated on
> startup. No manual migration steps are required between versions.

---

## 8. Building Locally

If you prefer to build from source instead of pulling from GHCR:

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy
docker build -t taxonomy-analyzer .
docker run -p 8080:8080 taxonomy-analyzer
```

The multi-stage `Dockerfile` uses `eclipse-temurin:17-jdk` for the build stage and
`eclipse-temurin:17-jre` for the runtime stage. The final image is approximately 200 MB.
