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
docker run --rm -p 8080:8080 \
  -e TAXONOMY_ADMIN_PASSWORD='choose-a-unique-local-password' \
  ghcr.io/carstenartur/taxonomy:latest
```

Open <http://localhost:8080> and sign in as `admin` with the password supplied above. There is no reusable development password. When the variable is intentionally omitted outside production, the generated credential remains inside the container in the owner-only temporary file whose path is logged; supplying the value explicitly is simpler for an ephemeral container.

### Bundled ONNX variant

The optional `-onnx` variant includes the pinned, quantized
`sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` bundle under
`/opt/taxonomy/models/multilingual-minilm` (about **130 MB extra, uncompressed**).
It runs CPU embeddings, semantic search and semantic scoring without an API key
or runtime model download. It does not generate explanations or provide a
generative Copilot. Standard tags and their defaults remain unchanged.

The corresponding tags are `latest-onnx`, `main-onnx`, `vX.Y.Z-onnx` and
`sha-<commit>-onnx` in the same GHCR repository. Publication follows successful
verification on `main` or a release tag; a feature-branch build does not make
these tags available. Pin the verified ONNX image digest in production.

For local evaluation, generate a unique administrator password (or set your own
unique value first) and keep it in the current shell for login:

```bash
export TAXONOMY_ADMIN_PASSWORD="${TAXONOMY_ADMIN_PASSWORD:-$(openssl rand -hex 24)}"
docker run --rm --name taxonomy-onnx --memory=2g \
  -p 127.0.0.1:8080:8080 \
  -e TAXONOMY_ADMIN_PASSWORD \
  ghcr.io/carstenartur/taxonomy:latest-onnx
```

Open <http://localhost:8080> as `admin` with that password. To build this variant
from the checked-out source instead:

```bash
docker build --target onnx -t taxonomy:local-onnx .
# Use taxonomy:local-onnx as the image in the docker run command above.
```

The image sets `LLM_PROVIDER=LOCAL_ONNX`, enables embeddings with profile
`MULTILINGUAL_MINILM_L12`, points the model directory at the bundled files and
sets `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false`. Native CPU execution uses
`RUST_FLAVOR=cpu`; the writable runtime cache is `/tmp/djl-cache`
(`DJL_CACHE_DIR`). Keep `/tmp` writable, including when using a read-only root
filesystem; no model-directory write access or model volume is needed.

The JVM defaults for this variant are `-XX:+UseSerialGC -Xss512k -Xmx768m
-XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp`. The **2 GiB container limit**
is a starting point based on a historical whole-application observation of about
1,504 MiB, not a guarantee for every workload. Native inference, Lucene and other
process memory are outside the Java heap; measure the actual deployment.

Bundling removes the download step, but model loading and catalogue/semantic-index
initialization still run at startup. Application readiness at
`/actuator/health/readiness` and catalogue progress at `/api/status/startup` do
not prove semantic readiness. Check `/api/embedding/status` for
`semanticReady: true` before relying on semantic results; `/api/ai-status` alone
does not establish that capability. Use the deployment's normal authentication
for application status endpoints.

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
  -e TAXONOMY_ADMIN_PASSWORD="$TAXONOMY_ADMIN_PASSWORD" \
  -e TAXONOMY_DATASOURCE_URL='jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached;hsqldb.write_delay_millis=0;shutdown=true' \
  -e TAXONOMY_DDL_AUTO=update \
  -v taxonomy-data:/app/data \
  ghcr.io/carstenartur/taxonomy:latest
```

Set `TAXONOMY_ADMIN_PASSWORD` to a unique secret of at least 16 characters in
the host environment before this command. The production guard rejects a
missing value or a literal `replace-with-...` placeholder. This direct
`docker run -p 8080:8080` example serves plain HTTP and is suitable only behind
a separately configured TLS reverse proxy.

Port 8080 is plain HTTP. Use a TLS-terminating reverse proxy for any non-local deployment.

## 3. Recommended Docker Compose setup

The repository includes [`docker-compose.prod.yml`](../../docker-compose.prod.yml), which runs Taxonomy behind Caddy with automatic HTTPS.

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy
cp .env.example .env
# Edit .env: domain, administrator password and AI settings.
docker compose -f docker-compose.prod.yml up -d --build
```

The local source build resolves `jgit-storage-hibernate-core` anonymously from the public immutable Maven repository. No GitHub account, token or Maven `settings.xml` entry is required.

The stack contains:

| Service | Purpose |
|---|---|
| `taxonomy` | Application on internal port 8080, built from the checked-out source |
| `caddy` | HTTPS reverse proxy on ports 80 and 443 |

### Compose and Helm with the bundled model

For a source-built production stack, configure `.env` as above, then apply the
[ONNX Compose overlay](../../docker-compose.onnx.yml):

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.onnx.yml up -d --build
```

The overlay selects build target `onnx`, enables the bundled model even if
`.env` or the base configuration disables embeddings or leaves the provider/model
path empty, sets the JVM/cache options and a 2 GiB starting memory limit, and
uses the image's `curl` for application readiness. Caddy and persistent data
configuration continue to come from the production stack.

For Kubernetes, apply [values-onnx.yaml](../../deploy/helm/taxonomy/values-onnx.yaml)
after your ordinary deployment values. The existing database/admin Secret and
other chart prerequisites still apply; see the [chart guide](../../deploy/helm/taxonomy/README.md).
Use the verified digest **of the ONNX variant**, supplied in `ONNX_IMAGE_DIGEST`
with its `sha256:` prefix:

```bash
helm upgrade --install taxonomy deploy/helm/taxonomy \
  -f your-deployment-values.yaml \
  -f deploy/helm/taxonomy/values-onnx.yaml \
  --set-string image.digest="$ONNX_IMAGE_DIGEST"
```

The overlay overrides the base chart's disabled/empty model configuration and
`JAVA_OPTS`, sets `image.variant=onnx` and uses the chart's writable `/tmp` mount
for native libraries and cache. It preserves the read-only root filesystem.
For a verified tag instead of a digest, set `image.tag=vX.Y.Z-onnx` or
`image.tag=sha-<commit>-onnx`; configure only one of tag and digest. Packaged
release charts derive `v<appVersion>-onnx` when both are empty; source snapshot
charts require an explicit tag or digest. A standard-image digest does not
contain the bundled model even when `image.variant=onnx` is set.

### Use the published image instead

A production deployment can avoid compiling source by replacing the `build` block with an immutable published image reference:

```yaml
services:
  taxonomy:
    image: ghcr.io/carstenartur/taxonomy@sha256:<verified-digest>
```

Remove the complete `build:` block when using an image.

### Database-specific Compose files

| File | Database / purpose |
|---|---|
| `docker-compose-postgres.yml` | PostgreSQL 16 |
| `docker-compose-mssql.yml` | Microsoft SQL Server 2022 |
| `docker-compose-oracle.yml` | Oracle Database 23 |
| `docker-compose-keycloak.yml` | PostgreSQL plus Keycloak/OIDC |
| `docker-compose.integration-test.yml` | Two Taxonomy instances plus Gitea |

All source-building Compose files resolve the released storage library anonymously and require no build credentials.

## 4. Environment variables

Runtime variables:

| Variable | Default | Description |
|---|---|---|
| `TAXONOMY_ADMIN_PASSWORD` | empty outside production; required in production | Initial local administrator password. An empty non-production value creates an owner-only one-time credential file and logs only its path. |
| `LLM_PROVIDER` | auto-detected | `GEMINI`, `OPENAI`, `DEEPSEEK`, `QWEN`, `LLAMA`, `MISTRAL`, `CUSTOM_OPENAI` or `LOCAL_ONNX` |
| `GEMINI_API_KEY`, `OPENAI_API_KEY`, … | empty | Provider credentials |
| `TAXONOMY_EMBEDDING_ENABLED` | `false` | Enable semantic/KNN search |
| `TAXONOMY_EMBEDDING_MODEL_PROFILE` | `MULTILINGUAL_MINILM_L12` | Multilingual model contract; `BGE_SMALL_EN` selects the legacy English model |
| `TAXONOMY_EMBEDDING_MODEL_DIR` | empty | Mounted, pre-provisioned model directory matching the profile |
| `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD` | `false` | Explicitly permit runtime model download when embeddings are enabled |
| `TAXONOMY_DATASOURCE_URL` | in-memory HSQLDB | JDBC URL; set a file URL for persistent HSQLDB |
| `TAXONOMY_DDL_AUTO` | `create` | HSQLDB default; persistent HSQLDB uses `update`. PostgreSQL uses managed migrations and `validate` |
| `TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION` | `false` | One-time opt-in for verified pre-library JGit storage adoption |
| `JAVA_OPTS` | Dockerfile defaults | JVM heap, GC and stack settings |

Provision mounted bundles using the [pinned model provisioning guide](../testing/multilingual-model-provisioning.md). `LOCAL_ONNX` alone does not enable embeddings; switching model profiles requires rebuilding the semantic index and discarding cached vectors.

No build-only credentials are required for the checked-in source build.

See [Configuration Reference](CONFIGURATION_REFERENCE.md) for the full application property set and [Hibernate-backed JGit storage](JGIT_STORAGE_HIBERNATE.md) for the storage contract.

## 5. Persistence and backup

The production Compose stack stores mutable state below `/app/data`:

| Path | Contents |
|---|---|
| `/app/data/taxonomydb*` | File-backed HSQLDB catalogue, users and Git pack/ref/reflog data |
| `/app/data/lucene-index` | Hibernate Search/Lucene indexes |

Named volumes configured by `docker-compose.prod.yml` survive container recreation.
Stop every writer to the HSQLDB files, including any separately started
Taxonomy container. The following commands stop the supplied Compose service
and resolve its real mounted volume before taking a filesystem-level backup:

```bash
# Run from the Compose project directory. Resolve the actual project-prefixed
# volume from the running service; a bare "taxonomy-data" names another volume.
app_container=$(docker compose -f docker-compose.prod.yml ps -q taxonomy)
test -n "$app_container" || { echo 'Taxonomy container not found' >&2; exit 1; }
data_volume=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/app/data"}}{{.Name}}{{end}}{{end}}' "$app_container")
test -n "$data_volume" || { echo 'Taxonomy data volume not found' >&2; exit 1; }
docker compose -f docker-compose.prod.yml stop taxonomy || exit 1
docker run --rm \
  --mount "type=volume,src=$data_volume,dst=/data,readonly" \
  -v "$(pwd)":/backup \
  alpine \
  tar czf /backup/taxonomy-backup.tar.gz -C /data . || {
    docker compose -f docker-compose.prod.yml start taxonomy
    exit 1
  }
docker compose -f docker-compose.prod.yml start taxonomy
```

If archiving fails, start the stopped service before troubleshooting. To test a
restore, extract the archive into a **new empty volume** on a separate host:

```bash
if docker volume inspect taxonomy-restore-data >/dev/null 2>&1; then
  echo 'Choose a new, empty restore volume name' >&2; exit 1
fi
docker volume create taxonomy-restore-data
docker run --rm \
  --mount type=volume,src=taxonomy-restore-data,dst=/data \
  -v "$(pwd)":/backup:ro \
  alpine tar xzf /backup/taxonomy-backup.tar.gz -C /data
```

On that separate host, create `restore-volume.override.yml` beside the Compose
file to attach the restored volume instead of creating a new project volume:

```yaml
volumes:
  taxonomy-data:
    external: true
    name: taxonomy-restore-data
```

Run `docker compose -f docker-compose.prod.yml -f restore-volume.override.yml
-p taxonomy-restore up -d` with independent domain and secrets so the test
instance cannot write to the source deployment. Verify users, workspace state, search and Git
history before accepting the backup. For an upgrade that changes the JGit Core
schema, retain the backup until refs, commit traversal and pack BLOB checksums
have been verified. Never restore over a live database or mix restored database
files with a stale index snapshot.

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
