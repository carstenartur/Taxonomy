# Taxonomy Architecture Analyzer — Deployment Guide

This guide covers deploying the Taxonomy Architecture Analyzer using Docker and Render.com.

> **Prerequisites:** Docker 20+ for containerised deployment. No external database or message broker is required — the application is fully self-contained.

---

## Table of Contents

1. [Docker Deployment](#1-docker-deployment)
2. [Render.com Deployment](#2-rendercom-deployment)
3. [Health Check](#3-health-check)
4. [Troubleshooting](#4-troubleshooting)
5. [Database Backends](#5-database-backends)
6. [HTTPS/TLS Termination](#6-httpstls-termination)
7. [VPS Deployment](#7--vps-deployment-docker--caddy)

> See also: [Configuration Reference](CONFIGURATION_REFERENCE.md) for all environment variables, [Architecture Description](ARCHITECTURE.md) for system design details.

---

## 1. Docker Deployment

### Building the Docker Image

The repository includes a multi-stage `Dockerfile`:

| Stage | Base Image | Purpose |
|---|---|---|
| **build** | `eclipse-temurin:17-jdk-alpine` | Compiles the application with Maven |
| **runtime** | `eclipse-temurin:17-jre-alpine` | Runs the application (minimal image) |

```bash
# Build the image
docker build -t taxonomy-analyzer .

# The image is approximately 200 MB (JRE + application JAR)
```

### Running with Docker

> ⚠️ **The commands below expose unencrypted HTTP on port 8080 — use only for local testing.**
> For any internet-facing deployment, use [`docker-compose.prod.yml`](../../docker-compose.prod.yml)
> which provides automatic HTTPS via Caddy (see [Section 7](#7--vps-deployment-docker--caddy)).

**Minimal (browser-only, no AI):**
```bash
docker run -p 8080:8080 taxonomy-analyzer
```

**With a cloud LLM provider (e.g. Gemini):**
```bash
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your-gemini-api-key \
  taxonomy-analyzer
```

**With local offline AI (no API key needed):**
```bash
docker run -p 8080:8080 \
  -e LLM_PROVIDER=LOCAL_ONNX \
  taxonomy-analyzer
```

**Full production configuration:**
```bash
docker run -d \
  --name taxonomy-analyzer \
  -p 8080:8080 \
  -e GEMINI_API_KEY=your-gemini-api-key \
  -e ADMIN_PASSWORD=your-admin-password \
  -e TAXONOMY_ADMIN_PASSWORD=strong-login-password \
  -e TAXONOMY_EMBEDDING_ENABLED=true \
  -e TAXONOMY_SWAGGER_PUBLIC=false \
  -e TAXONOMY_AUDIT_LOGGING=true \
  taxonomy-analyzer
```

### Required `-e` Flags

| Flag | Required? | Description |
|---|---|---|
| `-e GEMINI_API_KEY=...` | At least one LLM key or `LOCAL_ONNX` | Enables AI analysis |
| `-e ADMIN_PASSWORD=...` | Optional | Protects admin panels |
| `-e LLM_PROVIDER=...` | Optional | Forces a specific LLM provider |
| `-e TAXONOMY_EMBEDDING_ENABLED=false` | Optional | Disables semantic search |

### Volume Mounts

The application uses a file-based HSQLDB database and a filesystem Lucene index, both stored
under `/app/data`. Mount a persistent volume to retain data across container restarts:

```bash
docker run -p 8080:8080 \
  -v taxonomy-data:/app/data \
  taxonomy-analyzer
```

For the DJL embedding model cache (LOCAL_ONNX mode):

```bash
docker run -p 8080:8080 \
  -e LLM_PROVIDER=LOCAL_ONNX \
  -v taxonomy-data:/app/data \
  -v djl-cache:/root/.djl.ai \
  taxonomy-analyzer
```

This persists the downloaded model, database, and search index across container restarts.

### Health Check

The application responds to `GET /` with a 200 status when healthy:

```bash
docker run -d \
  --name taxonomy-analyzer \
  --health-cmd="wget -q -O /dev/null http://localhost:8080/ || exit 1" \
  --health-interval=30s \
  --health-timeout=10s \
  --health-retries=3 \
  -p 8080:8080 \
  taxonomy-analyzer
```

### Using the Published Image

The CI/CD pipeline publishes a Docker image to GitHub Container Registry:

```bash
docker pull ghcr.io/carstenartur/taxonomy:latest
docker run -p 8080:8080 -e GEMINI_API_KEY=your-key ghcr.io/carstenartur/taxonomy:latest
```

---

## 2. Render.com Deployment

### Understanding `render.yaml`

The repository includes a `render.yaml` Blueprint specification for the Render **Free Tier**:

```yaml
services:
  - type: web
    name: taxonomy-analyzer
    runtime: docker
    plan: free
    healthCheckPath: /api/status/startup
    envVars:
      - key: GEMINI_API_KEY
        sync: false        # set manually in the Render dashboard
      - key: TAXONOMY_EMBEDDING_ENABLED
        value: "false"        # DJL/ONNX model uses ~80–140 MB native memory; disable on the 512 MB free tier
      - key: TAXONOMY_INIT_ASYNC
        value: "true"         # load taxonomy after Tomcat opens its port so Render detects the port quickly
```

| Field | Description |
|---|---|
| `type: web` | Render Web Service with a public URL |
| `name` | Display name in the Render dashboard |
| `runtime: docker` | Tells Render to use the `Dockerfile` at the repo root |
| `plan: free` | Render Free plan; upgrade to `starter` or higher for more resources |
| `healthCheckPath: /api/status/startup` | Render probes `GET /api/status/startup` — always returns HTTP 200 even while the taxonomy is still loading |
| `TAXONOMY_EMBEDDING_ENABLED` | Set to `false` on the free tier to save ~80–140 MB of native memory (disables semantic KNN search) |
| `TAXONOMY_INIT_ASYNC` | Set to `true` so the taxonomy loads in a background thread after Tomcat opens its port (prevents Render port-scan timeout) |
| `envVars[].sync: false` | The variable must be entered manually as a secret in the dashboard |

> **Note:** Render Free Tier does **not** support persistent disks. The application uses an
> in-memory HSQLDB database (`mem:`) and Lucene heap index by default, which are the correct
> settings for the free tier. Taxonomy data is reloaded from the embedded Excel workbook on
> every deploy. To persist data across redeploys, upgrade to a paid Render plan and add a
> `disk:` section with `TAXONOMY_DATASOURCE_URL`, `TAXONOMY_DDL_AUTO=update`, and
> `TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem`.

### Setting Environment Variables in Render

1. Go to the [Render Dashboard](https://dashboard.render.com)
2. Select your **taxonomy-analyzer** service
3. Click **Environment** in the left sidebar
4. Click **Add Environment Variable**

**Required variables:**

| Key | Secret? | Value |
|---|---|---|
| `GEMINI_API_KEY` | ✅ Yes | Your Gemini API key |

**Optional variables:**

| Key | Secret? | Value |
|---|---|---|
| `ADMIN_PASSWORD` | ✅ Yes | Password for admin panels |
| `LLM_PROVIDER` | No | `GEMINI`, `OPENAI`, etc. (overrides auto-detection) |
| `OPENAI_API_KEY` | ✅ Yes | Alternative: OpenAI key instead of Gemini |
| `TAXONOMY_EMBEDDING_ENABLED` | No | `true` (default) or `false` — set to `false` in `render.yaml` for the free tier |
| `JAVA_OPTS` | No | Override JVM flags without rebuilding the image (e.g. `-XX:+UseSerialGC -Xss512k -XX:MaxRAMPercentage=50.0 -Xmx220m`) |

> **Tip:** Mark API keys and passwords as "Secret" in Render to prevent them from
> appearing in logs and the dashboard.

### Deploying from GitHub

1. Connect your GitHub repository in the Render dashboard
2. Render auto-detects the `render.yaml` and `Dockerfile`
3. Set your secret environment variables
4. Click **Manual Deploy** → **Deploy latest commit** (or push to trigger auto-deploy)

The deploy takes approximately 3–5 minutes (Maven build + Docker image).

---

## 3. Health Check

The application exposes a startup status endpoint at `GET /api/status/startup` which always
returns HTTP 200 with a JSON body indicating whether the taxonomy has been fully loaded.
Render uses this path for health monitoring.

Additional health indicators:

| Endpoint | What It Checks |
|---|---|
| `GET /api/status/startup` | Always HTTP 200; `{"initialized": true/false, "status": "pending/loading/ready/error"}` |
| `GET /` | Application is running and Thymeleaf renders (requires taxonomy loaded) |
| `GET /api/ai-status` | LLM provider availability |
| `GET /api/embedding/status` | Embedding model loaded and ready |
| `GET /api/taxonomy` | Taxonomy data loaded from Excel |

---

## 4. Troubleshooting

### Container fails to start

- Check memory: the JRE + Lucene index + ONNX Runtime need ~256 MB minimum; heap is capped at 50 % of container memory (hard cap `-Xmx220m`) to leave room for off-heap native memory. On Render's 512 MB free tier, semantic embedding is disabled via `TAXONOMY_EMBEDDING_ENABLED=false` to save ~80–140 MB of native memory.
- Check logs: `docker logs taxonomy-analyzer`

### AI analysis not working

- Verify the API key is set: `docker exec taxonomy-analyzer printenv | grep API_KEY`
- Check `GET /api/ai-status` for provider status

### Embedding model download fails

- The DJL model download requires internet access on first run
- For air-gapped deployments, see the [Configuration Reference](CONFIGURATION_REFERENCE.md)
  section on pre-downloading the embedding model

---

## 5. Database Backends

By default, the application uses an embedded HSQLDB database (in-memory, no setup required). For production deployments, switch to a persistent database:

| Database | Profile | Docker Compose File |
|---|---|---|
| PostgreSQL 14+ | `postgres` | `docker-compose-postgres.yml` |
| SQL Server 2019+ | `mssql` | `docker-compose-mssql.yml` |
| Oracle 19c+ / 23c | `oracle` | `docker-compose-oracle.yml` |

```bash
# Example: Run with PostgreSQL
docker compose -f docker-compose-postgres.yml up
```

To migrate from HSQLDB to a production database:

1. Set `SPRING_PROFILES_ACTIVE` to the target profile
2. Configure `TAXONOMY_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
3. Use `TAXONOMY_DDL_AUTO=create` on first run, then switch to `update`

The taxonomy data is always loaded from the bundled Excel workbook at startup, so no data migration is needed for the taxonomy itself. Architecture DSL data stored in the Git repository will need to be re-created.

See [Database Setup](DATABASE_SETUP.md) for detailed instructions for each database, including troubleshooting and integration tests.

---

## 6. HTTPS/TLS Termination

The application listens on **HTTP port 8080** by default. For any deployment beyond `localhost`, always terminate TLS in front of the application. The HSTS header (`Strict-Transport-Security`) is already sent by the application on every response.

### Option A — Reverse Proxy (Recommended)

A reverse proxy handles certificate management, TLS termination, and optionally rate limiting. The application continues to listen on plain HTTP behind the proxy.

#### nginx

```nginx
server {
    listen 443 ssl http2;
    server_name taxonomy.example.com;

    ssl_certificate     /etc/letsencrypt/live/taxonomy.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/taxonomy.example.com/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}

# Redirect HTTP → HTTPS
server {
    listen 80;
    server_name taxonomy.example.com;
    return 301 https://$host$request_uri;
}
```

#### Caddy (automatic Let's Encrypt)

```
taxonomy.example.com {
    reverse_proxy localhost:8080
}
```

Caddy provisions and renews TLS certificates automatically. No further configuration is needed.

#### Docker Compose (nginx + app)

```yaml
services:
  taxonomy:
    image: ghcr.io/carstenartur/taxonomy:latest
    environment:
      - GEMINI_API_KEY=${GEMINI_API_KEY}
      - TAXONOMY_ADMIN_PASSWORD=${TAXONOMY_ADMIN_PASSWORD}
    expose:
      - "8080"          # internal only — not published to host

  nginx:
    image: nginx:alpine
    ports:
      - "443:443"
      - "80:80"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
    depends_on:
      - taxonomy
```

In the `nginx.conf` above, replace `proxy_pass http://localhost:8080` with `proxy_pass http://taxonomy:8080` (the Docker service name).

### Option B — Spring Boot Native SSL

For single-container setups (no reverse proxy available), Spring Boot can terminate TLS directly using SSL bundles.

**Using environment variables:**

```bash
docker run -p 8443:8443 \
  -e SERVER_PORT=8443 \
  -e SERVER_SSL_BUNDLE=taxonomy-tls \
  -e SPRING_SSL_BUNDLE_PEM_TAXONOMY_TLS_KEYSTORE_CERTIFICATE=/certs/cert.pem \
  -e SPRING_SSL_BUNDLE_PEM_TAXONOMY_TLS_KEYSTORE_PRIVATE_KEY=/certs/key.pem \
  -v /etc/letsencrypt/live/taxonomy.example.com/fullchain.pem:/certs/cert.pem:ro \
  -v /etc/letsencrypt/live/taxonomy.example.com/privkey.pem:/certs/key.pem:ro \
  ghcr.io/carstenartur/taxonomy:latest
```

**Using `application-production.properties`** (see commented-out properties in that file):

```properties
server.port=8443
server.ssl.bundle=taxonomy-tls
spring.ssl.bundle.pem.taxonomy-tls.keystore.certificate=/certs/cert.pem
spring.ssl.bundle.pem.taxonomy-tls.keystore.private-key=/certs/key.pem
```

> **Note:** When using native SSL, update the Docker health check to use HTTPS:
> `--health-cmd="wget --no-check-certificate -q -O /dev/null https://localhost:8443/ || exit 1"`

### Render.com

Render.com provides automatic HTTPS for all web services — no additional configuration is needed. The TLS certificate is provisioned and renewed automatically by the platform.

### Cloud Load Balancers (AWS ALB, GCP, Azure)

Cloud load balancers terminate TLS before the traffic reaches the container. Keep the application on port 8080 (HTTP) and configure the load balancer's target group to forward to the container port.

---

## 7. VPS Deployment (Docker + Caddy)

This section provides a complete, copy-paste deployment for a Cloud server (or any Linux VPS with Docker). Caddy handles automatic HTTPS — no manual certificate management required.

### Prerequisites

| Requirement | Notes |
|---|---|
| **Linux VPS** | (2 vCPU, 4 GB RAM) or similar |
| **Docker + Docker Compose** | `apt install docker.io docker-compose-plugin` on Ubuntu/Debian |
| **Domain name** | A/AAAA DNS record pointing to your server's IP |
| **Ports 80 + 443 open** | Required for Let's Encrypt certificate validation and HTTPS |

> **Ports:** Caddy listens on **443** (HTTPS) and **80** (HTTP → HTTPS redirect + ACME challenge). The application's port 8080 is **never exposed** to the internet — it is only reachable inside the Docker network.

### Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy

# 2. Create the environment file
cp .env.example .env
nano .env                      # set your domain, admin password, and optionally an API key

# 3. Start everything (builds the app image, starts Caddy + app)
docker compose -f docker-compose.prod.yml up -d --build

# 4. Check logs
docker compose -f docker-compose.prod.yml logs -f
```

Open `https://your-domain.example.com` — Caddy provisions a Let's Encrypt TLS certificate automatically on first request. Log in with `admin` and the password you set in `.env`.

### What Gets Deployed

```
┌─── Internet ───────────────────────────────────────────────┐
│                                                            │
│  :443 (HTTPS) ──► Caddy ──► taxonomy:8080 (HTTP, internal) │
│  :80  (redirect)                                           │
│                                                            │
│  Port 8080 is NOT published to the host.                   │
└────────────────────────────────────────────────────────────┘
```

### Files

| File | Purpose |
|---|---|
| [`docker-compose.prod.yml`](../../docker-compose.prod.yml) | Production Compose file: Caddy + taxonomy app |
| [`Caddyfile`](../../Caddyfile) | Caddy reverse proxy configuration |
| [`.env.example`](../../.env.example) | Template for environment variables |

### Firewall (Cloud Firewall)

In the Cloud Console, create a firewall with these inbound rules:

| Port | Protocol | Source | Purpose |
|---|---|---|---|
| **22** | TCP | Your IP or `0.0.0.0/0` | SSH |
| **80** | TCP | `0.0.0.0/0` | ACME challenge + HTTP→HTTPS redirect |
| **443** | TCP | `0.0.0.0/0` | HTTPS |

**Do not open port 8080.** It must remain internal.

### Persistent Data

```bash
docker compose -f docker-compose.prod.yml down     # stop (data preserved)
docker compose -f docker-compose.prod.yml up -d     # restart (data intact)
```

The Compose file defines named volumes (`taxonomy-data`, `caddy-data`, `caddy-config`) that persist across container restarts and image rebuilds. To reset all data, run `docker compose -f docker-compose.prod.yml down -v`.

### Updating

```bash
cd Taxonomy
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

### Using a Pre-built Image

To skip the local build and use the published image from GitHub Container Registry:

```bash
# In docker-compose.prod.yml, replace:
#   build: .
# with:
#   image: ghcr.io/carstenartur/taxonomy:latest
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```
