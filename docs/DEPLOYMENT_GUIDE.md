# NATO Taxonomy Analyser — Deployment Guide

This guide covers deploying the NATO NC3T Taxonomy Browser using Docker and Render.com.

> **Prerequisites:** Docker 20+ for containerised deployment. No external database or message broker is required — the application is fully self-contained.

---

## Table of Contents

1. [Docker Deployment](#1-docker-deployment)
2. [Render.com Deployment](#2-rendercom-deployment)
3. [Health Check](#3-health-check)
4. [Troubleshooting](#4-troubleshooting)

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
docker build -t nato-taxonomy .

# The image is approximately 200 MB (JRE + application JAR)
```

### Running with Docker

**Minimal (browser-only, no AI):**
```bash
docker run -p 8080:8080 nato-taxonomy
```

**With a cloud LLM provider (e.g. Gemini):**
```bash
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your-gemini-api-key \
  nato-taxonomy
```

**With local offline AI (no API key needed):**
```bash
docker run -p 8080:8080 \
  -e LLM_PROVIDER=LOCAL_ONNX \
  nato-taxonomy
```

**Full production configuration:**
```bash
docker run -d \
  --name nato-taxonomy \
  -p 8080:8080 \
  -e GEMINI_API_KEY=your-gemini-api-key \
  -e ADMIN_PASSWORD=your-admin-password \
  -e TAXONOMY_EMBEDDING_ENABLED=true \
  nato-taxonomy
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
  nato-taxonomy
```

For the DJL embedding model cache (LOCAL_ONNX mode):

```bash
docker run -p 8080:8080 \
  -e LLM_PROVIDER=LOCAL_ONNX \
  -v taxonomy-data:/app/data \
  -v djl-cache:/root/.djl.ai \
  nato-taxonomy
```

This persists the downloaded model, database, and search index across container restarts.

### Health Check

The application responds to `GET /` with a 200 status when healthy:

```bash
docker run -d \
  --name nato-taxonomy \
  --health-cmd="wget -q -O /dev/null http://localhost:8080/ || exit 1" \
  --health-interval=30s \
  --health-timeout=10s \
  --health-retries=3 \
  -p 8080:8080 \
  nato-taxonomy
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

The repository includes a `render.yaml` Blueprint specification:

```yaml
services:
  - type: web            # Web service (publicly accessible)
    name: nato-taxonomy  # Service name in the Render dashboard
    runtime: docker      # Uses the Dockerfile in the repository root
    plan: free           # Free tier (512 MB RAM, shared CPU)
    healthCheckPath: /   # Render pings GET / to check health
    disk:
      name: taxonomy-data  # Persistent disk for database and Lucene index
      mountPath: /app/data
      sizeGB: 1
    envVars:
      - key: GEMINI_API_KEY
        sync: false      # false = must be set manually in the dashboard
      - key: TAXONOMY_DATASOURCE_URL
        value: "jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached"
      - key: TAXONOMY_DDL_AUTO
        value: update
      - key: TAXONOMY_SEARCH_DIRECTORY_TYPE
        value: local-filesystem
```

| Field | Description |
|---|---|
| `type: web` | Render Web Service with a public URL |
| `name` | Display name in the Render dashboard |
| `runtime: docker` | Tells Render to use the `Dockerfile` at the repo root |
| `plan: free` | Render Free plan; upgrade to `starter` or higher for more resources |
| `healthCheckPath: /` | Render probes `GET /` — the app returns the main page (HTTP 200) |
| `disk` | Persistent disk so the HSQLDB files and Lucene index survive redeploys |
| `TAXONOMY_DATASOURCE_URL` | Switches HSQLDB from the in-memory default to a disk-backed file database |
| `TAXONOMY_DDL_AUTO` | `update` preserves data across restarts (vs. `create` which rebuilds the schema) |
| `TAXONOMY_SEARCH_DIRECTORY_TYPE` | Switches Lucene from the in-memory heap default to a disk-backed filesystem index |
| `envVars[].sync: false` | The variable must be entered manually as a secret in the dashboard |

### Setting Environment Variables in Render

1. Go to the [Render Dashboard](https://dashboard.render.com)
2. Select your **nato-taxonomy** service
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
| `TAXONOMY_EMBEDDING_ENABLED` | No | `true` (default) or `false` |

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

The application exposes a health check at `GET /` which returns the main HTML page with
HTTP 200 status. Both Docker and Render use this path for health monitoring.

Additional health indicators:

| Endpoint | What It Checks |
|---|---|
| `GET /` | Application is running and Thymeleaf renders |
| `GET /api/ai-status` | LLM provider availability |
| `GET /api/embedding/status` | Embedding model loaded and ready |
| `GET /api/taxonomy` | Taxonomy data loaded from Excel |

---

## 4. Troubleshooting

### Container fails to start

- Check memory: the JRE + Lucene index + ONNX Runtime need ~256 MB minimum; heap is capped at 65 % of container memory to leave room for off-heap native memory
- Check logs: `docker logs nato-taxonomy`

### AI analysis not working

- Verify the API key is set: `docker exec nato-taxonomy printenv | grep API_KEY`
- Check `GET /api/ai-status` for provider status

### Embedding model download fails

- The DJL model download requires internet access on first run
- For air-gapped deployments, see the [Configuration Reference](CONFIGURATION_REFERENCE.md)
  section on pre-downloading the embedding model
