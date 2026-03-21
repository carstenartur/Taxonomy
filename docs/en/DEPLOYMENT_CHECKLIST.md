# Deployment Checklist — Government / Enterprise Environments

This checklist covers all steps required to introduce the Taxonomy Architecture Analyzer in a controlled government or enterprise environment.

---

## Pre-Deployment

- [ ] **Infrastructure approved** — server or container platform provisioned (VM, Kubernetes, Docker host)
- [ ] **Network access** — outbound HTTPS to LLM API endpoints allowed (or `LLM_PROVIDER=LOCAL_ONNX` for air-gapped)
- [ ] **TLS certificates** — valid certificate for the deployment domain, configured in reverse proxy
- [ ] **Database selected** — PostgreSQL, SQL Server, or Oracle provisioned (see [Database Setup](DATABASE_SETUP.md))
- [ ] **Backup strategy defined** — database backups, Lucene index, JGit repository directory
- [ ] **SBOM reviewed** — `target/taxonomy-sbom.json` generated via `mvn package` and reviewed for license compliance

---

## Security Configuration

- [ ] **Activate production profile**: `SPRING_PROFILES_ACTIVE=production,postgres`
- [ ] **Change default admin password**: `TAXONOMY_ADMIN_PASSWORD=<strong-random-password>`
- [ ] **Set admin panel token**: `ADMIN_PASSWORD=<separate-admin-panel-secret>`
- [ ] **Audit logging enabled** (automatic in production profile): `TAXONOMY_AUDIT_LOGGING=true`
- [ ] **Password change enforced** (automatic in production profile): `TAXONOMY_REQUIRE_PASSWORD_CHANGE=true`
- [ ] **Swagger UI restricted** (automatic in production profile): `TAXONOMY_SPRINGDOC_ENABLED=false`
- [ ] **Brute-force protection verified**: `TAXONOMY_LOGIN_RATE_LIMIT=true` (default)
- [ ] **HTTPS enforced** via reverse proxy — HSTS headers already sent by the application

---

## LLM / AI Configuration

- [ ] **LLM provider selected** — set `LLM_PROVIDER` and the corresponding API key environment variable
- [ ] **Rate limits configured** — `TAXONOMY_LLM_RPM` matches provider quota
- [ ] **For air-gapped deployments**: set `LLM_PROVIDER=LOCAL_ONNX`, pre-download embedding model with `TAXONOMY_EMBEDDING_MODEL_DIR`
- [ ] **AI transparency documented** — see [AI Transparency](AI_TRANSPARENCY.md)

---

## Data Protection

- [ ] **Data protection impact assessment (DPIA)** completed if personal data is processed
- [ ] **Data retention policy** defined for audit logs, user accounts, workspace data
- [ ] **Data processing agreement** in place if using cloud-hosted LLM providers
- [ ] **See full details**: [Data Protection](DATA_PROTECTION.md)

---

## User Management

- [ ] **User accounts created** for all initial users via Admin API or GUI
- [ ] **Roles assigned** — USER (analysts), ARCHITECT (architects), ADMIN (administrators)
- [ ] **Keycloak / SSO integration** configured if required (see [Keycloak Setup](KEYCLOAK_SETUP.md))

---

## Monitoring & Operations

- [ ] **Health check endpoint** configured in load balancer: `GET /api/status/startup`
- [ ] **Actuator endpoints** accessible to operations team: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`
- [ ] **Prometheus scraping** configured for `/actuator/prometheus`
- [ ] **Log rotation** configured — see [Operations Guide](OPERATIONS_GUIDE.md)
- [ ] **Alerting** configured for health-check failures and error-rate spikes

---

## Docker Deployment

```bash
docker run -d \
  --name taxonomy-analyzer \
  -p 8080:8080 \
  -v taxonomy-data:/app/data \
  -e SPRING_PROFILES_ACTIVE=production,postgres \
  -e TAXONOMY_ADMIN_PASSWORD=<strong-password> \
  -e ADMIN_PASSWORD=<admin-panel-secret> \
  -e GEMINI_API_KEY=<your-api-key> \
  -e TAXONOMY_DATASOURCE_URL=jdbc:postgresql://db:5432/taxonomy \
  -e SPRING_DATASOURCE_USERNAME=taxonomy \
  -e SPRING_DATASOURCE_PASSWORD=<db-password> \
  ghcr.io/carstenartur/taxonomy:latest
```

---

## Post-Deployment Verification

- [ ] **Application starts** — check `GET /api/status/startup` returns 200
- [ ] **Login works** — authenticate as admin, verify password change prompt
- [ ] **Taxonomy loaded** — `GET /api/taxonomy` returns ~2,500 nodes
- [ ] **Analysis works** — submit a test requirement, verify AI scoring
- [ ] **Export works** — generate ArchiMate XML, verify valid output
- [ ] **Audit logs written** — check application logs for `LOGIN_SUCCESS` entries
- [ ] **Health endpoint** — `/actuator/health` returns UP

---

## BSI AI Compliance

- [ ] **BSI KI Checklist reviewed** — all criteria from [BSI KI Checklist](BSI_KI_CHECKLIST.md) verified for the deployment environment
- [ ] **AI provider selection documented** — rationale for chosen LLM provider recorded (data residency, security classification)
- [ ] **Bias monitoring process defined** — schedule for comparative analysis across providers (see BSI Checklist recommendations)
- [ ] **Review cycle established** — monthly SBOM review, quarterly provider comparison, annual full KI audit

---

## Accessibility (BITV 2.0)

- [ ] **Accessibility concept reviewed** — [Barrierefreiheitskonzept](ACCESSIBILITY.md) reviewed and adapted for deployment context
- [ ] **axe/Lighthouse audit passed** — automated accessibility audit with acceptable score
- [ ] **Keyboard navigation verified** — all core functions reachable via keyboard
- [ ] **Barrierefreiheitserklärung published** — accessibility statement per § 12b BGG for the deployment URL
- [ ] **Feedback mechanism established** — contact point for accessibility issues communicated to users

---

## AI Literacy

- [ ] **AI Literacy training completed** — all users trained per [AI Literacy Concept](AI_LITERACY_CONCEPT.md) before system use
- [ ] **Training attendance documented** — participant list with dates retained for compliance
- [ ] **Refresher training scheduled** — annual refresher or upon significant system changes

---

## Compliance Documents Provided

| Document | Location |
|---|---|
| Security Architecture | [docs/SECURITY.md](SECURITY.md) |
| Data Protection | [docs/DATA_PROTECTION.md](DATA_PROTECTION.md) |
| AI Transparency | [docs/AI_TRANSPARENCY.md](AI_TRANSPARENCY.md) |
| BSI KI Checklist | [docs/BSI_KI_CHECKLIST.md](BSI_KI_CHECKLIST.md) |
| AI Literacy Concept | [docs/AI_LITERACY_CONCEPT.md](AI_LITERACY_CONCEPT.md) |
| Accessibility Concept | [docs/ACCESSIBILITY.md](ACCESSIBILITY.md) |
| Digital Sovereignty | [docs/DIGITAL_SOVEREIGNTY.md](DIGITAL_SOVEREIGNTY.md) |
| Administration Integration | [docs/VERWALTUNGSINTEGRATION.md](VERWALTUNGSINTEGRATION.md) |
| Operations Guide | [docs/OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md) |
| SSO Integration | [docs/KEYCLOAK_SETUP.md](KEYCLOAK_SETUP.md) |
| Third-Party Notices | [THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md) |
| SBOM | `target/taxonomy-sbom.json` (generated at build time) |

---

## Sign-Off

| Checkpoint | Responsible | Date | Signature |
|---|---|---|---|
| Security review completed | | | |
| Data protection review completed | | | |
| Operations readiness confirmed | | | |
| Go-live approved | | | |

---

## Related Documentation

- [Deployment Guide](DEPLOYMENT_GUIDE.md) — detailed deployment instructions (Docker, Render.com)
- [Configuration Reference](CONFIGURATION_REFERENCE.md) — all environment variables
- [Security](SECURITY.md) — security architecture
- [BSI KI Checklist](BSI_KI_CHECKLIST.md) — BSI criteria checklist for AI models
- [AI Literacy Concept](AI_LITERACY_CONCEPT.md) — AI literacy training concept
- [Accessibility](ACCESSIBILITY.md) — BITV 2.0 / WCAG 2.1 accessibility concept
- [Digital Sovereignty](DIGITAL_SOVEREIGNTY.md) — digital sovereignty and openCode compatibility
