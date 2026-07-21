# Taxonomy Architecture Analyzer — Deployment Guide

This guide describes supported deployment modes and their persistence, security and network characteristics. Commands labelled **local only** must not be used as internet-facing production deployments.

## Deployment modes

| Mode | Intended use | Persistence | Recommended? |
|---|---|---|---|
| Local Maven / `docker run -p 8080` | Development and evaluation | In-memory by default | Local only |
| `docker-compose.prod.yml` | Small controlled production installation | File-backed HSQLDB and filesystem Lucene under `/app/data` | Supported baseline |
| Production profile + PostgreSQL/SQL Server/Oracle | Multi-user or business-critical production | External database plus persistent Lucene storage | Recommended |
| Render Free | Public demonstration | Ephemeral; state resets across deploys | Demo only |

## 1. Local development and evaluation

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy
mvn install -DskipTests
mvn -pl taxonomy-app spring-boot:run
```

Or run the published image:

```bash
docker run --rm -p 8080:8080 ghcr.io/carstenartur/taxonomy:latest
```

Open `http://localhost:8080`. This exposes unencrypted HTTP and uses in-memory persistence unless additional variables are supplied. Never expose this command directly to the internet.

## 2. Production baseline: Docker Compose with Caddy

The repository provides `docker-compose.prod.yml` with:

- Caddy TLS termination on ports 80/443;
- application port 8080 exposed only on the Docker network;
- a required non-default administrator password;
- file-backed HSQLDB;
- filesystem-backed Lucene index;
- persistent application and DJL model-cache volumes;
- Swagger restricted;
- audit logging and first-login password-change policy enabled;
- runtime embedding-model download disabled unless explicitly enabled.

### Configure

```bash
cp .env.example .env
```

Set at least:

```dotenv
DOMAIN=taxonomy.example.com
TAXONOMY_ADMIN_PASSWORD=<long-random-password>
```

Choose a provider. Example cloud provider:

```dotenv
LLM_PROVIDER=GEMINI
GEMINI_API_KEY=<secret>
```

Local execution:

```dotenv
LLM_PROVIDER=LOCAL_ONNX
TAXONOMY_EMBEDDING_ENABLED=true
TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
TAXONOMY_EMBEDDING_MODEL_DIR=/models/bge-small-en-v1.5
```

For controlled or network-isolated environments, mount the model directory read-only and verify its revision and checksum.

### Start

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

### Verify persistence

```bash
# 1. Create a user, workspace and architecture change through the application.
# 2. Recreate only the application container.
docker compose -f docker-compose.prod.yml up -d --force-recreate taxonomy
# 3. Log in and verify the user, workspace, Git history and change still exist.
```

The supplied embedded baseline uses:

```text
jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached;shutdown=true
TAXONOMY_DDL_AUTO=update
TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem
TAXONOMY_SEARCH_DIRECTORY_ROOT=/app/data/lucene-index
```

The `taxonomy-data` volume therefore contains persistent database and search-index files. Back it up together with deployment secrets and configuration.

## 3. Recommended production: external database

For multi-user or business-critical use, activate the hardened production profile plus a database profile:

```text
SPRING_PROFILES_ACTIVE=production,postgres
```

Equivalent profiles exist for `mssql` and `oracle`.

Example environment:

```dotenv
SPRING_PROFILES_ACTIVE=production,postgres
TAXONOMY_DATASOURCE_URL=jdbc:postgresql://db.example.internal:5432/taxonomy
SPRING_DATASOURCE_USERNAME=taxonomy
SPRING_DATASOURCE_PASSWORD=<secret>
TAXONOMY_ADMIN_PASSWORD=<long-random-password>
TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem
TAXONOMY_SEARCH_DIRECTORY_ROOT=/app/data/lucene-index
```

See [Database Setup](DATABASE_SETUP.md) for driver and integration-test details.

### Schema management

The current production profile uses `ddl-auto=update` for compatibility. Before introducing incompatible schema changes or operating a regulated long-lived installation, add Flyway or Liquibase and move production to `ddl-auto=validate`.

## 4. Authentication and authorization

Form-login mode creates an initial `admin` account only when none exists. The password comes from `TAXONOMY_ADMIN_PASSWORD`.

Production requirements:

- never use `admin` as the password;
- enable `TAXONOMY_REQUIRE_PASSWORD_CHANGE=true`;
- keep login rate limiting and audit logging enabled;
- assign USER, ARCHITECT and ADMIN according to least privilege;
- use Keycloak/OIDC for centralized enterprise identity where available.

Administrative diagnostics, prompts, logs and preferences are authorized by `ROLE_ADMIN`. There is no separate shared admin-panel password.

Browser sessions retain CSRF protection for state-changing API calls. Explicit Basic/Bearer API clients are treated as stateless.

## 5. HTTPS and reverse proxy

`docker-compose.prod.yml` uses the included Caddyfile. Caddy obtains and renews certificates automatically when `DOMAIN` resolves to the host.

The application must not be published directly on port 8080. When using another proxy, forward at least:

- `Host`
- `X-Forwarded-Proto`
- `X-Forwarded-For`
- `X-Forwarded-Host`

Apply HSTS only on HTTPS deployments. Add an organization-approved Content Security Policy after all browser dependencies are served from approved origins.

## 6. Health, readiness and monitoring

Use:

| Endpoint | Purpose | Exposure |
|---|---|---|
| `/actuator/health/liveness` | process liveness | platform/internal |
| `/actuator/health/readiness` | traffic readiness | platform/internal |
| `/actuator/prometheus` | metrics | monitoring network only |
| `/api/status/startup` | detailed UI startup state | authenticated application use |

The production Compose health check uses `/actuator/health/readiness`.

Monitor:

- readiness and initialization failures;
- JVM heap and native memory;
- authentication failures and lockouts;
- LLM error/rate-limit rates;
- database/storage capacity;
- failed Git/workspace operations;
- model-download attempts in controlled environments.

## 7. Backup and recovery

At minimum back up:

- the production database or `/app/data/taxonomydb*` for embedded HSQLDB;
- JGit data stored through the configured database backend;
- uploaded source/provenance content where applicable;
- external Git remotes if used;
- configuration and secret references;
- optionally the Lucene index, although it can be rebuilt from authoritative data.

A backup is not accepted until it has been restored on a separate instance and the following have been verified:

- login and roles;
- workspace list;
- DSL history and branches;
- accepted/rejected hypotheses and relations;
- search availability;
- representative export.

## 8. Render.com

Render Free is suitable for a public demonstration only:

- no persistent disk;
- in-memory HSQLDB and heap index;
- state may reset on redeploy or sleep/restart;
- embedding is normally disabled to fit memory constraints.

Do not describe Render Free as a persistent collaborative deployment. For persistent Render use, select a paid plan with disk or an external database and configure the production persistence variables.

Recommended demo variables:

```text
TAXONOMY_INIT_ASYNC=true
TAXONOMY_EMBEDDING_ENABLED=false
TAXONOMY_ADMIN_PASSWORD=<secret>
TAXONOMY_REQUIRE_PASSWORD_CHANGE=true
TAXONOMY_SWAGGER_PUBLIC=false
```

## 9. Local and network-isolated AI

`LLM_PROVIDER=LOCAL_ONNX` avoids a cloud LLM request, but that setting alone does not prove full network isolation.

A network-isolated deployment must additionally verify:

- the embedding/model files are preloaded and checksummed;
- runtime downloads are disabled;
- browser libraries are served from approved local resources;
- no external fonts, analytics, CDN calls or API endpoints remain;
- the container is tested with outbound network access blocked.

Until that test passes, describe the capability as **local AI execution**, not as fully air-gapped operation.

## 10. Release and supply-chain verification

For each deployment record:

- Git commit and release tag;
- container digest, not only a mutable tag;
- CycloneDX SBOM;
- vulnerability-scan report;
- model revision and checksum;
- Zenodo DOI where an archived research release is used.

`taxonomy-vex.json` is currently SBOM companion metadata with assessment status `not-assessed`. It is not a vulnerability scan or an exploitability statement.

## 11. Go-live verification

Complete [Deployment Checklist](DEPLOYMENT_CHECKLIST.md), including:

1. persistence across container recreation;
2. backup restore;
3. role and CSRF tests;
4. authenticated axe audit and manual accessibility checks;
5. real analysis and export;
6. workspace-specific hypothesis/DSL versioning;
7. monitoring and incident-response ownership.

## Related documentation

- [Configuration Reference](CONFIGURATION_REFERENCE.md)
- [Container Image](CONTAINER_IMAGE.md)
- [Database Setup](DATABASE_SETUP.md)
- [Security](SECURITY.md)
- [Operations Guide](OPERATIONS_GUIDE.md)
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md)
- [Accessibility Evidence](ACCESSIBILITY.md)
